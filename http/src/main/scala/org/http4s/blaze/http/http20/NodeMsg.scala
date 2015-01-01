package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

object NodeMsg {

  sealed trait Http2Msg[+HType]

  case class DataFrame(isLast: Boolean, data: ByteBuffer) extends Http2Msg[Nothing]

  case class HeadersFrame[HType](priority: Option[Priority],
                               end_stream: Boolean,
                                  headers: HType) extends Http2Msg[HType]

//  case class PriorityFrame(streamDep: Int, exclusive: Boolean, priority: Int) extends Http2Msg

//  case class RstStreamFrame(code: Int) extends Http2Msg

//  case class SettingsFrame(ack: Boolean, settings: Seq[Setting]) extends Http2Msg[Nothing]

  case class PushPromiseFrame[HType](promisedId: Int, end_headers: Boolean, headers: HType) extends Http2Msg[HType]

//  case class PingFrame(data: Array[Byte], ack: Boolean) extends Http2Msg[Nothing]

//  case class GoAwayFrame(lastStream: Int, errorCode: Long, debugData: ByteBuffer) extends Http2Msg[Nothing]

//  case class WindowUpdateFrame(sizeIncrement: Int) extends Http2Msg

//  case class ContinuationFrame(endHeaders: Boolean, headers: HType) extends Http2Msg

  // For handling unknown stream frames
  case class ExtensionFrame(tpe: Int, flags: Byte, data: ByteBuffer) extends Http2Msg[Nothing]
}
