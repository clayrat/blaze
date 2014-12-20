package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

import org.http4s.blaze.http.http20.FrameHandler._
import org.http4s.blaze.util.BufferTools


/* The job of the Http20FrameCodec is to slice the ByteBuffers. It does
   not attempt to decode headers or perform any size limiting operations */
final class Http20FrameCodec(handler: FrameHandler) {

  /** Decode a data frame. false signals "not enough data" */
  def decodeBuffer(buffer: ByteBuffer): Boolean = {
    if (buffer.remaining() < HeaderSize) {
      return false
    }

    buffer.mark()
    val len = buffer.get() << 16 | buffer.get() << 8 | buffer.get()

    if (len + 6 > buffer.remaining()) {   // We still don't have a full frame
      buffer.reset()
      return false
    }

    // we have a full frame, get the header information
    val frameType = buffer.get()
    val flags = buffer.get()
    val streamId = buffer.getInt() & Masks.STREAMID // this concludes the 9 byte header. `in` is now to the payload

    // set frame sizes in the ByteBuffer and decode
    val oldLimit = buffer.limit()
    val endOfFrame = buffer.position() + len
    buffer.limit(endOfFrame)

    val r = frameType match {
      case FrameTypes.DATA          => decodeDataFrame(buffer, streamId, flags)
      case FrameTypes.HEADERS       => decodeHeaderFrame(buffer, streamId, flags)
      case FrameTypes.PRIORITY      => decodePriorityFrame(buffer, streamId, flags)
      case FrameTypes.RST_STREAM    => decodeRstStreamFrame(buffer, streamId)
      case FrameTypes.PUSH_PROMISE  => decodePushPromiseFrame(buffer, streamId, flags)
      case FrameTypes.PING          => decodePingFrame(buffer, streamId, flags)
      case FrameTypes.GOAWAY        => decodeGoAwayFrame(buffer, streamId)
      case FrameTypes.WINDOW_UPDATE => decodeWindowUpdateFrame(buffer, streamId)
      case unknown                  => handler.onUnknownFrame(unknown, streamId, flags, buffer.slice())
    }

    // reset buffer limits
    buffer.limit(oldLimit)
    buffer.position(endOfFrame)

    return r
  }

  //////////////// Decoding algorithms ///////////////////////////////////////////////////////////

  //////////// DATA ///////////////
  final def decodeDataFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Boolean = {

    if (streamId == 0) {
      handler.onError(new PROTOCOL_ERROR("Data frame with streamID 0x0"))
      return false
    }

    if (Flags.PADDED(flags)) limitPadding(buffer)

    val data = buffer.slice()

    handler.onDataFrame(data, streamId, Flags.END_STREAM(flags))
  }

  //////////// HEADERS ///////////////
  final def decodeHeaderFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Boolean = {

    if (streamId == 0) {
      handler.onError(new PROTOCOL_ERROR("Headers frame with streamID 0x0"))
      return false
    }

    if (Flags.PADDED(flags)) limitPadding(buffer)

    val dInt = if (Flags.PRIORITY(flags)) buffer.getInt() else 0
    val priority = if (Flags.PRIORITY(flags)) buffer.get() & 0xff else 16

    handler.onHeadersFrame(buffer.slice(), streamId, Flags.DepID(dInt), Flags.DepExclusive(dInt),
      priority, Flags.END_HEADERS(flags), Flags.END_STREAM(flags))
  }

  //////////// PRIORITY ///////////////
  def decodePriorityFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Boolean = {

    if (streamId == 0) {
      handler.onError(new PROTOCOL_ERROR("Priority frame with streamID 0x0"))
      return false
    }

    if (buffer.remaining() != 5) {    // Make sure the frame has the right amount of data
      handler.onError(new FRAME_SIZE_ERROR("Invalid PRIORITY frame size", 5, buffer.remaining()))
      return false
    }

    val r = buffer.getInt()
    val streamDep = Flags.DepID(r)
    val exclusive = Flags.DepExclusive(r)

    if (streamDep == 0) {
      val err = new PROTOCOL_ERROR("Priority frame with stream dependency 0x0")
      handler.onError(err)
      false
    }
    else handler.onPriorityFrame(streamId, streamDep, exclusive)
  }

  //////////// RST_STREAM ///////////////
  def decodeRstStreamFrame(buffer: ByteBuffer, streamId: Int): Boolean = {
    if (buffer.remaining() != 4) {
      handler.onError(new FRAME_SIZE_ERROR("Invalid RST_STREAM frame size", 4, buffer.remaining()))
      return false
    }

    if (streamId == 0) {
      val err = new PROTOCOL_ERROR("RST_STREAM frame with stream ID 0")
      handler.onError(err)
      return false
    }

    val code = buffer.getInt()

    handler.onRstStreamFrame(streamId, code)
  }

  //////////// SETTINGS ///////////////
  def decodeSettingsFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Boolean = {
    val len = buffer.remaining()
    val settingsCount = len / 6 // 6 bytes per setting

    if (len - settingsCount != 0) { // Invalid frame size
      handler.onError(new PROTOCOL_ERROR("Detected corrupted SETTINGS frame size"))
      return false
    }

    def go(remaining: Int): Boolean = {
      if (remaining > 0) {
        val id: Int = buffer.getShort() & 0xffff
        val value: Long = buffer.getInt() & 0xffffffffl
        if (handler.handleSetting(id, value)) go(remaining - 1)
        else false
      }
      else true
    }

    go(settingsCount)
  }

  //////////// PUSH_PROMISE ///////////////
  def decodePushPromiseFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Boolean = {

    if (streamId == 0) {
      handler.onError(new PROTOCOL_ERROR("Data frame with streamID 0x0"))
      return false
    }

    if (Flags.PADDED(flags)) limitPadding(buffer)

    val promisedId = buffer.getInt() & Masks.int31

    handler.onPushPromiseFrame(streamId, promisedId, Flags.END_HEADERS(flags), buffer.slice())
  }

  //////////// PING ///////////////
  def decodePingFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Boolean = {
    val pingSize = 8

    if (streamId != 0) {
      handler.onError(new PROTOCOL_ERROR("PING frame with streamID != 0x0"))
      return false
    }

    if (buffer.remaining() != pingSize) {
      handler.onError(new FRAME_SIZE_ERROR("Invalid PING frame size", 4, buffer.remaining()))
      return false
    }

    val pingBytes = new Array[Byte](pingSize)
    buffer.get(pingBytes)

    handler.onPingFrame(pingBytes, Flags.ACK(flags))
  }

  //////////// GOAWAY ///////////////
  def decodeGoAwayFrame(buffer: ByteBuffer, streamId: Int): Boolean = {

    if (buffer.remaining() < 8) {
      handler.onError(new FRAME_SIZE_ERROR("GOAWAY frame is wrong size", 8, buffer.remaining()))
      return false
    }

    if (streamId != 0) {
      handler.onError(new PROTOCOL_ERROR("GOAWAY frame with streamID != 0x0"))
      return false
    }

    val lastStream = Flags.DepID(buffer.getInt)
    val code: Long = buffer.getInt() & 0xffffffffl

    handler.onGoAwayFrame(lastStream, code, buffer.slice())
  }

  //////////// WINDOW_UPDATE ///////////////
  def decodeWindowUpdateFrame(buffer: ByteBuffer, streamId: Int): Boolean = {
    if (buffer.remaining() != 4) {
      handler.onError(new FRAME_SIZE_ERROR("WINDOW_UPDATE frame frame is wrong size", 8, buffer.remaining()))
      return false
    }

    val size = buffer.getInt() & Masks.int31

    if (size == 0) {
      handler.onError(new PROTOCOL_ERROR("Invalid WINDOW_UPDATE size of 0x0"))
      return false
    }

    handler.onWindowUpdateFrame(streamId, size)
  }

  //////////// CONTINUATION ///////////////
  def decodeContinuationFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Boolean = {

    if (streamId == 0) {
      val err = new PROTOCOL_ERROR("Priority frame with stream dependency 0x0")
      handler.onError(err)
      return false
    }

    handler.onContinuationFrame(streamId, Flags.END_HEADERS(flags), buffer.slice())
  }


  @inline
  private def limitPadding(buffer: ByteBuffer): ByteBuffer = {
    val padding = buffer.get() & 0xff
    if (padding > 0) buffer.limit(buffer.limit() - padding)
    buffer
  }

  //////////////// Encoding algorithms ///////////////////////////////////////////////////////////

  def mkDataFrame(data: ByteBuffer, streamId: Int, isLast: Boolean, padding: Byte): Seq[ByteBuffer] = {
    val flags = (if (padding > 0) 0x8 else 0x0) | (if (isLast) 0x1 else 0x0)

    val headerBuffer = ByteBuffer.allocate(HeaderSize + (if (padding > 0) 1 + padding else 0))

    writeFrameHeader(data.remaining(), FrameTypes.DATA, flags.toByte, streamId, headerBuffer)

    if (padding > 0) {
      headerBuffer.put(padding.toByte)
    }

    headerBuffer.flip()

    if (padding > 0) headerBuffer::data::BufferTools.allocate(padding)::Nil
    else             headerBuffer::data::Nil
  }

  def mkHeaderFrame(headerData: ByteBuffer,   // TODO: why would we want the ByteBuffer data? What about headers?
                      streamId: Int,
             dependentStreamId: Int,
                     exclusive: Int,
                      priority: Int,
                   end_headers: Boolean,
                    end_stream: Boolean,
                       padding: Int): Seq[ByteBuffer] = {

    require(padding <= 256, "padding must be 256 bytes or less")
    require(priority <= 256, "Weight must be 1 to 256")

    val size = HeaderSize + (if (padding > 0) 1 + padding else 0) +
                            (if (dependentStreamId > 0) 4 else 0) +
                            (if (priority > 0) 1 else 0)

    val buffer = ???
    ???
  }


  private def writeFrameHeader(length: Int, frameType: Byte, flags: Byte, streamdId: Int, buffer: ByteBuffer): Unit = {
    buffer.put((length >>> 16 & 0xff).toByte)
          .put((length >>> 8  & 0xff).toByte)
          .put((length        & 0xff).toByte)
          .put(frameType)
          .put(flags)
          .putInt(streamdId & Masks.STREAMID)
  }


}
