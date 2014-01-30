package blaze.pipeline.stages

import org.scalatest.{Matchers, WordSpec}
import blaze.pipeline.Command._
import scala.concurrent.Future
import blaze.pipeline.{TailStage, BaseStage, RootBuilder, HeadStage}
import scala.util.{Failure, Success}
import blaze.util.Execution


/**
 * @author Bryce Anderson
 *         Created on 1/26/14
 *
 *         What a mess. Its almost a full blown implementation of something to test this
 */
class HubStageSpec extends WordSpec with Matchers {

  implicit val ec = Execution.directec

  case class Msg(k: Int, msg: String)

  val msgs = Msg(1, "one")::Msg(2, "two")::Nil

  class TestHubStage(builder: RootBuilder[Msg, Msg] => HeadStage[Msg]) extends HubStage[Msg, Msg, Int](builder) {

    override protected def stageStartup(): Unit = {
      super.stageStartup()
      reqLoop()
    }

    private def reqLoop(): Unit = channelRead().onComplete {
      case Success(msg) =>
        val k = msg.k
        getNode(k) match {
          case Some(node) => node.sendMsg(msg)
          case None =>
            val n = makeAndInitNode(k)
            n.sendMsg(msg)
        } 
      
        reqLoop()

      case Failure(EOF) => 
        logger.trace("Finished.")
        closeAllNodes()

      case Failure(t)   => throw t
    }

    protected def nodeReadRequest(key: Int, size: Int): Unit = {}

    protected def onNodeWrite(key: Int, data: Msg): Future[Any] = channelWrite(data)

    protected def onNodeWrite(key: Int, data: Seq[Msg]): Future[Any] = channelWrite(data)

    protected def onNodeCommand(key: Int, cmd: Command): Unit = {
      logger.trace(s"Received command $cmd")
      cmd match {
        case Shutdown => removeNode(key)
        case _ => sendOutboundCommand(cmd)
      }
    }
  }

  class Echo extends TailStage[Msg] {
    def name: String = "EchoTest"

    override protected def stageStartup(): Unit = {
      readLoop()
    }

    private def readLoop(): Unit = channelRead().onComplete {
      case Success(msg) =>
        channelWrite(Msg(msg.k, "Echoing: " + msg.msg))
          .onSuccess{ case _ => readLoop() }

      case Failure(EOF) => logger.debug("Received EOF")
    }
  }

  def nodeBuilder(r: RootBuilder[Msg, Msg]): HeadStage[Msg] = r.cap(new Echo)

  def rootBuilder(r: RootBuilder[Msg, Msg]): HeadStage[Msg] = r.cap(new TestHubStage(nodeBuilder))

  "HubStage" should {
    "Initialize" in {
      val h = new SeqHead(msgs)
      val rb = new RootBuilder(h, h)

      rootBuilder(rb)
      h.inboundCommand(Connected)
      h.inboundCommand(Shutdown)
      // All the business should have finished because it was done using the directec

      h.results should equal(Vector(Msg(1, "Echoing: one"), Msg(2, "Echoing: two")))



    }
  }

}