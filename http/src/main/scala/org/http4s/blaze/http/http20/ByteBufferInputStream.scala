package org.http4s.blaze.http.http20

import java.io.InputStream
import java.nio.ByteBuffer

class ByteBufferInputStream(buffer: ByteBuffer) extends InputStream {
  override def read(): Int = buffer.get()

  override def read(b: Array[Byte], off: Int, len: Int): Int = {
    if (buffer.remaining() == 0) -1
    else {
      val readSize = math.min(len, buffer.remaining())
      super.read(b, 0, 1)
      buffer.get(b, off, readSize)
      readSize
    }

  }

  override def available(): Int = buffer.remaining()
}
