package org.http4s.blaze.pipeline.stages

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.{SSLException, SSLEngine}

import scala.annotation.tailrec
import scala.collection.mutable.{ ArrayBuffer, ListBuffer, Buffer }
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

  private sealed trait SSLResult
  private case object Ok extends SSLResult
  private case class NeedHandshake(r: HandshakeStatus) extends SSLResult
  private case class Failed(t: Throwable) extends SSLResult

  /////////////////////////////////////////////////////////////////////

  override def writeRequest(data: Seq[ByteBuffer]): Future[Unit] =
    syncWrite(data.toArray)

  override def writeRequest(data: ByteBuffer): Future[Unit] =
    syncWrite(Array(data))

  override def readRequest(size: Int): Future[ByteBuffer] = {
    val p = Promise[ByteBuffer]
    doRead(size, p)
    p.future
  }

  /////////////////////////////////////////////////////////////////////

  // MUST be called inside synchronized blocks
  private def takeQueuedBytes(): ByteBuffer = {
    if (readLeftover != null) {
      val b = readLeftover
      readLeftover = null
      b
    } else emptyBuffer
  }

  private def doRead(size: Int, p: Promise[ByteBuffer]): Unit = handshakeQueue.synchronized {
    if (inHandshake()) handshakeQueue += ReadOp(size, p)
    else {
      val out = new ArrayBuffer[ByteBuffer]
      val b = takeQueuedBytes()
      val r = readLoop(b, out)

      if (b.hasRemaining()) {
        readLeftover = b
      }

      r match {
        case Ok if out.nonEmpty => p.trySuccess(joinBuffers(out))

        case Ok => // buffer underflow and no data to send
          channelRead(if (size > 0) math.max(size, maxNetSize) else size).onComplete {
            case Success(buff) => handshakeQueue.synchronized {
                readLeftover = concatBuffers(readLeftover, buff)
                doRead(size, p)
              }

            case Failure(t) => p.tryFailure(t)
          }(trampoline)

        case NeedHandshake(r) =>
          handshakeQueue += ReadOp(size, p)
          val data = takeQueuedBytes()
          sslHandshake(data, r)

        case Failed(t) => p.tryFailure(t)
      }
    }
  }

  // cleans up any pending requests
  private def handshakeFailure(t: Throwable): Unit = handshakeQueue.synchronized {
    val results = handshakeQueue.result(); handshakeQueue.clear();
    results.foreach {
      case ReadOp(_, p) => p.tryFailure(t)
      case WriteOp(_, p) => p.tryFailure(t)
    }
  }

  /** Perform the SSL Handshake and then continue any pending operations
    *
    * There should be at least one pending operation as this is only started
    * after such an operation is stashed.
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
        val pendingOps = handshakeQueue.result(); handshakeQueue.clear()
        logger.trace(s"Submitting backed up ops: $pendingOps")
        pendingOps.foreach {
          case ReadOp(sz, p) => doRead(sz, p)
          case WriteOp(d, p) => continueWrite(d, p)
        }
    }
  }

  // Read as much data from the buffer, `b` as possible and put the result in
  // the accumulator `out`. It should only modify its arguments
  private def readLoop(b: ByteBuffer, out: Buffer[ByteBuffer]): SSLResult = {
    val scratch = getScratchBuffer(maxBuffer)

    @tailrec
    def goRead(): SSLResult = {
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
              Ok

            case Status.CLOSED =>
              if (out.nonEmpty) Ok
              else Failed(EOF)

            case Status.BUFFER_OVERFLOW =>  // resize and go again
              Failed(new Exception("Shouldn't get here: Buffer overflow in readLoop"))
          }

        case _ => // must need to handshake
          if (out.nonEmpty) Ok
          else NeedHandshake(r.getHandshakeStatus)
      }
    }

    try goRead()
    catch {
      case t: SSLException =>
        logger.warn(t)("SSLException during read loop")
        Failed(t)

      case t: Throwable =>
        logger.warn(t)("Error in SSL read loop")
        Failed(t)
    }
  }

  // Attempts to write the data synchronously, but falls back to using
  // a Promise if it needs to do intermediate handshaking or writes
  // NOTE: this is not strictly necessary, as we can start with continueWrite and a
  // Promise but this gives a small performance boost for the general case
  private def syncWrite(data: Array[ByteBuffer]): Future[Unit] = handshakeQueue.synchronized {
    if (inHandshake()) {
      val p = Promise[Unit]
      handshakeQueue += WriteOp(data, p)
      p.future
    }
    else {
      val out = new ArrayBuffer[ByteBuffer]
      writeLoop(data, out) match {
        case Ok if BufferTools.checkEmpty(data) => channelWrite(out)
        case Ok => // must have more data to write
          val p = Promise[Unit]
          channelWrite(out).onComplete {
            case Success(_) => continueWrite(data, p)
            case Failure(t) => p.tryFailure(t)
          }(trampoline)
          p.future

        case NeedHandshake(r) =>
          val p = Promise[Unit]
          handshakeQueue += WriteOp(data, p)
          val readData = takeQueuedBytes()
          if (out.nonEmpty) { // need to write
            channelWrite(out).onComplete {
              case Success(_) => sslHandshake(readData, r)
              case Failure(t) => handshakeFailure(t)
            }(trampoline)
          } else sslHandshake(readData, r)

          p.future

        case Failed(t) => Future.failed(t)
      }
    }
  }

  // Attempts to continue write requests
  private def continueWrite(data: Array[ByteBuffer], p: Promise[Unit]): Unit = handshakeQueue.synchronized {
    if (inHandshake()) handshakeQueue += WriteOp(data, p)
    else {
      val out = new ArrayBuffer[ByteBuffer]
      writeLoop(data, out) match {
        case Ok if BufferTools.checkEmpty(data) =>
          p.completeWith(channelWrite(out))

        case Ok => // must have more data to write
          channelWrite(out).onComplete {
            case Success(_) => continueWrite(data, p)
            case Failure(t) => p.tryFailure(t)
          }(trampoline)

        case NeedHandshake(r) =>
          handshakeQueue += WriteOp(data, p)
          val readData = takeQueuedBytes()
          if (out.nonEmpty) { // need to write
            channelWrite(out).onComplete {
              case Success(_) => sslHandshake(readData, r)
              case Failure(t) => handshakeFailure(t)
            }(trampoline)
          } else sslHandshake(readData, r)

        case Failed(t) => p.tryFailure(t)
      }
    }
  }

  // this should just write as much data to the accumulator as possible only
  // modify its input arguments
  private def writeLoop(buffers: Array[ByteBuffer], out: Buffer[ByteBuffer]): SSLResult = {
    val o = getScratchBuffer(maxBuffer)
    @tailrec
    def goWrite(b: Int): SSLResult = {    // We try and encode the data buffer by buffer until its gone
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
              if (checkEmpty(buffers) || maxWrite > 0 && buffered > maxWrite) Ok
              else goWrite(buffered)

            case Status.CLOSED => Failed(EOF)

            case s => Failed(new Exception(s"Invalid status in SSL writeLoop: $s"))
          }

        case _ => NeedHandshake(r.getHandshakeStatus) // need to handshake
      }
    }

    try {
      val r = goWrite(0)
      BufferTools.dropEmpty(buffers)   // let the GC have the empty buffers
      r
    } catch {
      case t: SSLException =>
        logger.warn(t)("SSLException during writeLoop")
        Failed(t)

      case t: Throwable =>
        logger.error(t)("Error in SSL writeLoop")
        Failed(t)
    }
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
