package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

import org.http4s.blaze.util.BufferTools._

import org.specs2.mutable.Specification



class Http20FrameCodecSpec extends Specification {

  def mkData(size: Int): ByteBuffer = {
    val s = "The quick brown fox jumps over the lazy dog".getBytes()
    val buff = ByteBuffer.allocate(size)
    while(buff.hasRemaining) buff.put(s, 0, math.min(buff.remaining(), s.length))
    buff.flip()
    buff
  }

  def compare(s1: Seq[ByteBuffer], s2: Seq[ByteBuffer]): Boolean = {
    val b1 = joinBuffers(s1)
    val b2 = joinBuffers(s2)

    if (b1.remaining() != b2.remaining()) false
    else {
      def go(): Boolean = {
        if (b1.hasRemaining) {
          if (b1.get() == b2.get()) go()
          else false
        }
        else true
      }

      go()
    }
  }

  val bonusSize = 10

  def addBonus(buffers: Seq[ByteBuffer]): ByteBuffer = {
    joinBuffers(buffers :+ ByteBuffer.allocate(bonusSize))
  }

  def decoder(h: FrameHandler, inHeaders: Boolean = false) = new Http20FrameDecoder {
    def handler = h

  }

  def encoder = new Http20FrameEncoder {}

  "DATA frame" should {

    def dat = mkData(20)

    def dec(sId: Int, isL: Boolean) = decoder(new MockFrameHandler(false) {
      override def onDataFrame(streamId: Int, isLast: Boolean, data: ByteBuffer): DecoderResult = {
        if (streamId == sId && isLast == isL && compare(data::Nil, dat::Nil)) Success
        else sys.error("Fail.")
      }
    })

    "make round trip" in {
      val buff1 = joinBuffers(encoder.mkDataFrame(dat, 1, true, 0))
      dec(1, true).decodeBuffer(buff1) must_== Success
    }

    "Decode 'END_STREAM flag" in {
      val buff2 = joinBuffers(encoder.mkDataFrame(dat, 3, false, 100))
      dec(3, false).decodeBuffer(buff2) must_== Success
    }

    "Decode padded buffers" in {
      val buff3 = addBonus(encoder.mkDataFrame(dat, 1, true, 100))
      dec(1, true).decodeBuffer(buff3) must_== Success
      buff3.remaining() must_== bonusSize
    }


  }

  // This doesn't check header compression
  "HEADERS frame" should {
    def dat = mkData(20)

    def dec(sId: Int, sDep: Int, ex: Boolean, p: Int, end_h: Boolean,  end_s: Boolean) =
      decoder(new MockFrameHandler(false) {
        override def onHeadersFrame(streamId: Int,
                                    streamDep: Int,
                                    exclusive: Boolean,
                                    priority: Int,
                                    end_headers: Boolean,
                                    end_stream: Boolean,
                                    buffer: ByteBuffer): DecoderResult = {
          assert(sId == streamId)
          assert(sDep == streamDep)
          assert(ex == exclusive)
          assert(p == priority)
          assert(end_h == end_headers)
          assert(end_s == end_stream)
          assert(compare(buffer::Nil, dat::Nil))
          Success
        }
    })

    "make round trip" in {
      val buff1 = joinBuffers(encoder.mkHeaderFrame(dat, 1, 1, false, 1, true, true, 0))
      dec(1, 1, false, 1, true, true).decodeBuffer(buff1) must_== Success

      val buff2 = joinBuffers(encoder.mkHeaderFrame(dat, 2, 3, false, 6, true, false, 0))
      dec(2, 3, false, 6, true, false).decodeBuffer(buff2) must_== Success
    }

    "preserve padding" in {
      val buff = addBonus(encoder.mkHeaderFrame(dat, 1, 1, false, 1, true, true, 0))
      dec(1, 1, false, 1, true, true).decodeBuffer(buff) must_== Success
      buff.remaining() must_== bonusSize
    }

    "fail on bad stream dependency" in {
      encoder.mkHeaderFrame(dat, 1, 0, false, 1, true, true, 0) must throwA[Exception]
    }

    "fail on bad stream ID" in {
      encoder.mkHeaderFrame(dat,0 , 1, false, 1, true, true, 0) must throwA[Exception]
    }

    "fail on bad padding" in {
      encoder.mkHeaderFrame(dat,1 , 1, false, 1, true, true, -10) must throwA[Exception]
      encoder.mkHeaderFrame(dat,1 , 1, false, 1, true, true, 500) must throwA[Exception]
    }
  }

  "PRIORITY frame" should {
    def dec(sId: Int, sDep: Int, ex: Boolean, p: Int) =
      decoder(new MockFrameHandler(false) {
        override def onPriorityFrame(streamId: Int, streamDep: Int, exclusive: Boolean, priority: Int): DecoderResult = {
          assert(sId == streamId)
          assert(sDep == streamDep)
          assert(ex == exclusive)
          assert(p == priority)
          Success
        }
      })

    "make a round trip" in {
      val buff1 = encoder.mkPriorityFrame(1, 1, true, 1)
      dec(1, 1, true, 1).decodeBuffer(buff1) must_== Success

      val buff2 = encoder.mkPriorityFrame(1, 1, false, 10)
      dec(1, 1, false, 10).decodeBuffer(buff2) must_== Success
    }

    "fail on bad priority" in {
      encoder.mkPriorityFrame(1, 1, true, 500) must throwA[Exception]
      encoder.mkPriorityFrame(1, 1, true, -500) must throwA[Exception]
    }

    "fail on bad streamId" in {
      encoder.mkPriorityFrame(0, 1, true, 0) must throwA[Exception]
    }

    "fail on bad stream dependency" in {
      encoder.mkPriorityFrame(1, 0, true, 0) must throwA[Exception]
    }
  }

}
