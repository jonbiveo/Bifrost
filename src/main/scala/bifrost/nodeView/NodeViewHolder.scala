package bifrost.nodeView

import akka.actor.{ActorRef, ActorSystem, Props}
import bifrost.crypto.PrivateKey25519Companion
import bifrost.history.History
import bifrost.mempool.MemPool
import bifrost.modifier.block.{Block, BlockCompanion}
import bifrost.modifier.box.proposition.PublicKey25519Proposition
import bifrost.modifier.box.ArbitBox
import bifrost.modifier.transaction.bifrostTransaction.{ArbitTransfer, GenericTransaction, PolyTransfer, Transaction}
import bifrost.modifier.transaction.serialization.TransactionCompanion
import bifrost.modifier.ModifierId
import bifrost.network.BifrostSyncInfo
import bifrost.nodeView.NodeViewModifier.ModifierTypeId
import bifrost.settings.AppSettings
import bifrost.state.State
import bifrost.utils.{Logging, NetworkTimeProvider}
import bifrost.utils.serialization.BifrostSerializer
import bifrost.wallet.Wallet
import scorex.crypto.encode.Base58

class NodeViewHolder(appSettings: AppSettings, timeProvider: NetworkTimeProvider)
  extends GenericNodeViewHolder[Transaction, Block] {

  override lazy val settings: AppSettings = appSettings

  override type SI = BifrostSyncInfo
  override type HIS = History
  override type MS = State
  override type VL = Wallet
  override type MP = MemPool

  lazy val modifierCompanions: Map[ModifierTypeId, BifrostSerializer[_ <: NodeViewModifier]] =
    Map(Block.modifierTypeId -> BlockCompanion,
    GenericTransaction.modifierTypeId -> TransactionCompanion)

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    reason.printStackTrace()
    System.exit(100) // this actor shouldn't be restarted at all so kill the whole app if that happened
  }

  /**
    * Restore a local view during a node startup. If no any stored view found
    * (e.g. if it is a first launch of a node) None is to be returned
    */
  override def restoreState(): Option[NodeView] = {
    if (Wallet.exists(settings)) {
      val x = History.readOrGenerate(settings)
      Some(
        (
          x,
          State.readOrGenerate(settings, callFromGenesis = true, x),
          Wallet.readOrGenerate(settings, 1),
          MemPool.emptyPool
        )
      )
    } else None
  }

  //noinspection ScalaStyle
  override protected def genesisState: NodeView = {
    NodeViewHolder.initializeGenesis(settings)
  }
}

object NodeViewHolder extends Logging {
  type HIS = History
  type MS = State
  type VL = Wallet
  type MP = MemPool

  type NodeView = (HIS, MS, VL, MP)

  //noinspection ScalaStyle
  def initializeGenesis(settings: AppSettings): NodeView = {
    val GenesisAccountsNum = 50
    val GenesisBalance = 100000000L

    //propositions with wallet seed genesisoo, genesiso1, ..., genesis48, genesis49
    val icoMembers: IndexedSeq[PublicKey25519Proposition] =
      IndexedSeq(
        "6sYyiTguyQ455w2dGEaNbrwkAWAEYV1Zk6FtZMknWDKQ", "7BDhJv6Wh2MekgJLvQ98ot9xiw5x3N4b3KipURdrW8Ge",
        "Ei8oY3eg5vM26QUBhyFiAdPN1C23RJEV9irrykNmSAFV", "8LNhm5QagL88sWggvJKGDiZ5bBCG4ajV7R6vAKz4czA9",
        "EakiCSw1rfmL5DFTPNmSJZEEAEGtTp3DN12wVPJVsURS", "AEQ8bZRuAxAp8DV9VZnTrSudGPdNyzY2HXjPBCGy8igf",
        "DSL6bvb6j1v6SnvKjqc6fJWdsRjZ85YboH8FkzonUPiT", "419sTmWKAXb5526naQ93xJZL4YAYtpVkbLmzMb6k5X9m",
        "GydWCS1GwExoDNuEiW6fBLYr7cs4vwdLpk1kzDeKHq6A", "G8xVDYow1YcSb4cuAHwcpYSEKxFpYwC9GqYChMvbCWn5",
        "9E4F53GSXMPqwuPWEVoUQe9B1z4A8v9Y6tAQdKK779km", "5XtHBDxXCudA38FJnoWm1BVG8aV67AiQKnPuwYbWZCb3",
        "8Sp3v5vtYtkM9Z2K2B7PuZbWmWQE9bfiUFCvkmsdauGj", "8XTUXeLiHPbMNXedWQh5xHQtq4xUHU3pZZGqRQzC2eyj",
        "ftqJXjSXrWQXmumNVVaRiNB7TZuCy4GCvz9V4GJGhAv", "GMAYWvbBmssCr55m9bcq8cKzfczSKKxidtVrukBM1KFN",
        "3nFprwUuqGH9BpvJMQeCb5AwHdaXuxKin1WSxWc9PTkY", "HfYNA96cGebFGgAhGUbxvRJYyLFchQJZpJTQMXztE6gZ",
        "EPbo8xRWARg2znJAqevKnQMskxnemmCdimPiVFhr8eLd", "4pygr1SPEe5KbU1R8XgMmYaW7YfTH818wd113mF6bhsP",
        "52gwahUytUXv7wfKs4j6YeKeepc38sYsUi4jp4z4jVym", "Hi3Q1ZQbD2zztq6ajm5yUKfFccxmj3yZn79GUjhFvPSW",
        "G1yK5iwPQKNXnqU4Drg83et3gKhRW5CogqiekKEYDcrt", "Hf8XcEAVMCiWbu376rGS48FhwH5NgteivfsTsvX1XpbA",
        "3FAskwxrbqiX2KGEnFPuD3z89aubJvvdxZTKHCrMFjxQ", "GgahaaNBaHRnyUtvEu3k7N5BnW3dvhVCXyxMP6uijdhh",
        "7R9waVeAKuHKNQY5uTYBp6zNLNo6wSDvj9XfQCyRWmDF", "E4AoFDANgDFL83gTS6A7kjWbLmqWcPr6DqEgMG7cqU18",
        "AEkuiLFdudYmUwZ9dSa64rakqUgJZf6pKFFwwm6CZFQz", "3QzGZvvTQbcUdhd5BL9ofEK3GdzbmqUnYA1pYTAdVY44",
        "EjpGvdZETt3SuZpcuwKvZS4jgWCockDHzFQLoeYNW4R", "C85c1uMAiHKDgcqxF6EaiCGQyWgQEYATbpo8M7XEnx3R",
        "8V5y1CSC1gCGD1jai3ns5FJNW7tAzf7BGd4iwmBv7V44", "CJ9udTDT61ckSHMd6YNpjeNdsN2fGwmJ6Ry6YERXmGa7",
        "7eboeRCeeBCFwtzPtB4vKPnaYMPL52BjfiEpqSRWfkgx", "E3JJCTMouTys5BSwFyHTV3Ht55mYWfNUAverrNaVo4jE",
        "9PLHPwnHyA5jf6GPGRjJt7HNd93rw4gWTBi7LBNL4Wwt", "2YM2FQ4HfMiV3LFkiwop2xFznbPVEHbhahVvcrhfZtXq",
        "3oTzYXjwdr684FUzaJEVVuXBztysNgR8M8iV9QykaM9C", "J6bgGpwDMqKFrde2mpdS6dasRyn9WFV6jKgWAkHSN91q",
        "4wtQpa1BVgAt9CA4FUuHZHCYGBYtvudPqa1sAddfAPii", "DaSXwzkAU2WfH39zxMfuXpExsVfKk6JzeYbdW9RLiXr4",
        "6BtXEZE6GcxtEtSLAHXkE3mkcTG1u8WuoQxZG7R8BR5X", "39Z9VaCAeqoWajHyku29argf7zmVqs2vVJM8zYe7YLXy",
        "7focbpSdsNNE4x9h7eyXSkvXE6dtxsoVyZMpTpuThLoH", "CBdnTL6C4A7nsacxCP3VL3TqUokEraFy49ckQ196KU46",
        "CfvbDC8dxGeLXzYhDpNpCF2Ar9Q5LKs8QrfcMYAV59Lt", "GFseSi5squ8GRRkj6RknbGj9Hyz82HxKkcn8NKW1e5CF",
        "FuTHJNKaPTneEYRkjKAC3MkSttvAC7NtBeb2uNGS8mg3", "5hhPGEFCZM2HL6DNKs8KvUZAH3wC47rvMXBGftw9CCA5"
      ).map(s => PublicKey25519Proposition(Base58.decode(s).get))

    val genesisAccount = PrivateKey25519Companion.generateKeys("genesis".getBytes)
    val genesisAccountPriv = genesisAccount._1

    val genesisTxs = Seq(ArbitTransfer(
      IndexedSeq(genesisAccountPriv -> 0),
      icoMembers.map(_ -> GenesisBalance),
      0L,
      0L,
      "")) ++ Seq(PolyTransfer(
      IndexedSeq(genesisAccountPriv -> 0),
      icoMembers.map(_ -> GenesisBalance),
      0L,
      0L,
      ""))
    log.debug(s"Initialize state with transaction ${genesisTxs.head} with boxes ${genesisTxs.head.newBoxes}")
    assert(icoMembers.length == GenesisAccountsNum)
    //YT - commented out below assertion since transaction id generation was changed
    //assert(Base58.encode(genesisTxs.head.id) == "5dJRukdd7sw7cmc8vwSnwbVggWLPV4VHYsZt7AQcFW3B", Base58.encode(genesisTxs.head.id))

    val genesisBox = ArbitBox(genesisAccountPriv.publicImage, 0, GenesisBalance)

    val genesisBlock = Block.create(ModifierId(History.GenesisParentId), 0L, genesisTxs, genesisBox, genesisAccountPriv, 0L, settings.forgingSettings.version)

    var history = History.readOrGenerate(settings)
    history = history.append(genesisBlock).get._1

    val gs = State.genesisState(settings, Seq(genesisBlock), history)
    val gw = Wallet.genesisWallet(settings, Seq(genesisBlock))

    assert(!settings.walletSeed.startsWith("genesis") || gw.boxes().flatMap(_.box match {
      case ab: ArbitBox => Some(ab.value)
      case _ => None
    }).sum >= GenesisBalance)

    gw.boxes().foreach(b => assert(gs.closedBox(b.box.id).isDefined))

    (history, gs, gw, MemPool.emptyPool)
  }
}

object NodeViewHolderRef {

  def props(settings: AppSettings,
            timeProvider: NetworkTimeProvider): Props = Props(new NodeViewHolder(settings, timeProvider))

  def apply(settings: AppSettings,
            timeProvider: NetworkTimeProvider)(implicit system: ActorSystem): ActorRef =
    system.actorOf(props(settings, timeProvider))

  def apply(name: String,
            settings: AppSettings,
            timeProvider: NetworkTimeProvider)(implicit system: ActorSystem): ActorRef =
    system.actorOf(props(settings, timeProvider), name)
}