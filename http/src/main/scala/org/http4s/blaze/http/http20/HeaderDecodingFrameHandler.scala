package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

import org.http4s.blaze.util.BufferTools

/** This class is not 'thread safe' and should be treated accordingly */
abstract class HeaderDecodingFrameHandler extends FrameHandler {

  type HeaderType

  protected val headerDecoder: HeaderDecoder[HeaderType]

  private case class HeadersInfo(sId: Int, sDep: Int, ex: Boolean, priority: Int, end_stream: Boolean, isPromise: Boolean, var buffer: ByteBuffer)

  private var hInfo: HeadersInfo = null


  ///////////////////////////////////////////////////////////////////////////
  
  def onCompleteHeadersFrame(headers: HeaderType, streamId: Int, streamDep: Int, exclusive: Boolean, priority: Int, end_stream: Boolean): DecoderResult

  def onCompletePushPromiseFrame(headers: HeaderType, streamId: Int, promisedId: Int): DecoderResult

  ////////////////////////////////////////////////////////////////////////////

  final def setMaxHeaderTableSize(maxSize: Int): Unit = { headerDecoder.setMaxTableSize(maxSize) }

  override def inHeaderSequence(): Boolean = !headerDecoder.empty()

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

    headerDecoder.decode(buffer)

    if (end_headers) {
      val hs = headerDecoder.result()
      onCompleteHeadersFrame(hs, streamId, streamDep, exclusive, priority, end_stream)
    }
    else {
      hInfo = HeadersInfo(streamId, streamDep, exclusive, priority, end_stream, false, buffer)
      Continue
    }
  }

  final override def onPushPromiseFrame(streamId: Int, promisedId: Int, end_headers: Boolean, buffer: ByteBuffer): DecoderResult = {

    if (inHeaderSequence()) {
      return Error(PROTOCOL_ERROR("Received HEADERS frame while in in headers sequence"))
    }

    headerDecoder.decode(buffer)

    if (end_headers) {
      val hs = headerDecoder.result()
      onCompletePushPromiseFrame(hs, streamId, promisedId)
    }
    else {
      hInfo = HeadersInfo(streamId, promisedId, false, -1, false, true, buffer)
      Continue
    }
  }

  final override def onContinuationFrame(streamId: Int, end_headers: Boolean, buffer: ByteBuffer): DecoderResult = {

    if (!inHeaderSequence() || hInfo.sId != streamId) {
      return Error(new PROTOCOL_ERROR(s"Invalid CONTINUATION frame: $streamId"))
    }

    val newBuffer = BufferTools.concatBuffers(hInfo.buffer, buffer)

    headerDecoder.decode(newBuffer)
    
    if (end_headers) {
      val hs = headerDecoder.result()
      val info = hInfo
      hInfo = null;

      if (info.isPromise) onCompletePushPromiseFrame(hs, streamId, info.sDep)
      else onCompleteHeadersFrame(hs, streamId, info.sDep, info.ex, info.priority, info.end_stream)
    }
    else {
      hInfo.buffer = newBuffer
      Continue
    }
  }
}
