package org.http4s.blaze.http

import java.nio.ByteBuffer

import java.nio.charset.StandardCharsets.US_ASCII

import scala.collection.mutable
import scala.util.control.NoStackTrace

package object http20 {
  
  val HeaderSize = 9
  def clientTLSHandshakeString = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"

  private[http20] object Masks {
    val STREAMID = 0x7fffffff
    val LENGTH   =   0xffffff
    val int31    = 0x7fffffff

    val exclsive = ~int31
  }

  object FrameTypes {
    val DATA          = 0x0.toByte
    val HEADERS       = 0x1.toByte
    val PRIORITY      = 0x2.toByte
    val RST_STREAM    = 0x3.toByte
    val SETTINGS      = 0x4.toByte
    val PUSH_PROMISE  = 0x5.toByte
    val PING          = 0x6.toByte
    val GOAWAY        = 0x7.toByte
    val WINDOW_UPDATE = 0x8.toByte
    val CONTINUATION  = 0x9.toByte
  }

  type SettingKey = Int
  type SettingValue = Long
  case class Setting(key: SettingKey, value: SettingValue)

  object SettingsKeys {
    val SETTINGS_HEADER_TABLE_SIZE =      0x1
    val SETTINGS_ENABLE_PUSH =            0x2
    val SETTINGS_MAX_CONCURRENT_STREAMS = 0x3
    val SETTINGS_INITIAL_WINDOW_SIZE =    0x4
    val SETTINGS_MAX_FRAME_SIZE =         0x5
    val SETTINGS_MAX_HEADER_LIST_SIZE =   0x6
  }

  object DefaultSettings {

    def HEADER_TABLE_SIZE = 4096                                  //  Section 6.5.2
    def ENABLE_PUSH = true // 1                                   // Section 6.5.2
    def MAX_CONCURRENT_STREAMS = Integer.MAX_VALUE // (infinite)  // Section 6.5.2
    def INITIAL_WINDOW_SIZE = 65535                               // Section 6.5.2
    def MAX_FRAME_SIZE = 16384                                    // Section 6.5.2
    def MAX_HEADER_LIST_SIZE = Integer.MAX_VALUE //(infinite)     // Section 6.5.2
  }

  //////////////////////////////////////////////////

  private val exceptionsMap = new mutable.HashMap[Int, ErrorGen]()

  class ErrorGen private[http20](val code: Int, val name: String) {
    exceptionsMap += ((code, this))

    def apply(): Http2Exception = Http2Exception(code, name)(name, None)
    def apply(msg: String): Http2Exception = Http2Exception(code, name)(name + ": " + msg, None)
    def apply(msg: String, stream: Int): Http2Exception = Http2Exception(code, name)(msg, Some(stream))

    def unapply(ex: Http2Exception): Option[(String, Option[Int])] = {
      if (ex.code == code) Some(( ex.msg, ex.stream))
      else None
    }
  }

  final case class Http2Exception(val code: Int, val name: String)(val msg: String, val stream: Option[Int])
    extends Exception(msg) with NoStackTrace {
    def msgBuffer(): ByteBuffer = {
      val bytes = msg.getBytes(US_ASCII)
      ByteBuffer.wrap(bytes)
    }
  }

  val NO_ERROR                 = new ErrorGen(0x0, "NO_ERROR")
  val PROTOCOL_ERROR           = new ErrorGen(0x1, "PROTOCOL_ERROR")
  val INTERNAL_ERROR           = new ErrorGen(0x2, "INTERNAL_ERROR")
  val FLOW_CONTROL_ERROR       = new ErrorGen(0x3, "FLOW_CONTROL_ERROR")
  val SETTINGS_TIMEOUT         = new ErrorGen(0x4, "SETTINGS_TIMEOUT")
  val STREAM_CLOSED            = new ErrorGen(0x5, "STREAM_CLOSED")
  val FRAME_SIZE_ERROR         = new ErrorGen(0x6, "FRAME_SIZE_ERROR")
  val REFUSED_STREAM           = new ErrorGen(0x7, "FRAME_SIZE_ERROR")
  val CANCEL                   = new ErrorGen(0x8, "CANCEL")
  val COMPRESSION_ERROR        = new ErrorGen(0x9, "COMPRESSION_ERROR")
  val CONNECT_ERROR            = new ErrorGen(0xa, "CONNECT_ERROR")
  val ENHANCE_YOUR_CALM        = new ErrorGen(0xb, "ENHANCE_YOUR_CALM")
  val INADEQUATE_SECURITY      = new ErrorGen(0xc, "INADEQUATE_SECURITY")
  val HTTP_1_1_REQUIRED        = new ErrorGen(0xd, "HTTP_1_1_REQUIRED")

  //////////////////////////////////////////////////

  sealed trait DecoderResult

  case object Continue extends DecoderResult
  case object Halt extends DecoderResult
  case object BufferUnderflow extends DecoderResult

  case class Error(err: Http2Exception) extends DecoderResult


  //////////////////////////////////////////////////

  private[http20] object Flags {
    val END_STREAM = 0x1.toByte
    def END_STREAM(flags: Byte): Boolean  = checkFlag(flags, END_STREAM)   // Data, Header

    val PADDED = 0x8.toByte
    def PADDED(flags: Byte): Boolean      = checkFlag(flags, PADDED)       // Data, Header

    val END_HEADERS = 0x4.toByte
    def END_HEADERS(flags: Byte): Boolean = checkFlag(flags, END_HEADERS)  // Header, push_promise

    val PRIORITY = 0x20.toByte
    def PRIORITY(flags: Byte): Boolean    = checkFlag(flags, PRIORITY)     // Header

    val ACK = 0x1.toByte
    def ACK(flags: Byte): Boolean         = checkFlag(flags, ACK)          // ping

    def DepID(id: Int): Int            = id & Masks.int31
    def DepExclusive(id: Int): Boolean = (Masks.exclsive & id) != 0
  }

  @inline
  private def checkFlag(flags: Byte, flag: Byte) = (flags & flag) != 0


}
