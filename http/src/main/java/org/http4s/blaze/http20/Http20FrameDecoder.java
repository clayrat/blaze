//package org.http4s.blaze.http20;
//
//import org.http4s.blaze.http.http20.FrameHandler;
//
//import java.nio.ByteBuffer;
//
//public class Http20FrameDecoder {
//
//    public Http20FrameDecoder(FrameHandler handler) {
//        this.handler = handler;
//    }
//
//    private static final int HeaderSize = 9;
//
//    private static class Masks {
//        static final public int STREAMID = 0x7fffffff;
//        static final public int LENGTH   =   0xffffff;
//        static final public int  int31   = 0x7fffffff;
//
//        static final public int exclsive = ~int31;
//    }
//
//    private static class Flags {
//        static boolean END_STREAM(byte flags)  { return checkFlag(flags, 0x01); }   // Data, Header
//        static boolean PADDED(byte flags)      { return checkFlag(flags, 0x08); }   // Data, Header
//
//        static boolean END_HEADERS(byte flags) { return checkFlag(flags, 0x04); }  // Header, push_promise
//        static boolean PRIORITY(byte flags)    { return checkFlag(flags, 0x20); }  // Header
//
//        static boolean ACK(byte flags)         { return checkFlag(flags, 0x01); }   // ping
//
//        static int DepID(final int id)  { return id & Masks.int31; }
//        static boolean DepExclusive(final int id) { return (Masks.exclsive & id) != 0; }
//    }
//
//    private static boolean checkFlag(byte flags, int flag) { return (flags & flag) != 0; }
//
//    private final FrameHandler handler;
//
//        /** Decode a data frame. false signals "not enough data" */
//    public boolean decodeBuffer(ByteBuffer buffer) {
//        if (buffer.remaining() < HeaderSize) {
//            return false;
//        }
//
//        buffer.mark();
//        final int len = buffer.get() << 16 | buffer.get() << 8 | buffer.get();
//
//        if (len + 6 > buffer.remaining()) {   // We still don't have a full frame
//            buffer.reset();
//            return false;
//        }
//
//        // we have a full frame, get the header information
//        final byte frameType = buffer.get();
//        final byte flags = buffer.get();
//        final int streamId = buffer.getInt() & Masks.STREAMID; // this concludes the 9 byte header. `in` is now to the payload
//
//        // set frame sizes in the ByteBuffer and decode
//        final int oldLimit = buffer.limit();
//        final int endOfFrame = buffer.position() + len;
//        buffer.limit(endOfFrame);
//
//        boolean r;
//        switch (frameType) {
//            case 0x00: r = decodeDataFrame(buffer, streamId, flags); break;
//            case 0x01: r = decodeHeaderFrame(buffer, streamId, flags); break;
//            case 0x02: r = decodePriorityFrame(buffer, streamId, flags); break;
//            case 0x03: r = decodeRstStreamFrame(buffer, streamId); break;
//            case 0x04: r = decodeSettingsFrame(buffer, streamId, flags); break;
//            case 0x05: r = decodePushPromiseFrame(buffer, streamId, flags); break;
//            case 0x06: r = decodePingFrame(buffer, streamId, flags); break;
//            case 0x07: r = decodeGoAwayFrame(buffer, streamId); break;
//            case 0x08: r = decodeWindowUpdateFrame(buffer, streamId); break;
//            default: r = handler.onUnknownFrame(frameType, streamId, flags, buffer.slice()); break;
//        }
//
//        // reset buffer limits
//        buffer.limit(oldLimit);
//        buffer.position(endOfFrame);
//
//        return r;
//    }
//
//        //////////////// Decoding algorithms ///////////////////////////////////////////////////////////
//
//    boolean decodeDataFrame(final ByteBuffer buffer, final int streamId, final byte flags) {
//
//        if (streamId == 0) {
//            handler.onError(new FrameHandler.PROTOCOL_ERROR("Data frame with streamID 0x0"));
//            return false;
//        }
//
//        if (Flags.PADDED(flags)) limitPadding(buffer);
//
//        final ByteBuffer data = buffer.slice();
//
//        return handler.onDataFrame(data, streamId, Flags.END_STREAM(flags));
//    }
//
//    final boolean decodeHeaderFrame(final ByteBuffer buffer, final int streamId, final byte flags) {
//
//        if (streamId == 0) {
//            handler.onError(new FrameHandler.PROTOCOL_ERROR("Headers frame with streamID 0x0"));
//            return false;
//        }
//
//        if (Flags.PADDED(flags)) {
//            limitPadding(buffer);
//        }
//
//        final int dInt = Flags.PRIORITY(flags)? buffer.getInt() : 0;
//        final int priority = Flags.PRIORITY(flags) ? buffer.get() & 0xff : 16;
//
//        return handler.onHeadersFrame(buffer.slice(),
//                streamId,
//                Flags.DepID(dInt),
//                Flags.DepExclusive(dInt),
//                priority,
//                Flags.END_HEADERS(flags),
//                Flags.END_STREAM(flags));
//    }
//
//    boolean decodePriorityFrame(final ByteBuffer buffer, final int streamId, final byte flags) {
//
//        if (streamId == 0) {
//            handler.onError(new FrameHandler.PROTOCOL_ERROR("Priority frame with streamID 0x0"));
//            return false;
//        }
//
//        if (buffer.remaining() != 5) {    // Make sure the frame has the right amount of data
//            handler.onError(new FrameHandler.FRAME_SIZE_ERROR("Invalid PRIORITY frame size", 5, buffer.remaining()));
//            return false;
//        }
//
//        final int r = buffer.getInt();
//        final int streamDep = Flags.DepID(r);
//        final boolean exclusive = Flags.DepExclusive(r);
//
//        if (streamDep == 0) {
//            FrameHandler.Http2Exception err = new FrameHandler.PROTOCOL_ERROR("Priority frame with stream dependency 0x0");
//            handler.onError(err);
//            return false;
//        }
//        else {
//            return handler.onPriorityFrame(streamId, streamDep, exclusive);
//        }
//    }
//
//    boolean decodeRstStreamFrame(final ByteBuffer buffer, final int streamId) {
//        if (buffer.remaining() != 4) {
//            handler.onError(new FrameHandler.FRAME_SIZE_ERROR("Invalid RST_STREAM frame size", 4, buffer.remaining()));
//            return false;
//        }
//
//        if (streamId == 0) {
//            FrameHandler.Http2Exception err = new FrameHandler.PROTOCOL_ERROR("RST_STREAM frame with stream ID 0");
//            handler.onError(err);
//            return false;
//        }
//
//        final int code = buffer.getInt();
//
//        return handler.onRstStreamFrame(streamId, code);
//    }
//
//    boolean decodeSettingsFrame(final ByteBuffer buffer, final int streamId, final byte flags) {
//        final int len = buffer.remaining();
//        final int settingsCount = len / 6; // 6 bytes per setting
//
//        if (len - settingsCount != 0) { // Invalid frame size
//            handler.onError(new FrameHandler.PROTOCOL_ERROR("Detected corrupted SETTINGS frame size"));
//            return false;
//        }
//
//        for (int i = 0; i < settingsCount; i++) {
//            final int id = buffer.getShort() & 0xffff;
//            final long value = buffer.getInt() & 0xffffffffl;
//
//            if (!handler.handleSetting(id, value)) {
//                return false;
//            }
//        }
//
//        return true;
//    }
//
//    boolean decodePushPromiseFrame(final ByteBuffer buffer, final int streamId, final byte flags) {
//
//        if (streamId == 0) {
//            handler.onError(new FrameHandler.PROTOCOL_ERROR("Data frame with streamID 0x0"));
//            return false;
//        }
//
//        if (Flags.PADDED(flags)) limitPadding(buffer);
//
//        final int promisedId = buffer.getInt() & Masks.int31;
//
//        return handler.onPushPromiseFrame(streamId, promisedId, Flags.END_HEADERS(flags), buffer.slice());
//    }
//
//    boolean decodePingFrame(final ByteBuffer buffer, final int streamId, final byte flags) {
//        final int pingSize = 8;
//
//        if (streamId != 0) {
//            handler.onError(new FrameHandler.PROTOCOL_ERROR("PING frame with streamID != 0x0"));
//            return false;
//        }
//
//        if (buffer.remaining() != pingSize) {
//            handler.onError(new FrameHandler.FRAME_SIZE_ERROR("Invalid PING frame size", 4, buffer.remaining()));
//            return false;
//        }
//
//        byte[] pingBytes = new byte[pingSize];
//                buffer.get(pingBytes);
//
//        return handler.onPingFrame(pingBytes, Flags.ACK(flags));
//    }
//
//    boolean decodeGoAwayFrame(final ByteBuffer buffer, final int streamId) {
//
//        if (buffer.remaining() < 8) {
//            handler.onError(new FrameHandler.FRAME_SIZE_ERROR("GOAWAY frame is wrong size", 8, buffer.remaining()));
//            return false;
//        }
//
//        if (streamId != 0) {
//            handler.onError(new FrameHandler.PROTOCOL_ERROR("GOAWAY frame with streamID != 0x0"));
//            return false;
//        }
//
//        final int lastStream = Flags.DepID(buffer.getInt());
//        final long code = buffer.getInt() & 0xffffffffl;
//
//        return handler.onGoAwayFrame(lastStream, code, buffer.slice());
//    }
//
//    boolean decodeWindowUpdateFrame(final ByteBuffer buffer, final int streamId) {
//        if (buffer.remaining() != 4) {
//            handler.onError(new FrameHandler.FRAME_SIZE_ERROR("WINDOW_UPDATE frame frame is wrong size", 8, buffer.remaining()));
//            return false;
//        }
//
//        final int size = buffer.getInt() & Masks.int31;
//
//        if (size == 0) {
//            handler.onError(new FrameHandler.PROTOCOL_ERROR("Invalid WINDOW_UPDATE size of 0x0"));
//            return false;
//        }
//
//        return handler.onWindowUpdateFrame(streamId, size);
//    }
//
//    boolean decodeContinuationFrame(final ByteBuffer buffer, final int streamId, final byte flags) {
//
//        if (streamId == 0) {
//            handler.onError(new FrameHandler.PROTOCOL_ERROR("Priority frame with stream dependency 0x0"));
//            return false;
//        }
//
//        return handler.onContinuationFrame(streamId, Flags.END_HEADERS(flags), buffer.slice());
//    }
//
//
//    private ByteBuffer limitPadding(final ByteBuffer buffer) {
//        final int padding = buffer.get() & 0xff;
//        if (padding > 0) {
//            buffer.limit(buffer.limit() - padding);
//        }
//        return buffer;
//    }
//}
