package org.http4s.blaze.pipeline.stages

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.{SSLException, SSLEngine}

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.concurrent.{Promise, Future}
import scala.util.{Failure, Success}

import org.http4s.blaze.pipeline.MidStage
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.util.Execution._
import org.http4s.blaze.util.{BufferTools, ScratchBuffer}
import org.http4s.blaze.util.BufferTools._



final class SSLStage(engine: SSLEngine, maxWrite: Int = 1024*1024) extends MidStage[ByteBuffer, ByteBuffer] {
  import SSLStage._

  def name: String = "SSLStage"

  private val maxNetSize = engine.getSession.getPacketBufferSize
  private val maxBuffer = math.max(maxNetSize, engine.getSession.getApplicationBufferSize)

  ///////////// State maintained by the SSLStage //////////////////////
  private val handshakeQueue = new ListBuffer[DelayedOp]  // serves as our Lock object
  private var readLeftover: ByteBuffer = null
  private def inHandshake() = handshakeQueue.nonEmpty
  /////////////////////////////////////////////////////////////////////

  private sealed trait DelayedOp
  private case class ReadOp(size: Int, p: Promise[ByteBuffer]) extends DelayedOp
  private case class WriteOp(data: Array[ByteBuffer], p: Promise[Unit]) extends DelayedOp


  override def writeRequest(data: Seq[ByteBuffer]): Future[Unit] =
    doWrite(data.toArray, Promise[Unit])

  override def writeRequest(data: ByteBuffer): Future[Unit] =
    doWrite(Array(data), Promise[Unit])

  override def readRequest(size: Int): Future[ByteBuffer] = {
    val p = Promise[ByteBuffer]
    doRead(size, p)
    p.future
  }

  /////////////////////////////////////////////////////////////////////////

  // MUST be called inside synchronized blocks
  private def getQueuedBytes(): ByteBuffer = {
    if (readLeftover != null) {
      val b = readLeftover
      readLeftover = null
      b
    } else emptyBuffer
  }

  private def doRead(size: Int, p: Promise[ByteBuffer]): Unit = handshakeQueue.synchronized {
    if (inHandshake()) handshakeQueue += ReadOp(size, p)
    else readLoop(getQueuedBytes(), size, new ListBuffer, p)
  }

  // Does a channel read, then starts the read
  private def getBufferRead(size: Int, p: Promise[ByteBuffer]): Unit = channelRead(size).onComplete {
    case Success(buff) => handshakeQueue.synchronized {
        readLeftover = BufferTools.concatBuffers(readLeftover, buff)
        doRead(size, p)
      }
    case Failure(t) => p.tryFailure(t)
  }(trampoline)

  private def doWrite(data: Array[ByteBuffer], p: Promise[Unit]): Future[Unit] = handshakeQueue.synchronized {
    if (inHandshake()) handshakeQueue += WriteOp(data, p)
    else writeLoop(data, new ListBuffer, p)

    p.future
  }

  // cleans up any pending requests
  private def handshakeFailure(t: Throwable): Unit = handshakeQueue.synchronized {
    val results = handshakeQueue.result(); handshakeQueue.clear();
    results.foreach {
      case ReadOp(_, p) => p.tryFailure(t)
      case WriteOp(_, p) => p.tryFailure(t)
    }
  }

  /** Perform the SSL Handshake
    *
    * I would say this is by far the most precarious part of this library.
    *
    * @param data inbound ByteBuffer. Should be empty for write based handshakes.
    * @return any leftover inbound data.
    */
  private def sslHandshake(data: ByteBuffer, r: HandshakeStatus): Unit = handshakeQueue.synchronized {
    r match {
      case HandshakeStatus.NEED_UNWRAP =>
        try {
          val o = getScratchBuffer(maxBuffer)
          val r = engine.unwrap(data, o)

          if (r.getStatus == Status.BUFFER_UNDERFLOW) {
            channelRead().onComplete {
              case Success(b) =>
                val sum = concatBuffers(data, b)
                sslHandshake(sum, r.getHandshakeStatus)

              case Failure(t) => handshakeFailure(t)
            }(trampoline)
          }
          else sslHandshake(data, r.getHandshakeStatus)
        } catch {
          case t: SSLException =>
            logger.warn(t)("SSLException in SSL handshake")
            handshakeFailure(t)

          case t: Throwable =>
            logger.error(t)("Error in SSL handshake. HandshakeStatus coming in: " + r)
            handshakeFailure(t)
        }

      case HandshakeStatus.NEED_TASK =>
        try {
          runTasks()
          sslHandshake(data, engine.getHandshakeStatus)
        } catch {
          case t: SSLException =>
            logger.warn(t)("SSLException in SSL handshake while running tasks")
            handshakeFailure(t)

          case t: Throwable =>
            logger.error(t)("Error running handshake tasks")
            handshakeFailure(t)
        }

      case HandshakeStatus.NEED_WRAP =>
        try {
          val o = getScratchBuffer(maxBuffer)
          val r = engine.wrap(emptyBuffer, o)
          o.flip()

          if (r.bytesProduced() < 1) logger.warn(s"SSL Handshake WRAP produced 0 bytes.\n$r")

          channelWrite(copyBuffer(o)).onComplete {
            case Success(_) => sslHandshake(data, r.getHandshakeStatus)
            case Failure(t) => handshakeFailure(t)
          }(trampoline)

        } catch {
          case t: SSLException =>
            logger.warn(t)("SSLException during handshake")
            Future.failed(t)

          case t: Throwable =>
            logger.warn(t)("Error in SSL handshake")
            Future.failed(t)
        }

      case _ =>
        assert(readLeftover == null)
        readLeftover = data
        val ops = handshakeQueue.result(); handshakeQueue.clear()
        logger.trace(s"Submitting backed up ops: $ops")
        ops.foreach {
          case ReadOp(sz, p) => doRead(sz, p)
          case WriteOp(d, p) => doWrite(d, p)
        }
    }
  }

  // If we have at least one output buffer, we won't read more data until another request is made
  private def readLoop(b: ByteBuffer, size: Int, out: ListBuffer[ByteBuffer], p: Promise[ByteBuffer]): Unit = {

    val scratch = getScratchBuffer(maxBuffer)

    @tailrec
    def goRead(): Unit = {
      val r = engine.unwrap(b, scratch)
      logger.debug(s"SSL Read Request Status: $r, $scratch")

      if (r.bytesProduced() > 0) {
        scratch.flip()
        out += copyBuffer(scratch)
        scratch.clear()
      }

      r.getHandshakeStatus match {
        case HandshakeStatus.NOT_HANDSHAKING =>

          r.getStatus() match {
            case Status.OK => goRead()    // successful decrypt, continue

            case Status.BUFFER_UNDERFLOW => // Need more data
              if (b.hasRemaining()) {
                readLeftover = b
              }

              if (out.nonEmpty) p.success(joinBuffers(out)) // We got some data so send it
              else {
                val sz = if (size > 0) math.max(size, maxNetSize) else size
                getBufferRead(sz, p)
              }

            // It is up to the next stage to call shutdown, if that is what they want
            case Status.CLOSED =>
              if (out.nonEmpty) p.success(joinBuffers(out))
              else p.failure(EOF)

            case Status.BUFFER_OVERFLOW =>  // resize and go again
              p.tryComplete(invalidPosition("Buffer overflow in readLoop"))
          }

        case _ => // must need to handshake
          if (out.nonEmpty) { // We've read some data, just offer it up.
            readLeftover = b
            p.success(joinBuffers(out))
          }
          else {
            handshakeQueue += ReadOp(size, p)
            sslHandshake(b, r.getHandshakeStatus)
          }
      }
    }

    try goRead()
    catch {
      case t: SSLException =>
        logger.warn(t)("SSLException during read loop")
        p.tryFailure(t)

      case t: Throwable =>
        logger.warn(t)("Error in SSL read loop")
        p.tryFailure(t)
    }
  }

  private def writeLoop(buffers: Array[ByteBuffer], out: ListBuffer[ByteBuffer], p: Promise[Unit]): Unit = {
    val o = getScratchBuffer(maxBuffer)
    @tailrec
    def goWrite(b: Int): Unit = {    // We try and encode the data buffer by buffer until its gone
      o.clear()
      val r = engine.wrap(buffers, o)

      if (o.position() > 0) { // Accumulate any encoded bytes for output
        o.flip()
        out += copyBuffer(o)
      }

      r.getHandshakeStatus() match {
        case HandshakeStatus.NOT_HANDSHAKING =>
          val buffered = b + r.bytesProduced()

          r.getStatus() match {
            case Status.OK =>   // Successful encode
              if (checkEmpty(buffers)) p.completeWith(channelWrite(out))
              else if (maxWrite > 0 && buffered > maxWrite) {
                channelWrite(out).onComplete {
                  case Success(_)    => doWrite(buffers, p)
                  case f@ Failure(_) => p.tryComplete(f)
                }(trampoline)
              }
              else goWrite(buffered)

            case Status.CLOSED =>
              if (!out.isEmpty) p.completeWith(channelWrite(out))
              else p.tryFailure(EOF)

            case Status.BUFFER_OVERFLOW => // Should always have a large enough buffer
              p.tryComplete(invalidPosition("Buffer Overflow in writeLoop"))

            case Status.BUFFER_UNDERFLOW => // Need more data. Should probably never get here
              p.completeWith(channelWrite(out))
          }

        // r.getHandshakeStatus()
        case _ => // need to handshake
          handshakeQueue += WriteOp(buffers, p)
          val data = getQueuedBytes()

          if (out.nonEmpty) { // need to write
            channelWrite(out).onComplete {
              case Success(_) => sslHandshake(data, r.getHandshakeStatus())
              case Failure(t) => handshakeFailure(t)
            }(trampoline)
          } else sslHandshake(data, r.getHandshakeStatus())
      }
    }

    try goWrite(0)
    catch {
      case t: SSLException =>
        logger.warn(t)("SSLException during writeLoop")
        p.tryFailure(t)

      case t: Throwable =>
        logger.error(t)("Error in SSL writeLoop")
        p.tryFailure(t)
    }
  }

  private def invalidPosition(pos: String): Failure[Nothing] = {
    val e = new Exception("Invalid position: end of write loop")
    logger.error(e)(pos)
    Failure(e)
  }

  private def runTasks() {
    var t = engine.getDelegatedTask
    while(t != null) {
      t.run()
      t = engine.getDelegatedTask
    }
  }
}

private object SSLStage extends ScratchBuffer
