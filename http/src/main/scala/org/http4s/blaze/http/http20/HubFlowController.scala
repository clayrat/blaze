//package org.http4s.blaze.http.http20
//
//import java.nio.ByteBuffer
//import java.util
//
//import scala.collection.mutable.Buffer
//
//
//abstract class NodeState[HType] private[FlowControl](id: Int,
//                                                encoder: Http20FrameEncoder,
//                                               hencoder: HeaderEncoder[HType]) {
//
//  private type Http2Msg = NodeMsg.Http2Msg[HType]
//
//  private val pendingOutboundData = new util.ArrayDeque[NodeMsg.DataFrame](16)
//
//  def pendingOutboundFrames(): Int = pendingOutboundData.size()
//
//
//
//  /** Encodes the messages, returning the bytes written that count toward flow windows */
//  private def encodeMessage(maxFramePayload: Int, maxWindow: Int, msg: Http2Msg, acc: Buffer[ByteBuffer]): Int = {
//    msg match {
//      case frame: NodeMsg.DataFrame =>
//        var maxWrite = maxWindow
//        val d = frame.data
//
//        do maxWrite -= makeDataFrames(math.min(maxWrite, maxWindow), frame, acc)
//        while (maxWrite > 0 && d.hasRemaining)
//
//        if (d.hasRemaining) { // not finished with this frame.
//          pendingOutboundData.push(frame)
//        }
//        maxWindow - maxWrite
//
//      case hs: NodeMsg.HeadersFrame =>
//
//
//
//
//
//      case NodeMsg.PushPromiseFrame(promisedId, end_headers, hs) =>
//        val buffs = encoder.mkPushPromiseFrame(id, promisedId, end_headers, 0, hs)
//
//      case NodeMsg.ExtensionFrame(tpe, flags, data) =>
//        ???   // TODO: what should we do here?
//    }
//  }
//
//  private def encodeHeaders(maxPayloadSize: Int, hs: NodeMsg.HeadersFrame[HType], acc: Buffer[ByteBuffer]): Unit = {
//    val priority = if (hs.streamDep > 0) 16 else -1
//    val hsBuff = hencoder.encodeHeaders(hs.headers, true)
//
//    val addedBytes = 0 ???
//
//    if (hsBuff.remaining() <= maxPayloadSize) {
//      acc ++= encoder.mkHeaderFrame(hsBuff, id, hs.streamDep, hs.exclusive, priority, true, hs.end_stream, 0)
//    } else {    // need to split headers
//
//
//    }
//  }
//
//  private def makeDataFrames(maxPayload: Int, frame: NodeMsg.DataFrame, acc: Buffer[ByteBuffer]): Int = {
//    val data = frame.data
//    val sz = data.remaining()
//
//    if (sz > maxPayload) {  // can write a partial frame
//      val l = data.limit()
//      val end = data.position() + maxPayload
//
//      data.limit(end)
//      acc ++= encoder.mkDataFrame(data.slice(), id, false, 0)
//      data.limit(l).position(end)
//      maxPayload
//    }
//    else {    // we ignore the promise
//      acc ++= encoder.mkDataFrame(data, id, frame.isLast, 0)
//      sz
//    }
//  }
//}
