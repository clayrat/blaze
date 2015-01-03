package org.http4s.blaze.util

import org.specs2.mutable._
import java.nio.ByteBuffer

import BufferTools._

class BufferToolsSpec extends Specification {

  def b(i: Int = 1) = {
    val b = BufferTools.allocate(4)
    b.putInt(i).flip()
    b
  }

  "BufferTools" should {
    "discard null old buffers" in {
      val bb = b()
      BufferTools.concatBuffers(null, bb) should_== bb
    }

    "discard empty buffers" in {
      val b1 = b(); val b2 = b()
      b1.getInt()
      BufferTools.concatBuffers(b1, b2) should_== b2
    }

    "concat two buffers" in {
      val b1 = b(1); val b2 = b(2)
      val a = BufferTools.concatBuffers(b1, b2)
      a.remaining() should_== 8
      a.getInt() should_== 1
      a.getInt() should_== 2
    }

    "append the result of one to the end of another if there is room" in {
      val b1 = BufferTools.allocate(9)
      b1.position(1)              // offset by 1 to simulated already having read a byte
      b1.putInt(1).flip().position(1)
      val b2 = b(2)

      val bb = BufferTools.concatBuffers(b1, b2)
      bb should_== b1
      bb.position() should_== 1
      bb.getInt() should_== 1
      bb.getInt() should_== 2
    }

    "compact a buffer to fit the second" in {
      val b1 = BufferTools.allocate(8)
      b1.putInt(0).putInt(1).flip()
      b1.getInt() // Discard the first element
      val b2 = b(2)

      val bb = BufferTools.concatBuffers(b1, b2)
      bb should_== b1
      bb.getInt() should_== 1
      bb.getInt() should_== 2
    }

    "check if buffers are empty" in {
      checkEmpty(Array(allocate(0), allocate(3))) must_== false
      checkEmpty(Seq(allocate(0), allocate(3))) must_== false

      checkEmpty(Array(allocate(0), allocate(0))) must_== true
      checkEmpty(Seq(allocate(0), allocate(0))) must_== true

      checkEmpty(Array(allocate(3))) must_== false
      checkEmpty(Seq(allocate(3))) must_== false

      checkEmpty(Array(allocate(0))) must_== true
      checkEmpty(Seq(allocate(0))) must_== true

      checkEmpty(Array[ByteBuffer]()) must_== true
      checkEmpty(Seq()) must_== true
    }

    "Find index of first Non-empty buffer" in {
      dropEmpty(Array(allocate(1))) must_== 0
      val arr1 = Array(allocate(0))
      dropEmpty(arr1) must_== 0
      arr1(0) must_== emptyBuffer

      val arr2 = Array(allocate(1), allocate(2))
      dropEmpty(arr2) must_== 0
      arr2(0) must_!= emptyBuffer
      arr2(1) must_!= emptyBuffer

      val arr3 = Array(allocate(0), allocate(2))
      dropEmpty(arr3) must_== 1
      arr3(0) must_== emptyBuffer
      arr3(1) must_!= emptyBuffer

      val arr4 = Array(allocate(0), allocate(0))
      dropEmpty(arr4) must_== 1
      arr4(0) must_== emptyBuffer
      arr4(1) must_== emptyBuffer

      val arr5 = Array(allocate(1), allocate(0))
      dropEmpty(arr5) must_== 0
      arr2(0) must_!= emptyBuffer
      arr2(1) must_!= emptyBuffer
    }
  }

}
