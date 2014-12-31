package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

class MockFrameHandler(inHeaders: Boolean) extends FrameHandler {
  override def inHeaderSequence(): Boolean = inHeaders
  override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: ByteBuffer): Http2Result = ???
  override def onPingFrame(data: Array[Byte], ack: Boolean): Http2Result = ???
  override def onPushPromiseFrame(streamId: Int, promisedId: Int, end_headers: Boolean, data: ByteBuffer): Http2Result = ???

  // For handling unknown stream frames
  override def onExtensionFrame(tpe: Int, streamId: Int, flags: Byte, data: ByteBuffer): Http2Result = ???
  override def onHeadersFrame(streamId: Int, streamDep: Int, exclusive: Boolean, priority: Int, end_headers: Boolean, end_stream: Boolean, buffer: ByteBuffer): Http2Result = ???

  override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): Http2Result = ???
  override def onRstStreamFrame(streamId: Int, code: Int): Http2Result = ???
  override def onPriorityFrame(streamId: Int, streamDep: Int, exclusive: Boolean, priority: Int): Http2Result = ???
  override def onContinuationFrame(streamId: Int, endHeaders: Boolean, data: ByteBuffer): Http2Result = ???
  override def onDataFrame(streamId: Int, isLast: Boolean, data: ByteBuffer, flowSize: Int): Http2Result = ???
  override def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Http2Result = ???
}


class MockHeaderDecodingFrameHandler extends HeaderDecodingFrameHandler {
  override type HeaderType = Seq[(String, String)]

  override def onCompletePushPromiseFrame(headers: HeaderType, streamId: Int, promisedId: Int): Http2Result = ???

  override def onCompleteHeadersFrame(headers: HeaderType, streamId: Int, streamDep: Int, exclusive: Boolean, priority: Int, end_stream: Boolean): Http2Result = ???

  override protected val headerDecoder: HeaderDecoder[Seq[(String, String)]] = new SeqTupleHeaderDecoder(20*1024, 4096)

  override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: ByteBuffer): Http2Result = ???

  override def onPingFrame(data: Array[Byte], ack: Boolean): Http2Result = ???

  override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): Http2Result = ???

  // For handling unknown stream frames
  override def onExtensionFrame(tpe: Int, streamId: Int, flags: Byte, data: ByteBuffer): Http2Result = ???

  override def onRstStreamFrame(streamId: Int, code: Int): Http2Result = ???

  override def onDataFrame(streamId: Int, isLast: Boolean, data: ByteBuffer, flowSize: Int): Http2Result = ???

  override def onPriorityFrame(streamId: Int, streamDep: Int, exclusive: Boolean, priority: Int): Http2Result = ???

  override def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Http2Result = ???
}