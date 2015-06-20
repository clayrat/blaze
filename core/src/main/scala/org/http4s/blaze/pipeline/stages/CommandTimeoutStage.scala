package org.http4s.blaze.pipeline.stages

import org.http4s.blaze.pipeline.Command
import org.http4s.blaze.pipeline.stages.CommandTimeoutStage.{ TimeoutCancel, TimeoutBegin }
import org.http4s.blaze.util.Execution._
import org.http4s.blaze.util.TickWheelExecutor

import scala.concurrent.duration.Duration


/** Trigger timeouts based on pipeline commands
  *
  * The pipeline [[Command]]s [[TimeoutBegin]] and [[TimeoutCancel]] are used to
  * start and abort a timeout signal.
  */
class CommandTimeoutStage[T](timeout: Duration, exec: TickWheelExecutor = scheduler)
  extends TimeoutStageBase[T](timeout, exec) {
  // Overrides to propagate commands.
  override def outboundCommand(cmd: Command.OutboundCommand): Unit = cmd match {
    case TimeoutBegin => resetTimeout()
    case TimeoutCancel => cancelTimeout()

    case _ => super.outboundCommand(cmd)
  }

  // Overrides to propagate commands.
  override def inboundCommand(cmd: Command.InboundCommand): Unit = cmd match {
    case TimeoutBegin => resetTimeout()
    case TimeoutCancel => cancelTimeout()
    case _ => super.inboundCommand(cmd)
  }
}

object CommandTimeoutStage {
  object TimeoutBegin extends Command.InboundCommand with Command.OutboundCommand
  object TimeoutCancel extends Command.InboundCommand with Command.OutboundCommand
}

