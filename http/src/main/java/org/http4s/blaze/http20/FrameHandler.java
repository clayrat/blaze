//package org.http4s.blaze.http20;
//
//import java.nio.ByteBuffer;
//
//public interface FrameHandler {
//
//    boolean onDataFrame(ByteBuffer data, int streamId, boolean isLast);
//
//    boolean onHeadersFrame(ByteBuffer data,
//                           int streamId,
//                           int streamDep,
//                           boolean exclusive,
//                       int priority,
//                       boolean endHeaders,
//                       boolean endStream);
//
//    Boolean onPriorityFrame(int streamId, int streamDep, boolean exclusive);
//
//    Boolean onRstStreamFrame(int streamId, int code);
//
//    // Each setting is handled one at a time. false signals the decoding process to halt
//    Boolean handleSetting(int id, long value);
//
//    Boolean onPushPromiseFrame(int streamId, int promisedId, boolean endHeaders, ByteBuffer data);
//
//    Boolean onPingFrame(byte []data, boolean ack);
//
//    Boolean onGoAwayFrame(int lastStream, long errorCode, ByteBuffer debugData);
//
//    Boolean onWindowUpdateFrame(int streamId, int sizeIncrement);
//
//    Boolean onContinuationFrame(ByteBuffer data, int streamId, boolean endHeaders);
//
//    // For handling unknown stream frames
//    Boolean onUnknownFrame(int tpe, int streamId, byte flags, ByteBuffer data);
//
//}
