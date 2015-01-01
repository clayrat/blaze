package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

import org.http4s.blaze.util.BufferTools

/** This class is not 'thread safe' and should be treated accordingly */
abstract class HeaderDecodingFrameHandler extends FrameHandler {

  type HeaderType

  protected val headerDecoder: HeaderDecoder[HeaderType]

  private case class HeadersInfo(sId: Int,
                            priority: Option[Priority],
                          end_stream: Boolean,
                           isPromise: Boolean,
                          var buffer: ByteBuffer)

  private var hInfo: HeadersInfo = null


  ///////////////////////////////////////////////////////////////////////////
  
  def onCompleteHeadersFrame(headers: HeaderType, streamId: Int, priority: Option[Priority], end_stream: Boolean): Http2Result

  def onCompletePushPromiseFrame(headers: HeaderType, streamId: Int, promisedId: Int): Http2Result

  ////////////////////////////////////////////////////////////////////////////

  final def setMaxHeaderTableSize(maxSize: Int): Unit = { headerDecoder.setMaxTableSize(maxSize) }

  override def inHeaderSequence(): Boolean = hInfo != null

  final override def onHeadersFrame(streamId: Int,
                                    priority: Option[Priority],
                                 end_headers: Boolean,
                                  end_stream: Boolean,
                                      buffer: ByteBuffer): Http2Result = {

    if (inHeaderSequence()) {
      return Error(PROTOCOL_ERROR("Received HEADERS frame while in in headers sequence"))
    }

    if (end_headers) {
      headerDecoder.decode(buffer)
      val hs = headerDecoder.result()
      onCompleteHeadersFrame(hs, streamId, priority, end_stream)
    }
    else {
      hInfo = HeadersInfo(streamId, priority, end_stream, false, buffer)
      Continue
    }
  }

  final override def onPushPromiseFrame(streamId: Int, promisedId: Int, end_headers: Boolean, buffer: ByteBuffer): Http2Result = {

    if (inHeaderSequence()) {
      return Error(PROTOCOL_ERROR("Received HEADERS frame while in in headers sequence"))
    }

    if (end_headers) {
      headerDecoder.decode(buffer)
      val hs = headerDecoder.result()
      onCompletePushPromiseFrame(hs, streamId, promisedId)
    }
    else {
      hInfo = HeadersInfo(streamId, Some(Priority(promisedId, false, -1)), false, true, buffer)
      Continue
    }
  }

  final override def onContinuationFrame(streamId: Int, end_headers: Boolean, buffer: ByteBuffer): Http2Result = {

    if (!inHeaderSequence() || hInfo.sId != streamId) {
      return Error(PROTOCOL_ERROR(s"Invalid CONTINUATION frame", streamId))
    }

    val newBuffer = BufferTools.concatBuffers(hInfo.buffer, buffer)
    
    if (end_headers) {
      headerDecoder.decode(newBuffer)
      val hs = headerDecoder.result()

      val info = hInfo // make another reference and use it before we forget
      hInfo = null

      if (info.isPromise) onCompletePushPromiseFrame(hs, streamId, info.priority.get.dependentStreamId)
      else onCompleteHeadersFrame(hs, streamId, info.priority, info.end_stream)
    }
    else {
      hInfo.buffer = newBuffer
      Continue
    }
  }
}
