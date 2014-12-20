package org.http4s.blaze.http

package object http20 {
  
  val HeaderSize = 9

  private[http20] object Masks {
    val STREAMID = 0x7fffffff
    val LENGTH   =   0xffffff
    val int31    = 0x7fffffff

    val exclsive = ~int31
  }

  private[http20] object FrameTypes {
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

  private[http20] object Flags {
    def END_STREAM(flags: Byte): Boolean  = checkFlag(flags, 0x1)   // Data, Header
    def PADDED(flags: Byte): Boolean      = checkFlag(flags, 0x8)   // Data, Header

    def END_HEADERS(flags: Byte): Boolean = checkFlag(flags, 0x4)   // Header, push_promise
    def PRIORITY(flags: Byte): Boolean    = checkFlag(flags, 0x20)  // Header

    def ACK(flags: Byte): Boolean         = checkFlag(flags, 0x1)   // ping

    def DepID(id: Int): Int            = id & Masks.int31
    def DepExclusive(id: Int): Boolean = (Masks.exclsive & id) != 0
  }

  @inline
  private def checkFlag(flags: Byte, flag: Byte) = (flags & flag) != 0
}
