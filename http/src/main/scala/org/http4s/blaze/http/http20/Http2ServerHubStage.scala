package org.http4s.blaze.http.http20

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.util

import org.http4s.blaze.pipeline.Command.OutboundCommand
import org.http4s.blaze.pipeline.stages.addons.WriteSerializer
import org.http4s.blaze.pipeline.{Command => Cmd, LeafBuilder}
import org.http4s.blaze.pipeline.stages.HubStage
import org.http4s.blaze.util.BufferTools
import org.http4s.blaze.util.Execution.trampoline

import scala.annotation.tailrec
import scala.concurrent.{Promise, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}


final class Http2ServerHubStage[HType](headerDecoder: HeaderDecoder[HType],
                                       headerEncoder: HeaderEncoder[HType],
                                        node_builder: () => LeafBuilder[NodeMsg.Http2Msg[HType]],
                                       inboundWindow: Int = DefaultSettings.INITIAL_WINDOW_SIZE)
  extends HubStage[ByteBuffer] with WriteSerializer[ByteBuffer]
{ hub =>
  import DefaultSettings._
  import SettingsKeys._

  override def name: String = "Http2ServerHubStage"

  private implicit def ec = trampoline

  type Http2Msg = NodeMsg.Http2Msg[HType]

  override type Key = Int
  override type Out = Http2Msg
  override protected type Attachment = FlowControl.NodeState

  // Using synchronization and this is the lock
  private val lock = new AnyRef

  //////////////////////// The connection state ///////////////////////////////////

  private val idManager = new StreamIdManager
  
  private var outbound_initial_window_size = INITIAL_WINDOW_SIZE
  private var push_enable = ENABLE_PUSH                    // initially enabled
  private var max_streams = MAX_CONCURRENT_STREAMS         // initially unbounded
  private var max_frame_size = MAX_FRAME_SIZE
  private var max_header_size = MAX_HEADER_LIST_SIZE       // initially unbounded

  /////////////////////////////////////////////////////////////////////////////////

  private val codec = new Http20FrameCodec(FHandler) with HeaderHttp20Encoder {
    override type Headers = HType
    override protected val headerEncoder = hub.headerEncoder
  }

  override protected def nodeBuilder(): LeafBuilder[Out] = node_builder()

  // Startup
  override protected def stageStartup() {
    super.stageStartup()

    var settings = Vector.empty[Setting]

    if (inboundWindow != INITIAL_WINDOW_SIZE) {
      settings :+= Setting(SETTINGS_INITIAL_WINDOW_SIZE, inboundWindow)
    }

    if (headerDecoder.maxTableSize != DefaultSettings.HEADER_TABLE_SIZE) {
      settings :+= Setting(SETTINGS_HEADER_TABLE_SIZE, headerDecoder.maxTableSize)
    }

    logger.debug(s"Sending settings: " + settings)
    val buff = lock.synchronized(codec.mkSettingsFrame(false, settings))

    channelWrite(buff).flatMap(_ => channelRead()).onComplete {
      case Success(buff) => doHandshake(buff)
      case Failure(t)    => onFailure(t, "stageStartup")
    }
  }

  // This should only be called once at at startup
  private def doHandshake(buff: ByteBuffer): Unit = {
    logger.trace(s"Handshaking: $buff")

    if (buff.remaining() < clientTLSHandshakeString.length) {
      channelRead(clientTLSHandshakeString.length - buff.remaining()).onComplete {
        case Success(b2) => doHandshake(BufferTools.concatBuffers(buff, b2))
        case Failure(t)  => onFailure(t, "processHandshake")
      }
    } else {
      val l = buff.limit()
      val p = buff.position()
      buff.limit(p + clientTLSHandshakeString.length)
      val header = UTF_8.decode(buff.slice()).toString()
      logger.trace("Received header string: " + header)

      if (header == clientTLSHandshakeString) {
        logger.trace("Handshake complete. Entering readLoop")
        buff.limit(l).position(p + clientTLSHandshakeString.length)
        readLoop(buff)
      } else {
        onFailure(new Exception("Failed to handshake, invalid header: " + header), "processHandshake")
      }
    }
  }

  override protected def stageShutdown(): Unit = {
    closeAllNodes()
    super.stageShutdown()
  }

  // TODO: this is probably wrong
  private def readLoop(buff: ByteBuffer): Unit = {
    logger.trace(s"Received buffer: $buff")
      def go(): Unit = {
        val r = lock.synchronized(codec.decodeBuffer(buff))
        logger.trace("Decoded buffer. Result: " + r)
        r match {
          case Continue => go()
          case Halt => // NOOP TODO: are we ever using `Halt`?
          case Error(t) => onFailure(t, "readLoop Error result")
          case BufferUnderflow =>
            channelRead().onComplete {
              case Success(b2) => readLoop(BufferTools.concatBuffers(buff, b2))
              case Failure(t) => onFailure(t, "ReadLoop")
            }
        }
      }

    try go()
    catch { case t: Throwable => onFailure(t, "readLoop uncaught exception") }
  }

  /** called when a node requests a write operation */
  override protected def onNodeWrite(node: Node, data: Seq[Http2Msg]): Future[Unit] = {
    // TODO: extremely bad performance can be expected from this setup
    data.foldLeft(Future.successful(())){ (f, m) =>
      f.flatMap(_ => lock.synchronized(node.attachment.writeMessage(m)))(trampoline)
    }
  }

  /** called when a node needs more data */
  override protected def onNodeRead(node: Node, size: Int): Future[Http2Msg] = lock.synchronized {
    node.attachment.onNodeRead()
  }

  /** called when a node sends an outbound command */
  override protected def onNodeCommand(node: Node, cmd: OutboundCommand): Unit = lock.synchronized( cmd match {
    case Cmd.Disconnect => removeNode(node)
    case e@Cmd.Error(t) => logger.error(t)(s"Received error from node ${node.key}"); sendOutboundCommand(e)
    case cmd            => logger.warn(s"$name is ignoring unhandled command ($cmd) from $node.")  // Flush, Connect...
  })

  // Shortcut
  private def makeNode(id: Int): Node = super.makeNode(id, FlowControl.newNode(id))
                                             .getOrElse(sys.error(s"Attempted to make stream that already exists"))


  ///////////////////////// The FrameHandler decides how to decode incoming frames ///////////////////////
  
  // All the methods in here will be called from `codec` and thus synchronization can be managed through codec calls
  private object FHandler extends HeaderDecodingFrameHandler {
    override type HeaderType = HType

    override protected val headerDecoder = hub.headerDecoder

    override def onCompletePushPromiseFrame(headers: HeaderType, streamId: Int, promisedId: Int): Http2Result =
      Error(PROTOCOL_ERROR("Server received a PUSH_PROMISE frame from a client", streamId))

    override def onCompleteHeadersFrame(headers: HeaderType,
                                       streamId: Int,
                                       priority: Option[Priority],
                                     end_stream: Boolean): Http2Result =
    {
      val msg = NodeMsg.HeadersFrame(priority, end_stream, headers)

      getNode(streamId) match {
        case None =>
          if (!idManager.checkClientId(streamId)) Error(PROTOCOL_ERROR(s"Invalid streamId", streamId))
          else {
            val node = makeNode(streamId)
            node.startNode()
            node.attachment.inboundMessage(msg, 0)
          }

        case Some(node) => node.attachment.inboundMessage(msg, 0)
      }


    }

    override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: ByteBuffer): Http2Result = {
      val errStr = UTF_8.decode(debugData).toString()
      if (errorCode == NO_ERROR.code) {
        logger.warn(s"Received NO_ERROR goaway frame, msg: $errStr")
      }
      else if (true) {
        logger.warn(s"Received error code $errorCode, msg: $errStr")
      }


      ???
    }

    override def onPingFrame(data: Array[Byte], ack: Boolean): Http2Result = {
      channelWrite(codec.mkPingFrame(data, true))
      Continue
    }

    override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): Http2Result = {
      logger.trace(s"Received settings frames: $settings, ACK: $ack")
      if (ack) Continue    // TODO: ensure client sends acknolegments?
      else {
        val r = processSettings(settings)
        if (r.success) {
          val buff = codec.mkSettingsFrame(true, Nil)
          channelWrite(buff) // write the ACK settings frame
        }
        r
      }
    }

    @tailrec
    private def processSettings(settings: Seq[Setting]): MaybeError = {
      if (settings.isEmpty) Continue
      else {
        val r = settings.head match {
          case Setting(SETTINGS_HEADER_TABLE_SIZE, v) =>
            codec.setEncoderMaxTable(v.toInt)
            Continue

          case Setting(SETTINGS_ENABLE_PUSH, v) =>
            if (v == 0) { push_enable = false; Continue }
            else if (v == 1) {  push_enable = true; Continue }
            else Error(PROTOCOL_ERROR(s"Invalid ENABLE_PUSH setting value: $v"))

          case Setting(SETTINGS_MAX_CONCURRENT_STREAMS, v) =>
            if (v > Integer.MAX_VALUE) {
              Error(PROTOCOL_ERROR(s"To large MAX_CONCURRENT_STREAMS: $v"))
            } else { max_streams = v.toInt; Continue }

          case Setting(SETTINGS_INITIAL_WINDOW_SIZE, v) =>
            if (v > Integer.MAX_VALUE) Error(PROTOCOL_ERROR(s"Invalid initial window size: $v"))
            else { FlowControl.onInitialWindowSizeChange(v.toInt); Continue }

          case Setting(SETTINGS_MAX_FRAME_SIZE, v) =>
            // max of 2^24-1 http/2.0 draft 16 spec
            if (v < MAX_FRAME_SIZE || v > 16777215) Error(PROTOCOL_ERROR(s"Invalid frame size: $v"))
            else { max_frame_size = v.toInt; Continue }


          case Setting(SETTINGS_MAX_HEADER_LIST_SIZE, v) =>
            if (v > Integer.MAX_VALUE) Error(PROTOCOL_ERROR(s"SETTINGS_MAX_HEADER_LIST_SIZE to large: $v"))
            else { max_header_size = v.toInt; Continue }

          case Setting(k, v) =>
            logger.warn(s"Unknown setting ($k, $v)")
            Continue
        }
        if (r.success) processSettings(settings.tail)
        else r
      }
    }

    // For handling unknown stream frames
    override def onExtensionFrame(tpe: Int, streamId: Int, flags: Byte, data: ByteBuffer): Http2Result =
      Error(PROTOCOL_ERROR(s"Unsupported extension frame: type: $tpe, " +
                           s"Stream ID: $streamId, flags: $flags, data: $data", streamId))

    override def onRstStreamFrame(streamId: Int, code: Int): Http2Result = {
      val node = removeNode(streamId)
      if (node.isEmpty) {
        logger.warn(s"Client attempted to reset non-existent stream: $streamId, code: $code")
      }
      else logger.info(s"Stream $streamId reset with code $code")
      Continue
    }

    override def onDataFrame(streamId: Int, isLast: Boolean, data: ByteBuffer, flowSize: Int): Http2Result = {
      getNode(streamId) match {
        case Some(node) =>
          val msg = NodeMsg.DataFrame(isLast, data)
          node.attachment.inboundMessage(msg, flowSize)
          Continue

        case None => Continue  // NOOP // TODO: should this be a stream error?
      }
    }

    override def onPriorityFrame(streamId: Int, priority: Priority): Http2Result = {
      // TODO: should we implement some type of priority handling?
      Continue
    }

    override def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Http2Result = {
      FlowControl.onWindowUpdateFrame(streamId, sizeIncrement)
      Continue
    }
  }

  ///////////////////////////// Error Handling stuff //////////////////////////////////////////

  private def onFailure(t: Throwable, location: String): Unit = {
    logger.trace(t)("Failure: " + location)
    t match {
      case Cmd.EOF =>
        sendOutboundCommand(Cmd.Disconnect)
        stageShutdown()

      case e: Http2Exception =>
        sendGoAway(e).onComplete { _ =>
          sendOutboundCommand(Cmd.Disconnect)
          stageShutdown()
        }

      case t: Throwable =>
        logger.error(t)(s"Unhandled error in $location")
        sendOutboundCommand(Cmd.Error(t))
        stageShutdown()
    }
  }

  private def sendGoAway(e: Http2Exception): Future[Unit] = lock.synchronized {
    val lastStream = idManager.lastClientId()
    val buffs = codec.mkGoAwayFrame(lastStream, e.code, e.msgBuffer())
    channelWrite(buffs, 10.seconds)
  }

  /////////////////////////////////////////////////////////////////////////////////////////////

  protected object FlowControl {
    private var outboundConnectionWindow = outbound_initial_window_size
    private var inboundConnectionWindow = inboundWindow

    def newNode(id: Int): NodeState = new NodeState(id, outbound_initial_window_size)

    def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Unit = {
      if (streamId == 0) {
        outboundConnectionWindow += sizeIncrement

        // Allow all the nodes to attempt to write if they want to
        nodeIterator().forall { node =>
          node.attachment.incrementOutboundWindow(0)
          outboundConnectionWindow > 0
        }
      }
      else getNode(streamId).foreach(_.attachment.incrementOutboundWindow(sizeIncrement))

      logger.debug(s"Updated window of stream $streamId by $sizeIncrement. ConnectionOutbound: $outboundConnectionWindow")
    }

    def onInitialWindowSizeChange(newWindow: Int): Unit = {
      val diff = newWindow - outbound_initial_window_size
      logger.trace(s"Adjusting outbound windows by $diff")
      outbound_initial_window_size = newWindow

      outboundConnectionWindow += diff
      nodeIterator().foreach { node =>
        node.attachment.incrementOutboundWindow(diff)
      }
    }

    class NodeState private[FlowControl](id: Int, private var outboundWindow: Int) {

      private var streamInboundWindow: Int = inboundWindow

      private val pendingInboundMessages = new util.ArrayDeque[Http2Msg](16)
      private var pendingInboundPromise: Promise[Http2Msg] = null

      private var pendingOutboundData: (Promise[Unit], NodeMsg.DataFrame) = null

      def hasPendingOutboundData(): Boolean = pendingOutboundData != null

      def hasPendingInboundData(): Boolean = !pendingInboundMessages.isEmpty

      // TODO: this should be pulled out of FlowControl as its really not flow control logic
      // TODO: this needs to honor the max frame sizes
      def writeMessage(msg: Http2Msg): Future[Unit] = {
        if (pendingOutboundData != null) {
          Future.failed(new IndexOutOfBoundsException("Cannot have more than one pending write request"))
        } else {
          val f = msg match {
            case frame: NodeMsg.DataFrame =>
              val p = Promise[Unit]
              writeDataFrame(p, frame)

            case NodeMsg.HeadersFrame(priority, end_stream, hs) =>
              val buffs = codec.mkHeaderFrame(hs, id, priority, true, end_stream, 0)
              channelWrite(buffs)

            case NodeMsg.PushPromiseFrame(promisedId, end_headers, hs) =>
              val buffs = codec.mkPushPromiseFrame(id, promisedId, end_headers, 0, hs)
              channelWrite(buffs)

            case NodeMsg.ExtensionFrame(tpe, flags, data) =>
              ???   // TODO: what should we do here?
          }

          f
        }
      }

      // Increments the window and checks to see if there are any pending messages to be sent
      def incrementOutboundWindow(size: Int): Unit = {
        outboundWindow += size
        if (outboundWindow > 0 && outboundConnectionWindow > 0 && pendingOutboundData != null) {
          val (p, frame) = pendingOutboundData
          pendingOutboundData = null
          val f = writeDataFrame(p, frame)

          // what a dirty trick to avoid moving through a Promise in `writeDataFrame`
          if (f ne p.future) p.completeWith(f)
        }
      }

      def onNodeRead(): Future[Http2Msg] = {
        if (pendingInboundPromise != null) {
          Future.failed(new IndexOutOfBoundsException("Cannot have more than one pending write request"))
        } else {
          val data = pendingInboundMessages.poll()
          if (data == null) {
            pendingInboundPromise = Promise[Http2Msg]
            pendingInboundPromise.future
          }
          else  Future.successful(data)
        }
      }

      def inboundMessage(msg: Http2Msg, flowSize: Int): Http2Result = {
        val r = decrementInboundWindow(flowSize)
        if (r.success) {
          if (pendingInboundPromise != null) {
            val p = pendingInboundPromise
            pendingInboundPromise = null
            p.success(msg)
          }
          else pendingInboundMessages.offer(msg)
        }
        r
      }

      private def writeDataFrame(p: Promise[Unit], frame: NodeMsg.DataFrame): Future[Unit] = {
        val data = frame.data
        val sz = data.remaining()

        val minWindow = math.min(outboundWindow, outboundConnectionWindow)

        if (minWindow <= 0) {  // Cannot write right now
          assert(pendingOutboundData == null)
          pendingOutboundData = (p, frame)
          p.future
        }
        else if (minWindow < sz) {  // can write a partial frame
          outboundWindow -= minWindow
          outboundConnectionWindow -= minWindow

          val l = data.limit()
          val end = data.position() + minWindow

          data.limit(end)
          val buffs = codec.mkDataFrame(data.slice(), id, false, 0)
          data.limit(l).position(end)
          channelWrite(buffs)
          pendingOutboundData = (p, frame)
          p.future
        }
        else {    // we ignore the promise
          outboundWindow -= sz
          outboundConnectionWindow -= sz

          val buffs = codec.mkDataFrame(data, id, frame.isLast, 0)
          channelWrite(buffs)
        }
      }

      // Decrements the inbound window and if not backed up, sends a window update if the level drops below 50%
      private def decrementInboundWindow(size: Int): MaybeError = {
        if (size > streamInboundWindow || size > inboundConnectionWindow) {
          Error(FLOW_CONTROL_ERROR("Inbound flow control overflow"))
        } else {
          streamInboundWindow -= size
          inboundConnectionWindow -= size

          // If we drop below 50 % and there are no pending messages, top it up
          val bf1 =
            if (streamInboundWindow < 0.5 * inboundWindow && pendingInboundMessages.isEmpty) {
              val buff = codec.mkWindowUpdateFrame(id, inboundWindow - streamInboundWindow)
              streamInboundWindow = inboundWindow
              buff::Nil
            } else Nil

          // TODO: should we check to see if all the streams are backed up?
          val buffs =
            if (inboundConnectionWindow < 0.5 * inboundWindow) {
              val buff = codec.mkWindowUpdateFrame(0, inboundWindow - inboundConnectionWindow)
              inboundConnectionWindow = inboundWindow
              buff::bf1
            } else bf1

          if(buffs.nonEmpty) channelWrite(buffs)

          Continue
        }
      }
    }
  }
}

class StreamIdManager {
  private var _lastClientId: Int = 0
  private var _nextServerId: Int = 2
  
  /** Determine if the client ID is valid based on the stream history */
  def checkClientId(id: Int): Boolean = {
    if (id > _lastClientId && id % 2 == 1) {
      _lastClientId = id
      true
    }
    else false
  }

  /** Get the identifier of the last received client stream */
  def lastClientId(): Int = _lastClientId
  
  /** Get the next valid server id */
  def nextServerId(): Int = {
    val id = _nextServerId
    _nextServerId += 2
    id
  }
}
