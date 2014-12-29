package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

class MockFrameHandler(inHeaders: Boolean) extends FrameHandler {
  override def inHeaderSequence(): Boolean = inHeaders
  override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: ByteBuffer): DecoderResult = ???
  override def onPingFrame(data: Array[Byte], ack: Boolean): DecoderResult = ???
  override def onPushPromiseFrame(streamId: Int, promisedId: Int, end_headers: Boolean, data: ByteBuffer): DecoderResult = ???

  // For handling unknown stream frames
  override def onExtensionFrame(tpe: Int, streamId: Int, flags: Byte, data: ByteBuffer): DecoderResult = ???
  override def onHeadersFrame(streamId: Int, streamDep: Int, exclusive: Boolean, priority: Int, end_headers: Boolean, end_stream: Boolean, buffer: ByteBuffer): DecoderResult = ???

  override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): DecoderResult = ???
  override def onRstStreamFrame(streamId: Int, code: Int): DecoderResult = ???
  override def onPriorityFrame(streamId: Int, streamDep: Int, exclusive: Boolean, priority: Int): DecoderResult = ???
  override def onContinuationFrame(streamId: Int, endHeaders: Boolean, data: ByteBuffer): DecoderResult = ???
  override def onDataFrame(streamId: Int, isLast: Boolean, data: ByteBuffer): DecoderResult = ???
  override def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): DecoderResult = ???
}


class MockHeaderDecodingFrameHandler extends HeaderDecodingFrameHandler {
  override type HeaderType = Seq[(String, String)]

  override def onCompletePushPromiseFrame(headers: HeaderType, streamId: Int, promisedId: Int): DecoderResult = ???

  override def onCompleteHeadersFrame(headers: HeaderType, streamId: Int, streamDep: Int, exclusive: Boolean, priority: Int, end_stream: Boolean): DecoderResult = ???

  override protected val headerDecoder: HeaderDecoder[Seq[(String, String)]] = new SeqTupleHeaderDecoder(20*1024, 4096)

  override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: ByteBuffer): DecoderResult = ???

  override def onPingFrame(data: Array[Byte], ack: Boolean): DecoderResult = ???

  override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): DecoderResult = ???

  // For handling unknown stream frames
  override def onExtensionFrame(tpe: Int, streamId: Int, flags: Byte, data: ByteBuffer): DecoderResult = ???

  override def onRstStreamFrame(streamId: Int, code: Int): DecoderResult = ???

  override def onDataFrame(streamId: Int, isLast: Boolean, data: ByteBuffer): DecoderResult = ???

  override def onPriorityFrame(streamId: Int, streamDep: Int, exclusive: Boolean, priority: Int): DecoderResult = ???

  override def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): DecoderResult = ???
}