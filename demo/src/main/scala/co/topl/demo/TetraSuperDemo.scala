package co.topl.demo

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.scaladsl.{Flow, Keep, Source}
import akka.util.{ByteString, Timeout}
import cats.data.{Store => _, _}
import cats.effect.implicits._
import cats.effect.kernel.Sync
import cats.effect.unsafe.{IORuntime, IORuntimeConfig}
import cats.effect.{Async, ExitCode, IO, IOApp}
import cats.implicits._
import co.topl.algebras._
import co.topl.blockchain.Blockchain
import co.topl.catsakka._
import co.topl.codecs.bytes.tetra.instances._
import co.topl.codecs.bytes.typeclasses.implicits._
import co.topl.consensus.LeaderElectionValidation.VrfConfig
import co.topl.consensus._
import co.topl.crypto.hash.{Blake2b256, Blake2b512}
import co.topl.crypto.signing.{Curve25519, Ed25519, Ed25519VRF, ExtendedEd25519, KesProduct}
import co.topl.interpreters._
import co.topl.ledger.interpreters._
import co.topl.models._
import co.topl.models.utility.HasLength.instances._
import co.topl.models.utility.Lengths._
import co.topl.models.utility._
import co.topl.networking.p2p.{DisconnectedPeer, LocalPeer, Locations, SimulatedGeospatialDelayFlow}
import co.topl.numerics.{ExpInterpreter, Log1pInterpreter}
import co.topl.typeclasses._
import co.topl.typeclasses.implicits._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.net.InetSocketAddress
import java.nio.file.{Files, Paths}
import java.security.SecureRandom
import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

/**
 * Runs multiple independent DemoProgram instances, each with their own storage
 * and networking.  The instances share the same JVM, but they still communicate
 * over TCP and they each bind to an independent port on localhost.
 */
object TetraSuperDemo extends IOApp {

  // Configuration Data
  implicit private val vrfConfig: VrfConfig =
    VrfConfig(lddCutoff = 80, precision = 40, baselineDifficulty = Ratio(1, 20), amplitude = Ratio(2, 5))

  private val OperationalPeriodLength = 180L
  private val OperationalPeriodsPerEpoch = 3L
  private val ChainSelectionKLookback = 180L
  private val EpochLength = ChainSelectionKLookback * 3
  private val SlotDuration = 200.milli

  require(
    EpochLength % OperationalPeriodLength === 0L,
    "EpochLength must be evenly divisible by OperationalPeriodLength"
  )

  private val ChainSelectionSWindow = 200L

  private val KesKeyHeight = (9, 9)

  private def computeStakers(count: Int, random: Random) = List.tabulate(count) { idx =>
    implicit val ed25519Vrf: Ed25519VRF = Ed25519VRF.precomputed()
    implicit val ed25519: Ed25519 = new Ed25519
    implicit val kesProduct: KesProduct = new KesProduct
    val seed = Sized.strictUnsafe[Bytes, Lengths.`32`.type](Bytes(random.nextBytes(32)))

    val (_, poolVK) = new Ed25519().deriveKeyPairFromSeed(seed)

    val (stakerVrfKey, _) =
      ed25519Vrf.deriveKeyPairFromSeed(seed)

    val (kesKey, _) =
      kesProduct.createKeyPair(seed = seed.data, height = KesKeyHeight, 0)

    val stakerRegistration: Box.Values.Registrations.Operator =
      Box.Values.Registrations.Operator(
        vrfCommitment = kesProduct.sign(
          kesKey,
          new Blake2b256().hash(ed25519Vrf.getVerificationKey(stakerVrfKey).immutableBytes, poolVK.bytes.data).data
        )
      )

    Staker(Ratio(1, count), stakerVrfKey, kesKey, stakerRegistration, StakingAddresses.Operator(poolVK))
  }

  private val genesisTransaction =
    Transaction(
      inputs = Chain.empty,
      outputs = Chain(
        Transaction.Output(
          FullAddress(
            NetworkPrefix(1),
            Propositions.Contextual.HeightLock(1L).spendingAddress,
            StakingAddresses.Operator(VerificationKeys.Ed25519(Sized.strictUnsafe(Bytes.fill(32)(0: Byte)))),
            Proofs.Knowledge.Ed25519(Sized.strictUnsafe(Bytes.fill(64)(0: Byte)))
          ),
          Box.Values.Poly(Sized.maxUnsafe(BigInt(10_000L))),
          minting = true
        )
      ),
      chronology = Transaction.Chronology(0L, 0L, Long.MaxValue),
      None
    )

  private val genesis =
    BlockGenesis(List(genesisTransaction)).value

  // Actor system initialization

  implicit private val system: ActorSystem[_] =
    ActorSystem(Behaviors.empty, "TetraDemo")

  override val runtime: IORuntime = AkkaCatsRuntime(system).runtime
  override val runtimeConfig: IORuntimeConfig = AkkaCatsRuntime(system).ioRuntimeConfig

  // Interpreter initialization

  type F[A] = IO[A]

  private def makeClock(genesisTimestamp: Instant): ClockAlgebra[F] =
    SchedulerClock.Eval.make(SlotDuration, EpochLength, genesisTimestamp)

  implicit private val timeout: Timeout = Timeout(20.seconds)

  private def state(stakers: List[Staker]): F[ConsensusStateReader[F]] =
    NodeViewHolder.StaticData.make[F](
      stakers.map(staker => staker.address -> staker.relativeStake).toMap,
      stakers.map(staker => staker.address -> staker.registration).toMap
    )

  private val statsDir = Paths.get(".bifrost", "stats")
  Files.createDirectories(statsDir)

  private def statsInterpreter =
    StatsInterpreter.Eval.make[F](statsDir)

  // Program definition

  private val loggerColors = List(
    Console.MAGENTA,
    Console.BLUE,
    Console.YELLOW,
    Console.GREEN,
    Console.CYAN,
    Console.RED,
    Console.MAGENTA_B,
    Console.BLUE_B,
    Console.YELLOW_B,
    Console.GREEN_B,
    Console.CYAN_B,
    Console.RED_B
  )

  def run(args: List[String]): IO[ExitCode] = {
    for {
      random <- new Random(0L).pure[F]
      genesisTimestamp = Instant.now().plusSeconds(10)
      localPeers = List(
        (LocalPeer(parseAddress(port = 9090), Locations.NorthPole), "North1", parseAddress(port = 8090)),
        (LocalPeer(parseAddress(port = 9091), Locations.SouthPole), "South1", parseAddress(port = 8091)),
        (LocalPeer(parseAddress(port = 9092), Locations.AustinTxUSA), "Austin", parseAddress(port = 8092))
      )
      configurations = List(
        (
          localPeers(0)._1,
          Source(Nil: List[DisconnectedPeer]),
          true,
          localPeers(0)._2,
          localPeers(0)._3
        ),
        (
          localPeers(1)._1,
          Source(Nil: List[DisconnectedPeer]),
          true,
          localPeers(1)._2,
          localPeers(1)._3
        ),
        (
          localPeers(2)._1,
          Source
            .future(akka.pattern.after(3.seconds)(Future.unit))
            .flatMapConcat(_ =>
              Source(
                List(
                  DisconnectedPeer.tupled(LocalPeer.unapply(localPeers(0)._1).get),
                  DisconnectedPeer.tupled(LocalPeer.unapply(localPeers(1)._1).get)
                )
              )
            ),
          true,
          localPeers(2)._2,
          localPeers(2)._3
        )
      ).zipWithIndex
      stakers = computeStakers(3, random)
      _ <- configurations.parTraverse {
        case ((localPeer, remotes, stakingEnabled, stakerName, rpcAddress), stakerIndex) =>
          runInstance(
            localPeer,
            remotes,
            stakers,
            stakerName,
            stakerIndex,
            stakingEnabled,
            genesisTimestamp,
            rpcAddress.getHostName,
            rpcAddress.getPort
          )
      }
    } yield ()
  }
    .guarantee(
      Sync[F].delay(system.terminate()).flatMap(_ => Async[F].fromFuture(system.whenTerminated.pure[F])).void
    )
    .as(ExitCode.Success)

  private def runInstance(
    localPeer:        LocalPeer,
    remotes:          Source[DisconnectedPeer, _],
    stakers:          List[Staker],
    stakerName:       String,
    stakerIndex:      Int,
    stakingEnabled:   Boolean,
    genesisTimestamp: Instant,
    rpcHost:          String,
    rpcPort:          Int
  ) =
    Sync[F].defer(
      for {
        blake2b256Resource <- ActorPoolUnsafeResource.Eval.make[F, Blake2b256](new Blake2b256, _ => ())
        blake2b512Resource <- ActorPoolUnsafeResource.Eval.make[F, Blake2b512](new Blake2b512, _ => ())
        ed25519VRFResource <- ActorPoolUnsafeResource.Eval.make[F, Ed25519VRF](Ed25519VRF.precomputed(), _ => ())
        kesProductResource <- ActorPoolUnsafeResource.Eval.make[F, KesProduct](new KesProduct, _ => ())
        curve25519Resource <- ActorPoolUnsafeResource.Eval.make[F, Curve25519](new Curve25519, _ => ())
        ed25519Resource    <- ActorPoolUnsafeResource.Eval.make[F, Ed25519](new Ed25519, _ => ())
        extendedEd25519Resource <- ActorPoolUnsafeResource.Eval
          .make[F, ExtendedEd25519](ExtendedEd25519.precomputed(), _ => ())
        loggerColor = loggerColors(stakerIndex).toString
        implicit0(logger: Logger[F]) = Slf4jLogger
          .getLoggerFromName[F](s"node.${loggerColor}$stakerName${Console.RESET}")
          .withModifiedString(str => s"$loggerColor$str${Console.RESET}")
        _ <- Logger[F].info(
          show"Initializing node with genesis block id=${genesis.headerV2.id.asTypedBytes}" +
          show" and transactionIds=${genesis.blockBodyV2}"
        )
        slotDataStore    <- RefStore.Eval.make[F, TypedIdentifier, SlotData]()
        blockHeaderStore <- RefStore.Eval.make[F, TypedIdentifier, BlockHeaderV2]()
        blockBodyStore   <- RefStore.Eval.make[F, TypedIdentifier, BlockBodyV2]()
        transactionStore <- RefStore.Eval.make[F, TypedIdentifier, Transaction]()
        boxStateStore    <- RefStore.Eval.make[F, TypedIdentifier, NonEmptySet[Short]]()
        _                <- slotDataStore.put(genesis.headerV2.id, genesis.headerV2.slotData(Ed25519VRF.precomputed()))
        _                <- blockHeaderStore.put(genesis.headerV2.id, genesis.headerV2)
        _                <- blockBodyStore.put(genesis.headerV2.id, genesis.blockBodyV2)
        _                <- transactionStore.put(genesisTransaction.id, genesisTransaction)
        _                <- boxStateStore.put(genesisTransaction.id, NonEmptySet.one(0: Short))
        blockIdTree      <- BlockIdTree.make[F]
        _                <- blockIdTree.associate(genesis.headerV2.id, genesis.headerV2.parentHeaderId)
        blockHeightTreeStore        <- RefStore.Eval.make[F, Long, TypedIdentifier]()
        _                           <- blockHeightTreeStore.put(0, genesis.headerV2.parentHeaderId)
        blockHeightTreeUnapplyStore <- RefStore.Eval.make[F, TypedIdentifier, Long]()
        blockHeightTree <- BlockHeightTree
          .make[F](
            blockHeightTreeStore,
            genesis.headerV2.parentHeaderId,
            slotDataStore,
            blockHeightTreeUnapplyStore,
            blockIdTree
          )
        clock = makeClock(genesisTimestamp)
        etaCalculation <- EtaCalculation.Eval.make(
          slotDataStore.getOrRaise,
          clock,
          genesis.headerV2.eligibilityCertificate.eta,
          blake2b256Resource,
          blake2b512Resource
        )
        exp         <- ExpInterpreter.make[F](10000, 38)
        log1p       <- Log1pInterpreter.make[F](10000, 8)
        log1pCached <- Log1pInterpreter.makeCached[F](log1p)
        leaderElectionThreshold = LeaderElectionValidation.Eval.make[F](vrfConfig, blake2b512Resource, exp, log1pCached)
        consensusState <- state(stakers)
        underlyingHeaderValidation <- BlockHeaderValidation.Eval.make[F](
          etaCalculation,
          VrfRelativeStakeValidationLookup.Eval.make(consensusState, clock),
          leaderElectionThreshold,
          RegistrationLookup.Eval.make(consensusState, clock),
          ed25519VRFResource,
          kesProductResource,
          ed25519Resource,
          blake2b256Resource
        )
        cachedHeaderValidation <- BlockHeaderValidation.WithCache.make[F](underlyingHeaderValidation, blockHeaderStore)
        localChain <- LocalChain.Eval.make(
          genesis.headerV2.slotData(Ed25519VRF.precomputed()),
          ChainSelection
            .orderT[F](slotDataStore.getOrRaise, blake2b512Resource, ChainSelectionKLookback, ChainSelectionSWindow)
        )
        mempool <- Mempool.make[F](
          genesis.headerV2.id.asTypedBytes.pure[F],
          blockBodyStore.getOrRaise,
          transactionStore.getOrRaise,
          blockIdTree,
          clock,
          id => Logger[F].info(show"Expiring transaction id=$id"),
          Long.MaxValue,
          1000L
        )
        implicit0(networkRandom: Random) = new Random(new SecureRandom())
        boxState <- BoxState.make(
          genesis.headerV2.id.asTypedBytes.pure[F],
          blockBodyStore.getOrRaise,
          transactionStore.getOrRaise,
          blockIdTree,
          boxStateStore.pure[F]
        )
        transactionSyntaxValidation   <- TransactionSyntaxValidation.make[F]
        transactionSemanticValidation <- TransactionSemanticValidation.make[F](transactionStore.getOrRaise, boxState)
        transactionAuthorizationValidation <- TransactionAuthorizationValidation.make[F](
          blake2b256Resource,
          curve25519Resource,
          ed25519Resource,
          extendedEd25519Resource,
          slotDataStore.getOrRaise
        )
        bodySyntaxValidation <- BodySyntaxValidation.make[F](transactionStore.getOrRaise, transactionSyntaxValidation)
        bodySemanticValidation <- BodySemanticValidation.make[F](
          transactionStore.getOrRaise,
          boxState,
          boxState => TransactionSemanticValidation.make[F](transactionStore.getOrRaise, boxState)
        )
        bodyAuthorizationValidation <- BodyAuthorizationValidation.make[F](
          transactionStore.getOrRaise,
          transactionAuthorizationValidation
        )
        mintOpt <- OptionT
          .whenF(stakingEnabled)(
            DemoUtils.createMint[F](
              genesis,
              stakers(stakerIndex),
              clock,
              etaCalculation,
              leaderElectionThreshold,
              localChain,
              mempool,
              blockHeaderStore,
              transactionStore.getOrRaise,
              bodySyntaxValidation,
              bodySemanticValidation,
              bodyAuthorizationValidation,
              consensusState,
              ed25519VRFResource,
              kesProductResource,
              ed25519Resource,
              statsInterpreter,
              OperationalPeriodLength
            )
          )
          .value
        _ <- Blockchain
          .run[F](
            mintOpt,
            slotDataStore,
            blockHeaderStore,
            blockBodyStore,
            transactionStore,
            localChain,
            blockIdTree,
            blockHeightTree,
            cachedHeaderValidation,
            transactionSyntaxValidation,
            transactionSemanticValidation,
            bodySyntaxValidation,
            bodySemanticValidation,
            bodyAuthorizationValidation,
            mempool,
            ed25519VRFResource,
            localPeer,
            remotes.concat(Source.never),
            (peer, flow) => {
              val delayer =
                SimulatedGeospatialDelayFlow(
                  localPeer.coordinate,
                  peer.coordinate,
                  durationPerKilometer = 1.micros,
                  durationPerByte = 1.micros,
                  noise = 1.micros
                )
              Flow[ByteString].via(delayer).viaMat(flow)(Keep.right).via(delayer)
            },
            rpcHost,
            rpcPort
          )
      } yield ()
    )

  private def parseAddress(host: String = "localhost", port: Int) =
    InetSocketAddress.createUnresolved(host, port)
}
