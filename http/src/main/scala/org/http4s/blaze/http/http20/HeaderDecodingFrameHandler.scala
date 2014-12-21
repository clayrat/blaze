package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

import com.twitter.hpack.{Decoder, HeaderListener}
import org.http4s.blaze.util.BufferTools

trait HeaderBuilder[Result] extends HeaderListener {

  /** Returns the header collection and clears this builder */
  def result(): Result
}

abstract class HeaderDecodingFrameHandler(maxHeaderSize: Int) extends FrameHandler {

  type HeaderType

  private case class HeadersInfo(sId: Int, sDep: Int, ex: Boolean, priority: Int, end_stream: Boolean, isPromise: Boolean, var buffer: ByteBuffer)

  private val headerDecoder = new Decoder(maxHeaderSize, maxHeaderSize)

  private var hBuilder: HeaderBuilder[HeaderType] = null
  private var hInfo: HeadersInfo = null


  ///////////////////////////////////////////////////////////////////////////

  def getHeaderBuilder(): HeaderBuilder[HeaderType]

  def onCompleteHeadersFrame(headers: HeaderType, streamId: Int, streamDep: Int, exclusive: Boolean, priority: Int, end_stream: Boolean): DecoderResult

  def onCompletePushPromiseFrame(headers: HeaderType, streamId: Int, promisedId: Int): DecoderResult



  ////////////////////////////////////////////////////////////////////////////

  override def inHeaderSequence(): Boolean = hBuilder != null

  final override def onHeadersFrame(streamId: Int,
                                  streamDep: Int,
                                  exclusive: Boolean,
                                  priority: Int,
                                  end_headers: Boolean,
                                  end_stream: Boolean,
                                  buffer: ByteBuffer): DecoderResult = {

    if (inHeaderSequence()) {
      return Error(PROTOCOL_ERROR("Received HEADERS frame while in in headers sequence"))
    }

    val builder = getHeaderBuilder()

    decodeBuffer(buffer, builder)

    if (end_headers) {
      headerDecoder.endHeaderBlock()
      val hs = builder.result()
      onCompleteHeadersFrame(hs, streamId, streamDep, exclusive, priority, end_stream)
    }
    else {
      hBuilder = builder
      hInfo = HeadersInfo(streamId, streamDep, exclusive, priority, end_stream, false, buffer)
      Success
    }
  }

  final override def onPushPromiseFrame(streamId: Int, promisedId: Int, end_headers: Boolean, buffer: ByteBuffer): DecoderResult = {

    if (inHeaderSequence()) {
      return Error(PROTOCOL_ERROR("Received HEADERS frame while in in headers sequence"))
    }

    val builder = getHeaderBuilder()

    decodeBuffer(buffer, builder)

    if (end_headers) {
      headerDecoder.endHeaderBlock()
      val hs = builder.result()
      onCompletePushPromiseFrame(hs, streamId, promisedId)
    }
    else {
      hBuilder = builder
      hInfo = HeadersInfo(streamId, promisedId, false, -1, false, true, buffer)
      Success
    }
  }

  final override def onContinuationFrame(streamId: Int, end_headers: Boolean, buffer: ByteBuffer): DecoderResult = {

    if (!inHeaderSequence() || hInfo.sId != streamId) {
      return Error(new PROTOCOL_ERROR(s"Invalid CONTINUATION frame: $streamId"))
    }

    val newBuffer = BufferTools.concatBuffers(hInfo.buffer, buffer)

    decodeBuffer(newBuffer, hBuilder)
    
    if (end_headers) {
      headerDecoder.endHeaderBlock()

      val hs = hBuilder.result()
      val info = hInfo
      hBuilder = null; hInfo = null;

      if (info.isPromise) onCompletePushPromiseFrame(hs, streamId, info.sDep)
      else onCompleteHeadersFrame(hs, streamId, info.sDep, info.ex, info.priority, info.end_stream)
    }
    else {
      hInfo.buffer = newBuffer
      Success
    }
  }

  private def decodeBuffer(buffer: ByteBuffer, builder: HeaderListener): Unit =
    headerDecoder.decode(new ByteBufferInputStream(buffer), builder)
}
