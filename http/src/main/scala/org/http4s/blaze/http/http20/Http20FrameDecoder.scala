package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

import scala.collection.mutable.ListBuffer


/* The job of the Http20FrameCodec is to slice the ByteBuffers. It does
   not attempt to decode headers or perform any size limiting operations */
trait Http20FrameDecoder {

  def handler: FrameHandler

  /** Decode a data frame. false signals "not enough data" */

  def decodeBuffer(buffer: ByteBuffer): DecoderResult = {
    if (buffer.remaining() < HeaderSize) {
      return BufferUnderflow
    }

    buffer.mark()
    val len = buffer.get() << 16 | buffer.get() << 8 | buffer.get()

    if (len + 6 > buffer.remaining()) {   // We still don't have a full frame
      buffer.reset()
      return BufferUnderflow
    }

    // we have a full frame, get the header information
    val frameType = buffer.get()
    val flags = buffer.get()
    val streamId = buffer.getInt() & Masks.STREAMID // this concludes the 9 byte header. `in` is now to the payload
    
    // Make sure we are not in the middle of some header frames
    if (handler.inHeaderSequence() && frameType != FrameTypes.CONTINUATION) {
      return Error(PROTOCOL_ERROR(s"Received frame type $frameType while in in headers sequence"))
    }

    // set frame sizes in the ByteBuffer and decode
    val oldLimit = buffer.limit()
    val endOfFrame = buffer.position() + len
    buffer.limit(endOfFrame)

    val r = frameType match {
      case FrameTypes.DATA          => decodeDataFrame(buffer, streamId, flags)
      case FrameTypes.HEADERS       => decodeHeaderFrame(buffer, streamId, flags)
      case FrameTypes.PRIORITY      => decodePriorityFrame(buffer, streamId, flags)
      case FrameTypes.RST_STREAM    => decodeRstStreamFrame(buffer, streamId)
      case FrameTypes.SETTINGS      => decodeSettingsFrame(buffer, streamId, flags)
      case FrameTypes.PUSH_PROMISE  => decodePushPromiseFrame(buffer, streamId, flags)
      case FrameTypes.PING          => decodePingFrame(buffer, streamId, flags)
      case FrameTypes.GOAWAY        => decodeGoAwayFrame(buffer, streamId)
      case FrameTypes.WINDOW_UPDATE => decodeWindowUpdateFrame(buffer, streamId)
        
        // this concludes the types established by HTTP/2.0, but it could be an extension
      case code                     => onExtensionFrame(code, streamId, flags, buffer.slice())
    }

    // reset buffer limits
    buffer.limit(oldLimit)
    buffer.position(endOfFrame)

    return r
  }
  
  /** Overriding this method allows for easily supporting extension frames */
  def onExtensionFrame(code: Int, streamId: Int, flags: Byte, buffer: ByteBuffer): DecoderResult =
    handler.onExtensionFrame(code, streamId, flags, buffer)

  //////////////// Decoding algorithms ///////////////////////////////////////////////////////////

  //////////// DATA ///////////////
  private def decodeDataFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): DecoderResult = {

    if (streamId == 0) {
      return Error(PROTOCOL_ERROR("Data frame with streamID 0x0"))
    }

    if (Flags.PADDED(flags)) limitPadding(buffer)

    handler.onDataFrame(streamId, Flags.END_STREAM(flags), buffer.slice())
  }

  //////////// HEADERS ///////////////
  private def decodeHeaderFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): DecoderResult = {

    if (streamId == 0) {
      return Error(PROTOCOL_ERROR("Headers frame with streamID 0x0"))
    }

    if (Flags.PADDED(flags)) limitPadding(buffer)

    val isPriority = Flags.PRIORITY(flags)

    val dInt = if (isPriority) buffer.getInt() else 0
    val priority = if (isPriority) (buffer.get() & 0xff) + 1 else 16

    handler.onHeadersFrame(streamId, Flags.DepID(dInt), Flags.DepExclusive(dInt),
      priority, Flags.END_HEADERS(flags), Flags.END_STREAM(flags), buffer.slice())
  }

  //////////// PRIORITY ///////////////
  private def decodePriorityFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): DecoderResult = {

    if (streamId == 0) {
      return Error(PROTOCOL_ERROR("Priority frame with streamID 0x0"))
    }

    if (buffer.remaining() != 5) {    // Make sure the frame has the right amount of data
      return Error(FRAME_SIZE_ERROR("Invalid PRIORITY frame size", 5, buffer.remaining()))
    }

    val r = buffer.getInt()
    val streamDep = Flags.DepID(r)
    val exclusive = Flags.DepExclusive(r)

    val priority = (buffer.get() & 0xff) + 1

    if (streamDep == 0) {
      return Error(PROTOCOL_ERROR("Priority frame with stream dependency 0x0"))
    }
    else handler.onPriorityFrame(streamId, streamDep, exclusive, priority)
  }

  //////////// RST_STREAM ///////////////
  private def decodeRstStreamFrame(buffer: ByteBuffer, streamId: Int): DecoderResult = {
    if (buffer.remaining() != 4) {
      return Error(FRAME_SIZE_ERROR("Invalid RST_STREAM frame size", 4, buffer.remaining()))
    }

    if (streamId == 0) {
      return Error(PROTOCOL_ERROR("RST_STREAM frame with stream ID 0"))
    }

    val code = buffer.getInt()

    handler.onRstStreamFrame(streamId, code)
  }

  //////////// SETTINGS ///////////////
  private def decodeSettingsFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): DecoderResult = {
    val len = buffer.remaining()
    val settingsCount = len / 6 // 6 bytes per setting

    val isAck = Flags.ACK(flags)

    if (len % 6 != 0) { // Invalid frame size
      return Error(FRAME_SIZE_ERROR("SETTINGS frame payload must be multiple of 6 bytes", 6, len))
    }

    if (isAck && settingsCount != 0) {
      return Error(FRAME_SIZE_ERROR("SETTINGS ACK frame with settings payload", 0, len))
    }

    if (streamId != 0x0) {
      return Error(PROTOCOL_ERROR(s"SETTINGS frame with invalid stream id: $streamId"))
    }

    val settings = new ListBuffer[Setting]
    def go(remaining: Int): Unit = if (remaining > 0) {
      val id: Int = buffer.getShort() & 0xffff
      val value: Long = buffer.getInt() & 0xffffffffl
      settings += Setting(id, value)
      go(remaining - 1)
    }
    go(settingsCount)

    handler.onSettingsFrame(isAck, settings.result)
  }

  //////////// PUSH_PROMISE ///////////////
  private def decodePushPromiseFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): DecoderResult = {

    if (streamId == 0) {
      return Error(PROTOCOL_ERROR("Data frame with streamID 0x0"))
    }

    if (Flags.PADDED(flags)) limitPadding(buffer)

    val promisedId = buffer.getInt() & Masks.int31

    handler.onPushPromiseFrame(streamId, promisedId, Flags.END_HEADERS(flags), buffer.slice())
  }

  //////////// PING ///////////////
  private def decodePingFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): DecoderResult = {
    val pingSize = 8

    if (streamId != 0) {
      return Error(PROTOCOL_ERROR("PING frame with streamID != 0x0"))
    }

    if (buffer.remaining() != pingSize) {
      return Error(FRAME_SIZE_ERROR("Invalid PING frame size", 4, buffer.remaining()))
    }

    val pingBytes = new Array[Byte](pingSize)
    buffer.get(pingBytes)

    handler.onPingFrame(pingBytes, Flags.ACK(flags))
  }

  //////////// GOAWAY ///////////////
  private def decodeGoAwayFrame(buffer: ByteBuffer, streamId: Int): DecoderResult = {

    if (buffer.remaining() < 8) {
      return Error(FRAME_SIZE_ERROR("GOAWAY frame is wrong size", 8, buffer.remaining()))
    }

    if (streamId != 0) {
      return Error(PROTOCOL_ERROR("GOAWAY frame with streamID != 0x0"))
    }

    val lastStream = Flags.DepID(buffer.getInt)
    val code: Long = buffer.getInt() & 0xffffffffl

    handler.onGoAwayFrame(lastStream, code, buffer.slice())
  }

  //////////// WINDOW_UPDATE ///////////////
  private def decodeWindowUpdateFrame(buffer: ByteBuffer, streamId: Int): DecoderResult = {
    if (buffer.remaining() != 4) {
      return Error(FRAME_SIZE_ERROR("WINDOW_UPDATE frame frame is wrong size", 8, buffer.remaining()))
    }

    val size = buffer.getInt() & Masks.int31

    if (size == 0) {
      return Error(PROTOCOL_ERROR("Invalid WINDOW_UPDATE size of 0x0"))
    }

    handler.onWindowUpdateFrame(streamId, size)
  }

  //////////// CONTINUATION ///////////////
  private def decodeContinuationFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): DecoderResult = {

    if (streamId == 0) {
      return Error(PROTOCOL_ERROR("CONTINUATION frame with stream dependency 0x0"))
    }

    handler.onContinuationFrame(streamId, Flags.END_HEADERS(flags), buffer.slice())
  }


  @inline
  private def limitPadding(buffer: ByteBuffer): ByteBuffer = {
    val padding = buffer.get() & 0xff
    if (padding > 0) buffer.limit(buffer.limit() - padding)
    buffer
  }
}
