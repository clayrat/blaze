package org.http4s.blaze.http.http20

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.US_ASCII


import com.twitter.hpack.Encoder

/** This needs to contain the state of the header Encoder */
trait HeaderEncoder[T] {

  /** Note that the default value is 4096 bytes */
  def getMaxTableSize(): Int

  def setMaxTableSize(max: Int): Unit

  def encodeHeaders(hs: T): ByteBuffer
}

/** Simple Headers type for use in blaze and testing */
final class SeqTupleHeaderEncoder(private var maxTableSize: Int = DefaultSettings.HEADER_TABLE_SIZE)
  extends HeaderEncoder[Seq[(String, String)]]
{
  require(maxTableSize <= DefaultSettings.HEADER_TABLE_SIZE, "Invalid initial table size")

  private val encoder = new Encoder(getMaxTableSize)
  private val os = new ByteArrayOutputStream(512)


  /** Note that the default value is 4096 bytes */
  override def getMaxTableSize(): Int = maxTableSize

  override def setMaxTableSize(max: Int): Unit = {
    maxTableSize = max
    encoder.setMaxHeaderTableSize(os, max)
  }

  override def encodeHeaders(hs: Seq[(String, String)]): ByteBuffer = {
    hs.foreach { case (k,v) => encoder.encodeHeader(os, k.getBytes(US_ASCII), v.getBytes(US_ASCII), false) }
    val buff = ByteBuffer.wrap(os.toByteArray())
    os.reset()
    buff
  }
}
