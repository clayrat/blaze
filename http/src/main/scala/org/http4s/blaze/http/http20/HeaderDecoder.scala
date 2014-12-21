package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

trait HeaderDecoder[Result] {

  def empty(): Boolean

  def decode(buffer: ByteBuffer): Unit

  def setMaxTableSize(max: Int): Unit

  /** Returns the header collection and clears the builder */
  def result(): Result
}
