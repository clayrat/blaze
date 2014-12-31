package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

import org.http4s.blaze.util.BufferTools
import org.http4s.blaze.util.BufferTools._

import org.specs2.mutable.Specification

import scala.collection.mutable.ListBuffer


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

    def dec(sId: Int, isL: Boolean, padding: Int) = decoder(new MockFrameHandler(false) {
      override def onDataFrame(streamId: Int, isLast: Boolean, data: ByteBuffer, flowSize: Int): Http2Result = {
        streamId must_== sId
        isLast must_== isL
        compare(data::Nil, dat::Nil) must_== true
        padding must_== flowSize

        Continue
      }
    })

    "make round trip" in {
      val buff1 = joinBuffers(encoder.mkDataFrame(dat, 1, true, 0))
      dec(1, true, dat.remaining()).decodeBuffer(buff1) must_== Continue
    }

    "Decode 'END_STREAM flag" in {
      // payload size is buffer + padding + 1 byte
      val buff2 = joinBuffers(encoder.mkDataFrame(dat, 3, false, 100))
      dec(3, false, dat.remaining() + 101).decodeBuffer(buff2) must_== Continue
    }

    "Decode padded buffers" in {
      val buff3 = addBonus(encoder.mkDataFrame(dat, 1, true, 100))
      dec(1, true, dat.remaining() + 101).decodeBuffer(buff3) must_== Continue
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
                                    buffer: ByteBuffer): Http2Result = {
          sId must_== streamId
          sDep must_== streamDep
          ex must_== exclusive
          p must_== priority
          end_h must_== end_headers
          end_s must_== end_stream
          assert(compare(buffer::Nil, dat::Nil))
          Continue
        }
    })

    "make round trip" in {
      val buff1 = joinBuffers(encoder.mkHeaderFrame(dat, 1, 1, false, 1, true, true, 0))
      dec(1, 1, false, 1, true, true).decodeBuffer(buff1) must_== Continue
      buff1.remaining() must_== 0

      val buff2 = joinBuffers(encoder.mkHeaderFrame(dat, 2, 3, false, 6, true, false, 0))
      dec(2, 3, false, 6, true, false).decodeBuffer(buff2) must_== Continue
      buff2.remaining() must_== 0
    }

    "preserve padding" in {
      val buff = addBonus(encoder.mkHeaderFrame(dat, 1, 1, false, 1, true, true, 0))
      dec(1, 1, false, 1, true, true).decodeBuffer(buff) must_== Continue
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
        override def onPriorityFrame(streamId: Int, streamDep: Int, exclusive: Boolean, priority: Int): Http2Result = {
          sId must_== streamId
          sDep must_== streamDep
          ex must_== exclusive
          p must_== priority
          Continue
        }
      })

    "make a round trip" in {
      val buff1 = encoder.mkPriorityFrame(1, 1, true, 1)
      dec(1, 1, true, 1).decodeBuffer(buff1) must_== Continue
      buff1.remaining() must_== 0

      val buff2 = encoder.mkPriorityFrame(1, 1, false, 10)
      dec(1, 1, false, 10).decodeBuffer(buff2) must_== Continue
      buff2.remaining() must_== 0
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

  "RST_STREAM frame" should {
    def dec(sId: Int, c: Int) =
      decoder(new MockFrameHandler(false) {

        override def onRstStreamFrame(streamId: Int, code: Int): Http2Result = {
          sId must_== streamId
          c must_== code
          Continue
        }
      })

    "make round trip" in {
      val buff1 = encoder.mkRstStreamFrame(1, 0)
      dec(1, 0).decodeBuffer(buff1) must_== Continue
      buff1.remaining() must_== 0
    }

    "fail on bad stream Id" in {
      encoder.mkRstStreamFrame(0, 1) must throwA[Exception]
    }
  }

  "SETTINGS frame" should {
    def dec(a: Boolean, s: Seq[Setting]) =
      decoder(new MockFrameHandler(false) {
        override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): Http2Result = {
          s must_== settings
          a must_== ack
          Continue
        }
      })

    "make a round trip" in {
      val settings = (0 until 100).map(i => Setting(i, i + 3))

      val buff1 = encoder.mkSettingsFrame(false, settings)
      dec(false, settings).decodeBuffer(buff1) must_== Continue
      buff1.remaining() must_== 0
    }

    "reject settings on ACK" in {
      val settings = (0 until 100).map(i => Setting(i, i + 3))
      encoder.mkSettingsFrame(true, settings) must throwA[Exception]
    }
  }

  def hencoder = new HeaderHttp20Encoder with Http20FrameEncoder {
    override type Headers = Seq[(String,String)]
    override protected val headerEncoder: HeaderEncoder[Headers] = new SeqTupleHeaderEncoder()
  }

  "HEADERS frame with compressors" should {
    def dec(sId: Int, sDep: Int, ex: Boolean, p: Int, es: Boolean, hs: Seq[(String, String)]) =
      decoder(new MockHeaderDecodingFrameHandler {
        override def onCompleteHeadersFrame(headers: Seq[(String,String)],
                                            streamId: Int,
                                            streamDep: Int,
                                            exclusive: Boolean,
                                            priority: Int,
                                            end_stream: Boolean): Http2Result = {
          sId must_== streamId
          sDep must_== streamDep
          ex must_== exclusive
          p must_== priority
          es must_== end_stream
          hs must_== headers
          Halt
        }
      })

    "Make a simple round trip" in {
      val hs = Seq("foo" -> "bar", "biz" -> "baz")
      val bs = hencoder.mkHeaderFrame(hs, 1, 0, false, -1, true, true, 0)

      dec(1, 0, false, 16, true, hs).decodeBuffer(BufferTools.joinBuffers(bs)) must_== Halt
    }

    "Make a round trip with a continuation frame" in {
      val hs = Seq("foo" -> "bar", "biz" -> "baz")
      val bs = hencoder.mkHeaderFrame(hs, 1, 0, false, -1, false, true, 0)

      val decoder = dec(1, 0, false, 16, true, hs)

      decoder.inHeaderSequence() must_== false
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.inHeaderSequence() must_== true

      val bs2 = hencoder.mkContinuationFrame(1, true, Nil)
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must_== Halt
    }
  }
}
