package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

import scala.annotation.tailrec
import scala.collection.mutable.Buffer


private[http20] abstract class NodeMsgEncoder[HType](id: Int,
                                               fencoder: Http20FrameEncoder,
                                               hencoder: HeaderEncoder[HType]) {

  import NodeMsg.{DataFrame, HeadersFrame, PushPromiseFrame}

  private type Http2Msg = NodeMsg.Http2Msg[HType]

  protected def encodeMessages(maxPayloadSize: Int, maxWindow: Int, msgs: Seq[Http2Msg], acc: Buffer[ByteBuffer]): (Int, Seq[Http2Msg]) = {

    @tailrec
    def go(msgs: Seq[Http2Msg], written: Int): (Int, Seq[Http2Msg]) = {
      if (msgs.nonEmpty) msgs.head match {
        case d: DataFrame if written < maxWindow =>
          val frameSz = d.data.remaining()
          val wr = encodeDataFrame(maxPayloadSize, maxWindow - written, d, acc)
          val totalWrite = wr + written

          if (wr < frameSz) {
            // only wrote a partial frame
            assert(totalWrite == maxWindow)
            (totalWrite, msgs)
          }
          else go(msgs.tail, wr)

        case d: DataFrame => (written, msgs) // end of window

        case hs: HeadersFrame[HType] =>
          encodeHeaders(maxPayloadSize, hs, acc)
          go(msgs.tail, written)

        case pp: PushPromiseFrame[HType] =>
          encodePromiseFrame(maxPayloadSize, pp, acc)
          go(msgs.tail, written)

      }
      else (written, Nil)
    }
    go(msgs, 0)
  }

  /** Encode the HEADERS frame, splitting the payload if required */
  private def encodeHeaders(maxPayloadSize: Int, hs: HeadersFrame[HType], acc: Buffer[ByteBuffer]): Unit = {
    val hsBuff = hencoder.encodeHeaders(hs.headers)
    val priorityBytes = if (hs.priority.nonEmpty) 5 else 0

    if (hsBuff.remaining() + priorityBytes <= maxPayloadSize) {
      acc ++= fencoder.mkHeaderFrame(hsBuff, id, hs.priority, true, hs.end_stream, 0)
    } else {
      // need to split into HEADERS and CONTINUATION frames
      val l = hsBuff.limit()
      hsBuff.limit(hsBuff.position() + maxPayloadSize - priorityBytes)
      acc ++= fencoder.mkHeaderFrame(hsBuff.slice(), id, hs.priority, false, hs.end_stream, 0)
      // Add the rest of the continuation frames
      hsBuff.limit(l)
      mkContinuationFrames(maxPayloadSize, hsBuff, acc)
    }
  }

  /** Encode the PUSH_PROMISE frame, splitting the payload if required */
  private def encodePromiseFrame(maxPayloadSize: Int, pp: PushPromiseFrame[HType], acc: Buffer[ByteBuffer]): Unit = {
    val hsBuff = hencoder.encodeHeaders(pp.headers)

    if (4 + hsBuff.remaining() <= maxPayloadSize) {
      acc ++= fencoder.mkPushPromiseFrame(id, pp.promisedId, true, 0, hsBuff)
    }
    else {
      // must split it
      val l = hsBuff.limit()
      hsBuff.limit(hsBuff.position() + maxPayloadSize - 4)
      acc ++= fencoder.mkPushPromiseFrame(id, pp.promisedId, false, 0, hsBuff.slice())
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

  /** Encodes as much of the DataFrame as allowed, but it may end unconsumed */
  private def encodeDataFrame(maxPayloadSize: Int, maxWindow: Int, frame: DataFrame, acc: Buffer[ByteBuffer]): Int = {

    val data = frame.data
    val frameSize = data.remaining()

    var written = 0

    do {
      val maxPayload = math.min(maxPayloadSize, maxWindow - written)
      val sz = data.remaining()

      if (sz > maxPayload) { // can write a partial frame
        val l = data.limit()
        val end = data.position() + maxPayload
        data.limit(end)
        acc ++= fencoder.mkDataFrame(data.slice(), id, false, 0)
        data.limit(l).position(end)
        written += maxPayload
      }
      else {
        acc ++= fencoder.mkDataFrame(data, id, frame.isLast, 0)
        written += sz
      }

    } while (written < maxWindow && written < frameSize)

    written
  }
}

