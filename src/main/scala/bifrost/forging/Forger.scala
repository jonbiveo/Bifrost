package bifrost.forging

import java.time.Instant

import akka.actor._
import bifrost.crypto.{FastCryptographicHash, PrivateKey25519}
import bifrost.history.History
import bifrost.mempool.MemPool
import bifrost.modifier.block.Block
import bifrost.modifier.block.Block.Version
import bifrost.modifier.box.ArbitBox
import bifrost.modifier.box.proposition.PublicKey25519Proposition
import bifrost.modifier.transaction.bifrostTransaction.{CoinbaseTransaction, Transaction}
import bifrost.nodeView.CurrentView
import bifrost.settings.AppSettings
import bifrost.state.State
import bifrost.utils.Logging
import bifrost.wallet.Wallet
import com.google.common.primitives.Longs

import scala.concurrent.ExecutionContext
import scala.util.Try

class Forger(settings: AppSettings, viewHolderRef: ActorRef)
            (implicit ec: ExecutionContext) extends Actor with Logging {

  // Import the types of messages this actor can RECEIVE
  import bifrost.forging.Forger.ReceivableMessages._

  // Import the types of messages this actor can send
  import bifrost.nodeView.GenericNodeViewHolder.ReceivableMessages.{GetDataFromCurrentView, LocallyGeneratedModifier}

  val TransactionsInBlock = 100 //should be a part of consensus, but for our app is okay
  //private val infQ = ActorSystem("infChannel").actorOf(Props[InflationQuery], "infQ") // inflation query actor
  private val isForging = settings.forgingSettings.tryForging
  private val MaxTarget: Long = Long.MaxValue

  override def preStart(): Unit = {
    if (isForging) {
      context.system.scheduler.scheduleOnce(settings.forgingSettings.blockGenerationDelay)(self ! StartForging)
      context become readyToForge
    }
  }

////////////////////////////////////////////////////////////////////////////////////
////////////////////////////// ACTOR MESSAGE HANDLING //////////////////////////////

  // ----------- CONTEXT && MESSAGE PROCESSING FUNCTIONS
  override def receive: Receive = {
    case StartForging =>
      log.info(s"Forger: Received a START signal while forging disabled")

    case _ => nonsense
  }

  private def readyToForge: Receive = {
    case StartForging =>
      log.info("No Better Neighbor. Forger starts forging now.")
      viewHolderRef ! GetDataFromCurrentView(actOnCurrentView)
      context become activeForging

    case StopForging =>
      log.warn(s"Forger: Received a STOP signal while not forging. Signal ignored")

    case _ => nonsense
  }

  private def activeForging: Receive = {
    case StartForging =>
      log.warn(s"Forger: Received a START signal while forging. Signal ignored")

    case StopForging =>
      log.info(s"Forger: Received a stop signal. Forging will terminate after this trial")
      context become readyToForge

    case CurrentView(h: History, s: State, w: Wallet, m: MemPool) =>
      tryForging(h, s, w, m)

    case _ => nonsense
  }

  private def nonsense: Receive = {
    case nonsense: Any =>
      log.warn(s"Forger (in context ${context.toString}): got unexpected input $nonsense from ${sender()}")
  }

////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////// METHOD DEFINITIONS ////////////////////////////////
  /**
   * wrapper function to encapsulate the returned CurrentView
   *
   * @param view the view returned from NodeViewHolder
   * @return
   */
  def actOnCurrentView(view: CurrentView[History, State, Wallet, MemPool]): CurrentView[History, State, Wallet, MemPool] = view

  private def tryForging(h: History, s: State, w: Wallet, m: MemPool): Unit = {
      log.info(s"${Console.CYAN}Trying to generate a new block, chain length: ${h.height}${Console.RESET}")
      log.info("chain difficulty: " + h.difficulty)

      val boxes: Seq[ArbitBox] = w.boxes().filter(_.box match {
        case a: ArbitBox => s.closedBox(a.id).isDefined
        case _ => false
      }).map(_.box.asInstanceOf[ArbitBox])

      val boxKeys = boxes.flatMap(b => w.secretByPublicImage(b.proposition).map(s => (b, s)))

      val parent = h.bestBlock
      log.debug(s"Trying to generate block on top of ${parent.id} with balance " +
        s"${boxKeys.map(_._1.value).sum}")

      val adjustedTarget = calcAdjustedTarget(h.difficulty, parent, settings.forgingSettings.targetBlockTime.length)

      iteration(parent, boxKeys, pickTransactions(m, s, parent, (h, s, w, m)).get, adjustedTarget, settings.forgingSettings.version) match {
        case Some(block) =>
          log.debug(s"Locally generated block: $block")
          viewHolderRef !
            LocallyGeneratedModifier[Block](block)
        case None =>
          log.debug(s"Failed to generate block")
      }

      context.system.scheduler.scheduleOnce(settings.forgingSettings.blockGenerationDelay)(viewHolderRef ! GetDataFromCurrentView(actOnCurrentView))
    }

  def pickTransactions(memPool: MemPool,
                       state: State,
                       parent: Block,
                       view: (History, State, Wallet, MemPool)
                      ): Try[Seq[Transaction]] = Try {
    lazy val to: PublicKey25519Proposition = PublicKey25519Proposition(view._3.secrets.head.publicImage.pubKeyBytes)
    val infVal = 0 //Await.result(infQ ? view._1.height, Duration.Inf).asInstanceOf[Long]
    lazy val CB = CoinbaseTransaction.createAndApply(view._3, IndexedSeq((to, infVal)), parent.id.hashBytes).get
    val regTxs = memPool.take(TransactionsInBlock).foldLeft(Seq[Transaction]()) { case (txSoFar, tx) =>
      val txNotIncluded = tx.boxIdsToOpen.forall(id => !txSoFar.flatMap(_.boxIdsToOpen).exists(_ sameElements id))
      val invalidBoxes = tx.newBoxes.forall(b ⇒ state.closedBox(b.id).isEmpty)
      val txValid = state.validate(tx)
      if (txValid.isFailure) {
        log.debug(s"${Console.RED}Invalid Unconfirmed transaction $tx. Removing transaction${Console.RESET}")
        txValid.failed.get.printStackTrace()
        memPool.remove(tx)
      }
      if(!invalidBoxes) {
        memPool.remove(tx)
      }

      if (txValid.isSuccess && txNotIncluded) txSoFar :+ tx else txSoFar
    }
    CB +: regTxs
  }

  def calcAdjustedTarget(difficulty: Long,
                         parent: Block,
                         targetBlockDelay: Long): BigInt = {
    val target: Double = MaxTarget.toDouble / difficulty.toDouble
    val timedelta = Instant.now().toEpochMilli - parent.timestamp
    BigDecimal(target * timedelta.toDouble / targetBlockDelay.toDouble).toBigInt()
  }

  def hit(lastBlock: Block)(box: ArbitBox): Long = {
    val h = FastCryptographicHash(lastBlock.bytes ++ box.bytes)
    Longs.fromByteArray((0: Byte) +: h.take(7))
  }

  def iteration(parent: Block,
                boxKeys: Seq[(ArbitBox, PrivateKey25519)],
                txsToInclude: Seq[Transaction],
                target: BigInt,
                version: Version): Option[Block] = {

    log.debug("in the iteration function")
    val successfulHits = boxKeys.map { boxKey =>
      val h = hit(parent)(boxKey._1)
      //log.debug(s"Hit value: $h")
      (boxKey, h)
    }.filter(t => BigInt(t._2) < BigInt(t._1._1.value) * target)

    log.debug(s"Successful hits: ${successfulHits.size}")
    successfulHits.headOption.map { case (boxKey, _) =>
      if (txsToInclude.head.asInstanceOf[CoinbaseTransaction].newBoxes.nonEmpty) {
        Block.create(parent.id, Instant.now().toEpochMilli, txsToInclude, boxKey._1, boxKey._2,
          txsToInclude.head.asInstanceOf[CoinbaseTransaction].newBoxes.head.asInstanceOf[ArbitBox].value, version) // inflation val
      }
      else {
        Block.create(parent.id, Instant.now().toEpochMilli, txsToInclude, boxKey._1, boxKey._2, 0, version)
      }
    }
  }

}

////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////// COMPANION SINGLETON ////////////////////////////////

object Forger {

  object ReceivableMessages {

    case object StartForging

    case object StopForging

  }

}

////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////// ACTOR REF HELPER //////////////////////////////////

object ForgerRef {
  def props(settings: AppSettings, nodeViewHolderRef: ActorRef)(implicit ec: ExecutionContext): Props =
    Props(new Forger(settings, nodeViewHolderRef))

  def apply(settings: AppSettings, nodeViewHolderRef: ActorRef)(implicit system: ActorSystem, ec: ExecutionContext): ActorRef =
    system.actorOf(props(settings, nodeViewHolderRef))

  def apply(name: String, settings: AppSettings, nodeViewHolderRef: ActorRef)(implicit system: ActorSystem, ec: ExecutionContext): ActorRef =
    system.actorOf(props(settings, nodeViewHolderRef), name)
}