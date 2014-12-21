package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

import org.http4s.blaze.util.BufferTools

trait Http20FrameEncoder {

  def mkDataFrame(data: ByteBuffer, streamId: Int, isLast: Boolean, padding: Byte): Seq[ByteBuffer] = {

    require(streamId > 0, "bad DATA frame stream ID")
    require(padding >= 0 && padding < 255, "Invalid padding of DATA frame")

    val flags = (if (padding > 0) 0x8 else 0x0) | (if (isLast) 0x1 else 0x0)
    val paddingSize = if (padding > 0) 1 + padding else 0

    val headerBuffer = ByteBuffer.allocate(HeaderSize + (if (padding > 0) 1 else 0))

    writeFrameHeader(data.remaining() + paddingSize, FrameTypes.DATA, flags.toByte, streamId, headerBuffer)

    if (padding > 0) {
      headerBuffer.put(padding.toByte)
    }

    headerBuffer.flip()

    headerBuffer::data::paddedTail(padding)
  }

  def mkHeaderFrame(headerData: ByteBuffer,
                    streamId: Int,
                    dependentStreamId: Int,
                    exclusive: Boolean,
                    priority: Int,
                    end_headers: Boolean,
                    end_stream: Boolean,
                    padding: Int): Seq[ByteBuffer] = {

    require(streamId > 0, "bad HEADER frame stream ID")
    require(padding >= 0 && padding < 255, "Invalid padding of HEADER frame")
    require(priority <= 256, "Weight must be 1 to 256")

    var flags: Int = 0;
    var size1 = HeaderSize;

    if (padding > 0) {
      size1 += 1
      flags |= Flags.PADDED
    }

    if (priority >= 0) {
      size1 += 4 + 1      // stream dep and weight
      flags |= Flags.PRIORITY
    }

    if (end_headers) flags |= Flags.END_HEADERS
    if (end_stream)  flags |= Flags.END_STREAM

    val header = BufferTools.allocate(size1)
    writeFrameHeader(size1 - HeaderSize + headerData.remaining(), FrameTypes.HEADERS, flags.toByte, streamId, header)

    if (padding > 0) {
      header.put(padding.toByte)
    }

    if (priority >= 0) {
      require(dependentStreamId > 0)

      val i = dependentStreamId | (if (exclusive) Masks.exclsive else 0)
      header.putInt(i)

      header.put((priority-1).toByte)
    }

    header.flip()

    header::headerData::paddedTail(padding)
  }

  def mkPriorityFrame(streamId: Int, depId: Int, exclusive: Boolean, priority: Int): ByteBuffer = {

    require(priority > 0 && priority <= 256)

    val size = 5

    val buffer = BufferTools.allocate(HeaderSize + size)
    writeFrameHeader(size, FrameTypes.PRIORITY, 0, streamId, buffer)

    buffer.putInt(depId | (if (exclusive) Masks.exclsive else 0))
    buffer.put(((priority - 1) & 0xff).toByte)
    buffer.flip()

    buffer
  }

  def mkRstStreamFrame(streamId: Int, errorCode: Int): ByteBuffer = {
    require(streamId > 0, "Invalid RST_STREAM stream ID")

    val size = 4

    val buffer = BufferTools.allocate(HeaderSize + size)
    writeFrameHeader(size, FrameTypes.RST_STREAM, 0, streamId, buffer)
    buffer.putInt(errorCode)
    buffer.flip()

    buffer
  }

  def mkSettingsFrame(ack: Boolean, settings: Seq[Setting]): ByteBuffer = {
    require(!ack || settings.nonEmpty, "Setting acknowledgement must be empty")

    val size = settings.length * 6

    val buffer = BufferTools.allocate(HeaderSize + size)
    val flags = if (ack) Flags.ACK else 0

    writeFrameHeader(size, FrameTypes.SETTINGS, flags.toByte, 0, buffer)

    if (!ack) settings.foreach { case Setting(k,v) => buffer.putShort(k).putInt(v) }

    buffer.flip()
    buffer
  }

  def mkPushPromiseFrame(streamId: Int,
                         promiseId: Int,
                         end_headers: Boolean,
                         padding: Int,
                         headerBuffer: ByteBuffer): Seq[ByteBuffer] = {

    require(streamId != 0, "Invalid StreamID for PUSH_PROMISE frame")
    require(promiseId != 0 && promiseId % 2 == 0, "Invalid StreamID for PUSH_PROMISE frame")
    require(padding >= 0 && padding < 255, "Invalid padding of HEADER frame")

    var size = 4;
    var flags = 0;

    if (end_headers) flags |= Flags.END_HEADERS

    if (padding > 0) {
      flags |= Flags.PADDED
      size += 1
    }

    val buffer = BufferTools.allocate(HeaderSize + size)
    writeFrameHeader(size + headerBuffer.remaining(), FrameTypes.PUSH_PROMISE, flags.toByte, streamId, buffer)
    buffer.flip()

    buffer::headerBuffer::paddedTail(padding)
  }

  def mkPingFrame(data: Array[Byte], ack: Boolean): ByteBuffer = {
    val size = 8
    require(data.length == size, "Ping data must be 8 bytes long")

    val flags = if (ack) Flags.ACK else 0

    val buffer = ByteBuffer.allocate(HeaderSize + size)
    writeFrameHeader(size, FrameTypes.PING, flags.toByte, 0x0, buffer)
    buffer.put(data)
          .flip()

    buffer
  }

  def mkGoAwayFrame(lastStreamId: Int, error: Long, debugData: ByteBuffer): Seq[ByteBuffer] = {
    require(lastStreamId > 0, "Invalid last stream id for GOAWAY frame")
    val size = 8

    val buffer = BufferTools.allocate(HeaderSize + size)
    writeFrameHeader(size + debugData.remaining(), FrameTypes.GOAWAY, 0x0, 0x0, buffer)
    buffer.putInt(lastStreamId & Masks.int31)
          .putInt(error.toInt)
          .flip()

    buffer::debugData::Nil
  }

  def mkWindowUpdateFrame(streamId: Int, increment: Int): ByteBuffer = {
    require(streamId >= 0, "Invalid stream ID for WINDOW_UPDATE")
    require(increment > 0, "Invalid stream increment for WINDOW_UPDATE")

    val size = 4

    val buffer = BufferTools.allocate(HeaderSize + size)
    writeFrameHeader(size, FrameTypes.WINDOW_UPDATE, 0x0, streamId, buffer)
    buffer.putInt(Masks.int31 & increment)
          .flip()

    buffer
  }

  def mkContinuationFrame(streamId: Int, end_headers: Boolean, headerBuffer: ByteBuffer): Seq[ByteBuffer] = {
    require(streamId > 0, "Invalid stream ID for CONTINUATION frame")
    val flag = if (end_headers) Flags.END_HEADERS else 0x0

    val buffer = BufferTools.allocate(HeaderSize)
    writeFrameHeader(headerBuffer.remaining(), FrameTypes.CONTINUATION, flag.toByte, streamId, buffer)
    buffer.flip()

    buffer::headerBuffer::Nil
  }

  private def paddedTail(padding: Int): List[ByteBuffer] = {
    if (padding > 0) BufferTools.allocate(padding)::Nil
    else             Nil
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
