package org.http4s.blaze.http.http20

import java.nio.ByteBuffer
import java.util

import org.http4s.blaze.pipeline.Command.{EOF, OutboundCommand}
import org.http4s.blaze.pipeline.{Command, LeafBuilder}
import org.http4s.blaze.pipeline.stages.HubStage

import scala.concurrent.{Promise, ExecutionContext, Future}
import scala.util.{Failure, Success}

// The hub stage will send messages without waiting for ACKs so you must ensure the outbound
// pipeline will not fail when receiving multiple messages
abstract class Http2ServerHubStage[HType](headerDecoder: HeaderDecoder[HType],
                                          headerEncoder: HeaderEncoder[HType],
                                          ec: ExecutionContext,
                                          inboundWindow: Int = 65535) extends HubStage[ByteBuffer](ec) { self =>

  type Http2Msg = NodeMsg.Http2Msg[HType]

  // Using synchronization and this is the lock
  private val lock = new AnyRef

  private val INITIAL_MAX_FRAME_SIZE = 16384        // section 6.5.2 of the http/2.0 draft 16 spec

  override type Key = Int
  override type Out = Http2Msg
  override protected type Attachment = FlowControl.NodeState

  private val idManager = new StreamIdManager
  
  private var outbound_initial_window_size = 65535  // section 6.9.2 of the http/2.0 draft 16 spec
  private var push_enable = true                    // enabled by default
  private var max_streams = Long.MaxValue
  private var max_frame_size = INITIAL_MAX_FRAME_SIZE

  { // Startup
    if (inboundWindow != 65535) { // not the default, need to set settings frame
      ???
    }
  }

  // TODO: this is probably wrong
  private def readLoop(): Unit = channelRead().onComplete {
    case Success(buff) => lock.synchronized {
      codec.decodeBuffer(buff) match {
        case Continue => readLoop()
        case Halt => // NOOP
        case Error(t) => sendOutboundCommand(Command.Error(t))
      }
    }

    case Failure(EOF) => ???

    case Failure(t) => sendOutboundCommand(Command.Error(t))
  }

  override protected def nodeBuilder(): LeafBuilder[Http2Msg]

  /** called when a node requests a write operation */
  override protected def onNodeWrite(node: Node, data: Seq[Http2Msg]): Future[Unit] = lock.synchronized {
    // TODO: extremely bad performance
    data.foldLeft(Future.successful(())){ (f, m) => f.flatMap(_ => node.attachment.writeMessage(m)) }
  }

  /** called when a node needs more data */
  override protected def onNodeRead(node: Node, size: Int): Future[Http2Msg] = lock.synchronized {
    node.attachment.onNodeRead()
  }

  /** called when a node sends an outbound command */
  override protected def onNodeCommand(node: Node, cmd: OutboundCommand): Unit = lock.synchronized {
    ???
  }
  
  // This will throw exceptions and therefor interaction should happen in a try block
  private val codec = new Http20FrameCodec(new FHandler) with HeaderHttp20Encoder {
    override type Headers = HType
    override protected val headerEncoder = self.headerEncoder
  }

  // Shortcut
  private def makeNode(id: Int): Node = super.makeNode(id, FlowControl.newNode(id))


  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  
  // All the methods in here will be called from `codec` and thus synchronization can be managed through codec calls
  // By the same reasoning, errors are thrown in here and should be caught through the interaction with `codec`
  private class FHandler extends HeaderDecodingFrameHandler {
    override type HeaderType = HType

    override protected val headerDecoder = self.headerDecoder

    override def onCompletePushPromiseFrame(headers: HeaderType, streamId: Int, promisedId: Int): DecoderResult = {
      throw PROTOCOL_ERROR("Server received a PUSH_PROMISE frame from a client")
    }

    override def onCompleteHeadersFrame(headers: HeaderType, streamId: Int, streamDep: Int, exclusive: Boolean, priority: Int, end_stream: Boolean): DecoderResult = {
      val node = getNode(streamId).getOrElse {
          if (!idManager.checkClientId(streamId)) {
          // Invalid streamId
            throw PROTOCOL_ERROR(s"Invalid streamId: $streamId")
          }

          makeNode(streamId)
      }

      val msg = NodeMsg.HeadersFrame(streamDep, exclusive, end_stream, headers)
      node.attachment.inboundMessage(msg, 0)
      Continue
    }

    override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: ByteBuffer): DecoderResult = ???

    override def onPingFrame(data: Array[Byte], ack: Boolean): DecoderResult = {
      channelWrite(codec.mkPingFrame(data, true))
      Continue
    }

    override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): DecoderResult = {
      import SettingsKeys._

      var continue = true
      settings.foreach {
        case Setting(SETTINGS_HEADER_TABLE_SIZE, v)      => codec.setEncoderMaxTable(v.toInt)

        case Setting(SETTINGS_ENABLE_PUSH, v)            =>
          if      (v == 0) push_enable =  false
          else if (v == 1) push_enable =  true
          else throw PROTOCOL_ERROR(s"Invalid ENABLE_PUSH setting value: $v")

        case Setting(SETTINGS_MAX_CONCURRENT_STREAMS, v) => max_streams = v

        case Setting(SETTINGS_INITIAL_WINDOW_SIZE, v)    =>
          if (v > Integer.MAX_VALUE) throw PROTOCOL_ERROR(s"Invalid initial window size: $v")
          FlowControl.onInitialWindowSizeChange(v.toInt)

        case Setting(SETTINGS_MAX_FRAME_SIZE, v)         =>
          if (v < INITIAL_MAX_FRAME_SIZE || v > 16777215) { // max of 2^24-1 http/2.0 draft 16 spec
            throw PROTOCOL_ERROR(s"Invalid frame size: $v")
          }
          max_frame_size = v.toInt

        case Setting(SETTINGS_MAX_HEADER_LIST_SIZE, v)   => ???

        case Setting(k, v)   => logger.warn(s"Unknown setting ($k, $v)")
      }

      if (continue) Continue else Halt
    }

    // For handling unknown stream frames
    override def onExtensionFrame(tpe: Int, streamId: Int, flags: Byte, data: ByteBuffer): DecoderResult = ???

    override def onRstStreamFrame(streamId: Int, code: Int): DecoderResult = {
      if (removeNode(streamId).isEmpty) {
        val msg = s"Client attempted to reset non-existent stream: $streamId, code: $code"
        logger.warn(msg)
        throw new PROTOCOL_ERROR(msg)
      }
      logger.info(s"Stream $streamId reset with ")
      Continue
    }

    override def onDataFrame(streamId: Int, isLast: Boolean, data: ByteBuffer, flowSize: Int): DecoderResult = {
      getNode(streamId) match {
        case Some(node) =>
          val msg = NodeMsg.DataFrame(isLast, data)
          node.attachment.inboundMessage(msg, flowSize)
          Continue

        case None => ???    // TODO: should this be a stream error?
      }
    }

    override def onPriorityFrame(streamId: Int, streamDep: Int, exclusive: Boolean, priority: Int): DecoderResult = {
      // TODO: should we implement some type of priority handling?
      Continue
    }

    override def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): DecoderResult = {
      FlowControl.onWindowUpdateFrame(streamId, sizeIncrement)
      Continue
    }
  }

  /////////////////////////////////////////////////////////////////////////////////////////////

  object FlowControl {
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
    }

    def onInitialWindowSizeChange(newWindow: Int): Unit = {
      val diff = outbound_initial_window_size - newWindow
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

      def writeMessage(msg: Http2Msg): Future[Unit] = {
        if (pendingOutboundData != null) {
          Future.failed(new IndexOutOfBoundsException("Cannot have more than one pending write request"))
        } else {
          val f = msg match {
            case frame: NodeMsg.DataFrame =>
              val p = Promise[Unit]
              writeDataFrame(p, frame)

            case NodeMsg.HeadersFrame(dep, ex, end_stream, hs) =>
              val priority = if (dep > 0) 16 else -1
              val buffs = codec.mkHeaderFrame(hs, id, dep, ex, priority, true, end_stream, 0)
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

      def inboundMessage(msg: Http2Msg, flowSize: Int): Unit = {
        decrementInboundWindow(flowSize)

        if (pendingInboundPromise != null) {
          val p = pendingInboundPromise
          pendingInboundPromise = null

          p.success(msg)
        }
        else pendingInboundMessages.offer(msg)
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

      private def decrementInboundWindow(size: Int): Unit = {
        if (size > streamInboundWindow || size > inboundConnectionWindow) throw FLOW_CONTROL_ERROR("Inbound flow control overflow")
        streamInboundWindow -= size
        inboundConnectionWindow -= size

        checkInboundFlowWindow()
      }

      private def checkInboundFlowWindow(): Unit = {
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
