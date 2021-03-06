package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

import org.http4s.blaze.http.http20.NodeMsg.Http2Msg
import org.http4s.blaze.pipeline.Command.OutboundCommand
import org.http4s.blaze.pipeline.LeafBuilder

import scala.concurrent.Future


private[http20]trait Http2StageConcurrentOps {

  def makePipeline(streamId: Int): LeafBuilder[NodeMsg.Http2Msg]

  /** Write the buffers to the socket */
  def writeBuffers(data: Seq[ByteBuffer]): Future[Unit]

  def streamRead(stream: AbstractStream): Future[Http2Msg]

  def streamWrite(stream: AbstractStream, data: Seq[Http2Msg]): Future[Unit]

  def streamCommand(stream: AbstractStream, cmd: OutboundCommand): Unit

  def onFailure(t: Throwable, position: String): Unit
}
