package org.http4s.blaze.examples.http20


import java.nio.ByteBuffer

import org.http4s.blaze.http.http20._
import org.http4s.blaze.pipeline.TailStage
import org.http4s.blaze.pipeline.{ Command => Cmd }

import scala.util.{Failure, Success}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class Http2Handler extends TailStage[NodeMsg.Http2Msg[Seq[(String,String)]]] {

  type Http2Msg = NodeMsg.Http2Msg[Seq[(String,String)]]

  override def name: String = "Http20Stage"

  override protected def stageStartup(): Unit = {
    super.stageStartup()

    readLoop()
  }

  def handleMsg(msg: Http2Msg): Unit = msg match {
      // We don't want any bodies on here...
    case NodeMsg.HeadersFrame(streamId, ex, true, hs) =>

      val tail = "" //(0 to 1024*1024).mkString("\n")

      val body = ByteBuffer.wrap(
        hs.map{ case (k, v) => "[\"" + k + "\", \"" + v + "\"]" }
          .mkString("Headers\n", "\n", tail)
          .getBytes("UTF-8")
      )
                 // pseudo-header status
      val hss = Seq((":status", "200"), ("content-type", "text/plain; charset=utf8"))

      val hframe = NodeMsg.HeadersFrame(streamId, ex, false, hss)
      val bframe = NodeMsg.DataFrame(true, body)

      channelWrite(Seq(hframe, bframe)).onComplete {
        case Success(_) => sendOutboundCommand(Cmd.Disconnect)
        case Failure(t) => sendOutboundCommand(Cmd.Error(t))
      }

    case msg => sendOutboundCommand(Cmd.Error(new Exception(s"Unhandled message: $msg")))
  }

  private def readLoop(): Unit = {
    channelRead(timeout = 30.seconds).onComplete {
      case Success(msg) =>
        logger.info("Received message: " + msg)
        handleMsg(msg)

      case Failure(t) =>
        logger.error(t)("Failure in read loop")
        sendOutboundCommand(Cmd.Error(t))
        stageShutdown()
    }
  }
}
