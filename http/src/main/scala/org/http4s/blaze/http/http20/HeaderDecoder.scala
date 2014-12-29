package org.http4s.blaze.http.http20

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.US_ASCII

import org.http4s.blaze.util.BufferTools

import scala.collection.mutable.ListBuffer

trait HeaderDecoder[Result] {

  def empty(): Boolean

  def decode(buffer: ByteBuffer): Unit

  def setMaxTableSize(max: Int): Unit

  /** Returns the header collection and clears the builder */
  def result(): Result
}

final class SeqTupleHeaderDecoder(maxHeaderSize: Int, initialTableSize: Int) extends HeaderDecoder[Seq[(String, String)]] {
  import com.twitter.hpack.{Decoder, HeaderListener}

  private var leftovers: ByteBuffer = null

  private val decoder = new Decoder(maxHeaderSize, initialTableSize)

  private val acc = new ListBuffer[(String,String)]

  private val listener = new HeaderListener {
    override def addHeader(name: Array[Byte], value: Array[Byte], sensitive: Boolean): Unit = {
      val k = new String(name, US_ASCII)
      val v = new String(value, US_ASCII)
      acc += ((k,v))
    }
  }

  override def empty(): Boolean = acc.isEmpty

  override def setMaxTableSize(max: Int): Unit = decoder.setMaxHeaderTableSize(max)

  /** Returns the header collection and clears the builder */
  override def result(): Seq[(String, String)] = {
    decoder.endHeaderBlock()
    val r = acc.result()
    acc.clear()
    r
  }

  override def decode(buffer: ByteBuffer): Unit = {
    leftovers = BufferTools.concatBuffers(leftovers, buffer)
    val is = new ByteBufferInputStream(leftovers)
    decoder.decode(is, listener)

    if (!leftovers.hasRemaining()) {
      leftovers = null
    }
  }
}
