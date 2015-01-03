package org.http4s.blaze.examples.http20

import org.http4s.blaze.examples.http20.Http2Server.Http2Meg
import org.http4s.blaze.http.http20._
import org.http4s.blaze.pipeline.LeafBuilder

import scala.concurrent.duration._


object Http20Hub {
  def apply(builder: () => LeafBuilder[Http2Meg],
            timeout: Duration = Duration.Inf,
         maxHeaders: Int = 16*1024) =
    new Http2ServerHubStage[Seq[(String, String)]](
    new SeqTupleHeaderDecoder(maxHeaders),
    new SeqTupleHeaderEncoder(),
    builder,
    timeout,
    300   // max inbound streams
  )
}
