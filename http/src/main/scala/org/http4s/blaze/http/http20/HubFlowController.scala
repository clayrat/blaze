package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

import org.http4s.blaze.pipeline.TailStage

import scala.annotation.tailrec
import scala.collection.mutable.Buffer


private[http20] abstract class NodeState[HType] (id: Int,
                                           fencoder: Http20FrameEncoder,
                                           hencoder: HeaderEncoder[HType]) {

  import NodeMsg.{ DataFrame, HeadersFrame, PushPromiseFrame, ExtensionFrame }
  private type Http2Msg = NodeMsg.Http2Msg[HType]

  private var pendingOutboundDataFrame: DataFrame = null

  def pendingOutputData(): Int = {
    if (pendingOutboundDataFrame == null) 0
    else pendingOutboundDataFrame.data.remaining()
  }

  /** Encodes the messages, returning the bytes written that count toward flow windows */
  protected def encodeMessage(maxFramePayload: Int, maxWindow: Int, msg: Http2Msg, acc: Buffer[ByteBuffer]): Int = {
    msg match {
      case frame: DataFrame =>
        val d = frame.data
        var maxWrite = maxWindow

        do {
          maxWrite -= makeDataFrames(math.min(maxWrite, maxWindow), frame, acc)
        } while (maxWrite > 0 && d.hasRemaining)

        if (d.hasRemaining) { // not finished with this frame.
          pendingOutboundDataFrame = frame
        }

        maxWindow - maxWrite

      case hs: HeadersFrame[HType] =>
        encodeHeaders(maxFramePayload, hs, acc)
        0

      case PushPromiseFrame(promisedId, hs) =>
        encodePromiseFrame(maxFramePayload, promisedId, hs, acc)
        0

      case ExtensionFrame(tpe, flags, data) =>
        ???   // TODO: what should we do here?
    }
  }

  private def encodePromiseFrame(maxPayloadSize: Int, promisedId: Int, hs: HType, acc: Buffer[ByteBuffer]): Unit = {
    val hsBuff = hencoder.encodeHeaders(hs)

    if (4 + hsBuff.remaining() <= maxPayloadSize) {
      acc ++= fencoder.mkPushPromiseFrame(id, promisedId, true, 0, hsBuff)
    }
    else {    // must split it
      val l = hsBuff.limit()
      hsBuff.limit(hsBuff.position() + maxPayloadSize - 4)
      acc ++= fencoder.mkPushPromiseFrame(id, promisedId, false, 0, hsBuff.slice())
      // Add the rest of the continuation frames
      hsBuff.limit(l)
      mkContinuationFrames(maxPayloadSize, hsBuff, acc)
    }
  }

  private def encodeHeaders(maxPayloadSize: Int, hs: HeadersFrame[HType], acc: Buffer[ByteBuffer]): Unit = {
    val hsBuff = hencoder.encodeHeaders(hs.headers)
    val priorityBytes = if (hs.priority.nonEmpty) 5 else 0

    if (hsBuff.remaining() + priorityBytes <= maxPayloadSize) {
      acc ++= fencoder.mkHeaderFrame(hsBuff, id, hs.priority, true, hs.end_stream, 0)
    } else {    // need to split into HEADERS and CONTINUATION frames
      val l = hsBuff.limit()
      hsBuff.limit(hsBuff.position() + maxPayloadSize - priorityBytes)
      acc ++= fencoder.mkHeaderFrame(hsBuff.slice(), id, hs.priority, false, hs.end_stream, 0)
      // Add the rest of the continuation frames
      hsBuff.limit(l)
      mkContinuationFrames(maxPayloadSize, hsBuff, acc)
    }
  }

  // Split the remaining header data into CONTINUATION frames.
  @tailrec
  private def mkContinuationFrames(maxPayload: Int, hBuff: ByteBuffer, acc: Buffer[ByteBuffer]): Unit = {
    if (hBuff.remaining() >= maxPayload) {
      acc ++= fencoder.mkContinuationFrame(id, true, hBuff)
    }
    else {
      val l = hBuff.limit()
      hBuff.limit(hBuff.position() + maxPayload)
      acc ++= fencoder.mkContinuationFrame(id, false, hBuff.slice())
      hBuff.limit(l)
      mkContinuationFrames(maxPayload, hBuff, acc)
    }

  }

  // Writes the data frame to the accumulator, stopping if the payload exceeds the specified amount
  // and return the payload size written. Note that the whole frame is not necessarily consumed
  private def makeDataFrames(maxPayload: Int, frame: DataFrame, acc: Buffer[ByteBuffer]): Int = {
    val data = frame.data
    val sz = data.remaining()

    if (sz > maxPayload) {  // can write a partial frame
      val l = data.limit()
      val end = data.position() + maxPayload

      data.limit(end)
      acc ++= fencoder.mkDataFrame(data.slice(), id, false, 0)
      data.limit(l).position(end)
      maxPayload
    }
    else {    // we ignore the promise
      acc ++= fencoder.mkDataFrame(data, id, frame.isLast, 0)
      sz
    }
  }
}
