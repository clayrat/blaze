package org.http4s.blaze.examples.http20

import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup

import org.eclipse.jetty.alpn.ALPN

import org.http4s.blaze.channel._
import org.http4s.blaze.channel.nio2.NIO2SocketServerChannelFactory
import org.http4s.blaze.examples.ExampleKeystore
import org.http4s.blaze.http.http20.NodeMsg
import org.http4s.blaze.pipeline.{LeafBuilder, TrunkBuilder}
import org.http4s.blaze.pipeline.stages.SSLStage

class Http2Server(port: Int) {
  import Http2Server.Http2Meg

  val sslContext = ExampleKeystore.sslContext()

  private def nodeBuilder(): LeafBuilder[Http2Meg] = LeafBuilder(Http2Handler())

  private val f: BufferPipelineBuilder = { _ =>
    val eng = sslContext.createSSLEngine()
    eng.setUseClientMode(false)

    ALPN.put(eng, new ServerProvider)
    TrunkBuilder(new SSLStage(eng)).cap(Http20Hub(nodeBuilder))
  }

  val group = AsynchronousChannelGroup.withFixedThreadPool(10, java.util.concurrent.Executors.defaultThreadFactory())

  private val factory = new NIO2SocketServerChannelFactory(f)

  def run(): Unit = factory.bind(new InetSocketAddress(port)).run()
}

object Http2Server {
  type Http2Meg = NodeMsg.Http2Msg[Seq[(String, String)]]

  def main(args: Array[String]) {
    println("Hello world!")
    new Http2Server(4430).run()
  }
}