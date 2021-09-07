package co.topl.nodeView

import akka.actor.SupervisorStrategy.{Restart, Stop}
import akka.actor.{
  Actor,
  ActorInitializationException,
  ActorKilledException,
  ActorRef,
  DeathPactException,
  OneForOneStrategy,
  Props
}
import co.topl.modifier.block.{Block, BlockHeader}
import co.topl.modifier.transaction.Transaction
import co.topl.network.Broadcast
import co.topl.network.NetworkController.ReceivableMessages.SendToNetwork
import co.topl.network.message.{InvData, InvSpec, Message}
import co.topl.nodeView.CleanupWorker.RunCleanup
import co.topl.nodeView.MempoolAuditor.CleanupDone
import co.topl.settings.{AppContext, AppSettings}
import co.topl.utils.NetworkType.NetworkPrefix
import co.topl.utils.{Logging, TimeProvider}

import scala.concurrent.duration._

/**
 * Controls mempool cleanup workflow. Watches NodeView events and delegates
 * mempool cleanup task to [[CleanupWorker]] when needed.
 * Adapted from ErgoPlatform available at https://github.com/ergoplatform/ergo
 */
class MempoolAuditor(
  nodeViewHolderRef:     akka.actor.typed.ActorRef[NodeViewHolder.ReceivableMessage],
  networkControllerRef:  ActorRef,
  settings:              AppSettings,
  appContext:            AppContext
)(implicit timeProvider: TimeProvider)
    extends Actor
    with Logging {

  implicit val networkPrefix: NetworkPrefix = appContext.networkType.netPrefix

  override val supervisorStrategy: OneForOneStrategy =
    OneForOneStrategy(maxNrOfRetries = 5, withinTimeRange = 1.minute) {
      case _: ActorKilledException => Stop
      case _: DeathPactException   => Stop
      case e: ActorInitializationException =>
        log.warn(s"Cleanup worker failed during initialization with: $e")
        Stop
      case e: Exception =>
        log.warn(s"Cleanup worker failed with: $e")
        context become awaiting // turn ctx into awaiting mode if worker failed
        Restart
    }

  private val worker: ActorRef =
    context.actorOf(
      Props(new CleanupWorker(nodeViewHolderRef, settings))
    )

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[NodeViewHolder.Events.SemanticallySuccessfulModifier[_]])
    log.info(s"${Console.YELLOW}MemPool Auditor transitioning to the operational state${Console.RESET}")
  }

  override def postRestart(reason: Throwable): Unit = {
    log.error(s"Mempool auditor actor restarted due to ${reason.getMessage}", reason)
    super.postRestart(reason)
  }

  override def postStop(): Unit = {
    logger.info("Mempool auditor stopped")
    super.postStop()
  }

  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////// ACTOR MESSAGE HANDLING //////////////////////////////

  override def receive: Receive =
    awaiting orElse nonsense

  private def awaiting: Receive = {
    case NodeViewHolder.Events.SemanticallySuccessfulModifier(_: Block) |
        NodeViewHolder.Events.SemanticallySuccessfulModifier(_: BlockHeader) =>
      initiateCleanup()
    case _ => nonsense
  }

  private def working: Receive = {
    case CleanupDone(ids) =>
      log.info("Cleanup done. Switching to awaiting mode")
      rebroadcastTransactions(ids)
      context become awaiting

    case _ => nonsense
  }

  private def nonsense: Receive = { case nonsense: Any =>
    log.warn(s"Got unexpected input $nonsense from ${sender()}")
  }

  ////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////// METHOD DEFINITIONS ////////////////////////////////

  private def initiateCleanup(): Unit = {
    log.info("Initiating cleanup. Switching to working mode")
    worker ! RunCleanup
    context become working // ignore other triggers until work is done
  }

  private def rebroadcastTransactions(transactions: Seq[Transaction.TX]): Unit =
    if (transactions.nonEmpty) {
      log.debug("Rebroadcasting transactions")
      transactions.foreach { tx =>
        log.info(s"Rebroadcasting $tx")
        val msg = Message(
          new InvSpec(settings.network.maxInvObjects),
          Right(InvData(Transaction.modifierTypeId, Seq(tx.id))),
          None
        )

        networkControllerRef ! SendToNetwork(msg, Broadcast)
      }
    } else {
      log.debug("No transactions to rebroadcast")
    }
}

////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////// COMPANION SINGLETON ////////////////////////////////

object MempoolAuditor {

  val actorName = "mempoolAuditor"

  case class CleanupDone(toBeBroadcast: Seq[Transaction.TX])

}

////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////// ACTOR REF HELPER //////////////////////////////////

object MempoolAuditorRef {

  def props(
    settings:              AppSettings,
    appContext:            AppContext,
    nodeViewHolderRef:     akka.actor.typed.ActorRef[NodeViewHolder.ReceivableMessage],
    networkControllerRef:  ActorRef
  )(implicit timeProvider: TimeProvider): Props =
    Props(new MempoolAuditor(nodeViewHolderRef, networkControllerRef, settings, appContext))

}
