package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

trait FrameHandler {

  def inHeaderSequence(): Boolean

  def onDataFrame(streamId: Int, isLast: Boolean, data: ByteBuffer, flowSize: Int): DecoderResult

  def onHeadersFrame(streamId: Int,
                    streamDep: Int,
                    exclusive: Boolean,
                     priority: Int,
                  end_headers: Boolean,
                   end_stream: Boolean,
                   buffer: ByteBuffer): DecoderResult

  def onPriorityFrame(streamId: Int, streamDep: Int, exclusive: Boolean, priority: Int): DecoderResult

  def onRstStreamFrame(streamId: Int, code: Int): DecoderResult

  def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): DecoderResult

  def onPushPromiseFrame(streamId: Int, promisedId: Int, end_headers: Boolean, data: ByteBuffer): DecoderResult

  def onPingFrame(data: Array[Byte], ack: Boolean): DecoderResult

  def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: ByteBuffer): DecoderResult

  def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): DecoderResult

  def onContinuationFrame(streamId: Int, endHeaders: Boolean, data: ByteBuffer): DecoderResult

  // For handling unknown stream frames
  def onExtensionFrame(tpe: Int, streamId: Int, flags: Byte, data: ByteBuffer): DecoderResult

  ///////////// Error Handling //////////////////////////////////
}

