package org.http4s.blaze.http.http20

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.US_ASCII

import org.http4s.blaze.util.BufferTools

import scala.collection.mutable.ListBuffer

import com.twitter.hpack.{Decoder, HeaderListener}

abstract class HeaderDecoder[To](maxHeaderSize: Int,
                              val maxTableSize: Int) { self =>

  require(maxTableSize >= DefaultSettings.HEADER_TABLE_SIZE)

  private var leftovers: ByteBuffer = null

  private val decoder = new Decoder(maxHeaderSize, maxTableSize)
  private val listener = new HeaderListener {
    override def addHeader(name: Array[Byte], value: Array[Byte], sensitive: Boolean): Unit = {
      val k = new String(name, US_ASCII)
      val v = new String(value, US_ASCII)
      self.addHeader(k,v, sensitive)
    }
  }

  def addHeader(name: String, value: String, sensitive: Boolean): Unit

  def empty(): Boolean

  /** Returns the header collection and clears the builder */
  def result(): To

  final def decode(buffer: ByteBuffer): Unit = {
    val buff = BufferTools.concatBuffers(leftovers, buffer)
    val is = new ByteBufferInputStream(buff)
    decoder.decode(is, listener)

    if (!buff.hasRemaining()) leftovers = null
    else if (buff ne buffer) leftovers = buff
    else {  // need to drain the input buffer so we are not sharing this buffer with someone else
      val b = BufferTools.allocate(buff.remaining())
      b.put(buff).flip()
      leftovers = b
    }
  }

  final def setMaxTableSize(max: Int): Unit = decoder.setMaxHeaderTableSize(max)
}

final class SeqTupleHeaderDecoder(maxHeaderSize: Int,
                                 maxHeaderTable: Int  = DefaultSettings.HEADER_TABLE_SIZE)
  extends HeaderDecoder[Seq[(String, String)]](maxHeaderSize, maxHeaderTable) {

  private val acc = new ListBuffer[(String, String)]

  override def addHeader(name: String, value: String, sensitive: Boolean): Unit = acc += ((name, value))

  /** Returns the header collection and clears the builder */
  override def result(): List[(String, String)] = {
    val r = acc.result()
    acc.clear()
    r
  }

  override def empty(): Boolean = acc.isEmpty
}
