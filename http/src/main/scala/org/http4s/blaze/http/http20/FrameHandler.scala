package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

import org.http4s.blaze.http.http20.FrameHandler.Http2Exception

import scala.util.control.NoStackTrace

import com.twitter.hpack.HeaderListener

trait HeaderBuilder[Result] extends HeaderListener {

  /** Returns the header collection and clears this builder */
  def result(): Result
}

trait FrameHandler {

  def onDataFrame(data: ByteBuffer, streamId: Int, isLast: Boolean): Boolean

  def onHeadersFrame(buffer: ByteBuffer,
                   streamId: Int,
                  streamDep: Int,
                  exclusive: Boolean,
                   priority: Int,
                end_headers: Boolean,
                 end_stream: Boolean): Boolean

  def onPriorityFrame(streamId: Int, streamDep: Int, exclusive: Boolean): Boolean

  def onRstStreamFrame(streamId: Int, code: Int): Boolean

  // Each setting is handled one at a time. false signals the decoding process to halt
  def handleSetting(id: Int, value: Long): Boolean

  def onPushPromiseFrame(streamId: Int, promisedId: Int, end_headers: Boolean, data: ByteBuffer): Boolean

  def onPingFrame(data: Array[Byte], ack: Boolean): Boolean

  def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: ByteBuffer): Boolean

  def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Boolean

  def onContinuationFrame(streamId: Int, endHeaders: Boolean, data: ByteBuffer): Boolean

  // For handling unknown stream frames
  def onUnknownFrame(tpe: Int, streamId: Int, flags: Byte, data: ByteBuffer): Boolean

  ///////////// Error Handling //////////////////////////////////

  def onError(error: Http2Exception): Unit
}

object FrameHandler {
  sealed abstract class Http2Exception(code: Int, msg: String) extends Exception(msg) with NoStackTrace

  class NO_ERROR(msg: String)                                      extends Http2Exception(0x0, msg)
  class PROTOCOL_ERROR(msg: String)                                extends Http2Exception(0x1, msg)
  class INTERNAL_ERROR(msg: String)                                extends Http2Exception(0x2, msg)
  class FLOW_CONTROL_ERROR(msg: String)                            extends Http2Exception(0x3, msg)
  class SETTINGS_TIMEOUT(msg: String)                              extends Http2Exception(0x4, msg)
  class STREAM_CLOSED(msg: String)                                 extends Http2Exception(0x5, msg)
  class FRAME_SIZE_ERROR(msg: String, expected: Int, found: Int)   extends Http2Exception(0x6, msg)
  class REFUSED_STREAM(id: Int)                                    extends Http2Exception(0x7, s"Stream $id refused")
}
