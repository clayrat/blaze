package org.http4s.blaze
package examples

import org.http4s.blaze.http._

import java.nio.ByteBuffer

import scala.concurrent.Future

import org.http4s.blaze.http.http_parser.BaseExceptions.BadRequest
import org.http4s.blaze.pipeline.stages.monitors.IntervalConnectionMonitor


object ExampleHttpServerStage {
  def apply(status: Option[IntervalConnectionMonitor], maxRequestLength: Int): HttpServerStage = {
    def handleRequest(method: Method, uri: Uri, headers: Seq[(String, String)], body: ByteBuffer): Future[SimpleHttpResponse] = {

      if (uri.endsWith("error")) Future.failed(new BadRequest("You request resulted in an error! (Intentionally...)"))
      else if (uri.endsWith("status")) Future.successful(SimpleHttpResponse("OK", 200, Nil, ByteBuffer.wrap(getStatus().getBytes())))
      else {
        val respmsg = s"Hello world!\nRequest URI: $uri\n" + headers.map{ case (k,v) => k + ": " + v }.mkString("\n")
        val body = ByteBuffer.wrap(respmsg.getBytes())

        Future.successful(SimpleHttpResponse("OK", 200, Nil, body))
      }
    }

    def getStatus(): String = status.fold("")(_.getStats().toString)
    
    new HttpServerStage(maxRequestLength)(handleRequest)
  }
}