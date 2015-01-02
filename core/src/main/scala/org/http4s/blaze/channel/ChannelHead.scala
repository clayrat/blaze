package org.http4s.blaze.channel

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException

import org.http4s.blaze.pipeline.Command._
import org.http4s.blaze.pipeline.HeadStage

trait ChannelHead extends HeadStage[ByteBuffer] {
  import ChannelHead.brokePipeMessages

  /** Close the channel with an error
    * __NOTE:__ EOF is a valid error to close the channel with and signals normal termination.
    * This method should __not__ send a [[Disconnected]] command. */
  protected def closeWithError(t: Throwable): Unit

  /** Close the channel under normal conditions. A [[Disconnected]] command will be sent */
  final def closeChannel(): Unit = {
    sendInboundCommand(Disconnected)
    closeWithError(EOF)
  }

  override def outboundCommand(cmd: OutboundCommand): Unit = cmd match {
      case Disconnect => closeWithError(EOF)
      case Error(e)   => closeWithError(e)
      case cmd        => // NOOP
  }

  /** Checks the error. If the error is determined to be fatal to the channel, it will be
    * closed with `closeChannel()`, and thus a [[Disconnected]] command will be emitted.
    */
  protected def checkError(e: Throwable): Throwable = e match {
    case EOF =>
      logger.warn("Unhandled EOF")
      EOF

    case e: ClosedChannelException =>
      logger.debug("Channel closed, dropping packet")
      EOF

    case e: IOException if brokePipeMessages.contains(e.getMessage) =>
      closeChannel()
      EOF

    case e: IOException =>
      logger.warn(e)("Channel IOException not known to be a disconnect error")
      closeChannel()
      EOF

    case e: Throwable =>  // Don't know what to do besides close
      logger.error(e)("Unexpected fatal error")
      closeWithError(e)
      e
  }
}

object ChannelHead {

  // If the connection is forcibly closed, we might get an IOException with one of the following messages
  private [blaze] val brokePipeMessages = Seq(
    "Connection reset by peer",   // Found on Linux
    "An existing connection was forcibly closed by the remote host",    // Found on windows
    "Broken pipe"   // also found on Linux
  )
}
