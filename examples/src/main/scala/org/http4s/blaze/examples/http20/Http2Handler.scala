package org.http4s.blaze.examples.http20


import java.nio.ByteBuffer

import org.http4s.blaze.http.http20._
import org.http4s.blaze.http._
import org.http4s.blaze.util.Execution.trampoline

import scala.concurrent.{ExecutionContext, Future}


import scala.concurrent.duration._

object Http2Handler {
  def apply(timeout: Duration = Duration.Inf, ec: ExecutionContext = trampoline): BasicHttpStage =
    new BasicHttpStage(timeout, ec, service)

  private val tail = (0 to 1024*1024).mkString("\n", "\n", "")

  private def service(method: Method, uri: Uri, hs: Headers, body: ByteBuffer): Future[Response] = {

    val body = hs.map { case (k, v) => "[\"" + k + "\", \"" + v + "\"]" }
                 .mkString("Headers\n", "\n", tail)

    Future.successful(SimpleHttpResponse.Ok(body))
  }
}
