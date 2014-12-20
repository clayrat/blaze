//package org.http4s.blaze.http20;
//
//public class Http20FrameEncoder {
//
//
//    //////////////// Encoding algorithms ///////////////////////////////////////////////////////////
//
//    def mkDataFrame(data: ByteBuffer, streamId: Int, isLast: Boolean, padding: Byte): Seq[ByteBuffer] = {
//        val flags = (if (padding > 0) 0x8 else 0x0) | (if (isLast) 0x1 else 0x0)
//
//        val headerBuffer = ByteBuffer.allocate(HeaderSize + (if (padding > 0) 1 + padding else 0))
//
//        writeFrameHeader(data.remaining(), FrameTypes.DATA, flags.toByte, streamId, headerBuffer)
//
//        if (padding > 0) {
//            headerBuffer.put(padding.toByte)
//        }
//
//        headerBuffer.flip()
//
//        if (padding > 0) headerBuffer::data::BufferTools.allocate(padding)::Nil
//        else             headerBuffer::data::Nil
//    }
//
//
//    private def writeFrameHeader(length: Int, frameType: Byte, flags: Byte, streamdId: Int, buffer: ByteBuffer): Unit = {
//        buffer.put((length >>> 16 & 0xff).toByte)
//                .put((length >>> 8  & 0xff).toByte)
//                .put((length        & 0xff).toByte)
//                .put(frameType)
//                .put(flags)
//                .putInt(streamdId & Masks.STREAMID)
//    }
//
//}
