package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

trait HeaderHttp20Encoder[Headers] { self: Http20FrameEncoder =>

  protected val headerEncoder: HeaderEncoder[Headers]

  final def setEncoderMaxTable(max: Int): Unit = { headerEncoder.setMaxTableSize(max) }

  final def mkHeaderFrame(headers: Headers,
                    streamId: Int,
                    dependentStreamId: Int,
                    exclusive: Boolean,
                    priority: Int,
                    end_headers: Boolean,
                    end_stream: Boolean,
                    padding: Int): Seq[ByteBuffer] = {

    val buffer = headerEncoder.encodeHeaders(headers, end_headers)

    mkHeaderFrame(buffer, streamId, dependentStreamId, exclusive,
                  priority, end_headers, end_stream, padding)
  }

  final def mkPushPromiseFrame(streamId: Int,
                                  promiseId: Int,
                                  end_headers: Boolean,
                                  padding: Int,
                                  headers: Headers): Seq[ByteBuffer] = {
    val buffer = headerEncoder.encodeHeaders(headers, end_headers)
    mkPushPromiseFrame(streamId, promiseId, end_headers, padding, buffer)
  }

  final def mkContinuationFrame(streamId: Int, end_headers: Boolean, headers: Headers): Seq[ByteBuffer] = {
    val buffer = headerEncoder.encodeHeaders(headers, end_headers)
    mkContinuationFrame(streamId, end_headers, buffer)
  }
}


