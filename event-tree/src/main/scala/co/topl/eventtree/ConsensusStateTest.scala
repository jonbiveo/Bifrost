package co.topl.eventtree

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import cats.data.{Chain, NonEmptyChain}
import cats.effect.{Async, IO, IOApp}
import cats.implicits._
import co.topl.codecs.bytes.implicits._
import co.topl.codecs.bytes.{ByteCodec, Reader, Writer}
import co.topl.commoninterpreters.{AkkaSchedulerClock, LevelDbStore}
import co.topl.models._
import co.topl.models.utility.HasLength.instances.bytesLength
import co.topl.models.utility.Sized
import co.topl.typeclasses.BlockGenesis
import co.topl.typeclasses.implicits._

import java.nio.file.Files
import scala.collection.immutable.ListMap
import scala.concurrent.duration._

object ConsensusStateTest extends IOApp.Simple {
  import ConsensusStateCodecs._

  type F[A] = IO[A]

  implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "ConsensusStateTest")

  private def childOf(blockV2: BlockV2, transactions: Seq[Transaction], address: TaktikosAddress): BlockV2 = {
    val header = BlockHeaderV2(
      parentHeaderId = blockV2.headerV2.id,
      parentSlot = blockV2.headerV2.slot,
      txRoot = Sized.strictUnsafe(Bytes.fill(32)(1: Byte)),
      bloomFilter = Sized.strictUnsafe(Bytes.fill(256)(1: Byte)),
      timestamp = System.currentTimeMillis(),
      height = blockV2.headerV2.height + 1,
      slot = blockV2.headerV2.slot + 1,
      eligibilityCertificate = EligibilityCertificate(
        Proofs.Knowledge.VrfEd25519(Sized.strictUnsafe(Bytes.fill(80)(1: Byte))),
        VerificationKeys.VrfEd25519(Sized.strictUnsafe(Bytes.fill(32)(1: Byte))),
        Sized.strictUnsafe(Bytes.fill(32)(1: Byte)),
        Sized.strictUnsafe(Bytes.fill(32)(1: Byte))
      ),
      operationalCertificate = OperationalCertificate(
        VerificationKeys.KesProduct(Sized.strictUnsafe(Bytes.fill(32)(1: Byte)), 0),
        Proofs.Knowledge.KesProduct(
          Proofs.Knowledge.KesSum(
            VerificationKeys.Ed25519(Sized.strictUnsafe(Bytes.fill(32)(1: Byte))),
            Proofs.Knowledge.Ed25519(Sized.strictUnsafe(Bytes.fill(64)(1: Byte))),
            Vector.empty
          ),
          Proofs.Knowledge.KesSum(
            VerificationKeys.Ed25519(Sized.strictUnsafe(Bytes.fill(32)(1: Byte))),
            Proofs.Knowledge.Ed25519(Sized.strictUnsafe(Bytes.fill(64)(1: Byte))),
            Vector.empty
          ),
          Sized.strictUnsafe(Bytes.fill(32)(1: Byte))
        ),
        VerificationKeys.Ed25519(Sized.strictUnsafe(Bytes.fill(32)(1: Byte))),
        Proofs.Knowledge.Ed25519(Sized.strictUnsafe(Bytes.fill(64)(1: Byte)))
      ),
      metadata = None,
      address = address
    )

    val body =
      BlockBodyV2(header.id, transactions)

    BlockV2(header, body)
  }

  val addresses = List(
    TaktikosAddress(
      Sized.strictUnsafe(Bytes.fill(32)(1: Byte)),
      VerificationKeys.Ed25519(Sized.strictUnsafe(Bytes.fill(32)(1: Byte))),
      Proofs.Knowledge.Ed25519(Sized.strictUnsafe(Bytes.fill(64)(1: Byte)))
    )
  )

  val dionAddresses = List(
    DionAddress(NetworkPrefix(1: Byte), TypedEvidence(1: Byte, Sized.strictUnsafe(Bytes.fill(32)(1: Byte))))
  )

  val commonChain =
    LazyList
      .unfold(List(BlockGenesis.apply(Nil).value)) { blocks =>
        val next = childOf(
          blocks.last,
          List(
            Transaction(
              inputs = ListMap.empty,
              feeOutput = None,
              coinOutputs = NonEmptyChain(
                Transaction.CoinOutputs.Arbit(dionAddresses(0), addresses(0), Int128(50)): Transaction.CoinOutput
              ),
              consensusOutputs = Chain(),
              fee = Int128(0),
              timestamp = System.currentTimeMillis(),
              data = None,
              minting = false
            )
          ),
          addresses(0)
        )
        Some((next, blocks :+ next))
      }
      .take(30)
      .toList

  val tineA =
    LazyList
      .unfold(commonChain) { blocks =>
        val next = childOf(
          blocks.last,
          List(
            Transaction(
              inputs = ListMap.empty,
              feeOutput = None,
              coinOutputs = NonEmptyChain(
                Transaction.CoinOutputs.Arbit(dionAddresses(0), addresses(0), Int128(40)): Transaction.CoinOutput
              ),
              consensusOutputs = Chain(),
              fee = Int128(0),
              timestamp = System.currentTimeMillis(),
              data = None,
              minting = false
            )
          ),
          addresses(0)
        )
        Some((next, blocks :+ next))
      }
      .take(50)
      .toList

  val tineB =
    LazyList
      .unfold(commonChain) { blocks =>
        val next = childOf(
          blocks.last,
          List(
            Transaction(
              inputs = ListMap.empty,
              feeOutput = None,
              coinOutputs = NonEmptyChain(
                Transaction.CoinOutputs.Arbit(dionAddresses(0), addresses(0), Int128(60)): Transaction.CoinOutput
              ),
              consensusOutputs = Chain(),
              fee = Int128(0),
              timestamp = System.currentTimeMillis(),
              data = None,
              minting = false
            )
          ),
          addresses(0)
        )
        Some((next, blocks :+ next))
      }
      .take(50)
      .toList

  val blocks: List[BlockV2] =
    commonChain ++ tineA ++ tineB

  def run: IO[Unit] =
    (for {
      eventStore <- LevelDbStore.Eval.make[F, BlockV2](Files.createTempDirectory("event-store-"))
      deltaStore <- LevelDbStore.Eval.make[F, ConsensusStateUnapply](Files.createTempDirectory("delta-store-"))
      tree       <- ParentChildTree.FromRef.make[F, TypedIdentifier]
      _ <- blocks.foldLeftM(()) { case (_, block) =>
        (
          eventStore.put(block.headerV2.id, block),
          tree.associate(block.headerV2.id, block.headerV2.parentHeaderId)
        ).tupled.void
      }
      clock = AkkaSchedulerClock.Eval.make[F](100.milli, 5L)
      eventTree <- EventSourcedState.OfTree.make[F, BlockV2, ConsensusState[F], ConsensusStateUnapply](
        initialState = ConsensusState.LevelDB.make[F](Files.createTempDirectory("consensus-state"), clock),
        initialEventId = blocks.head.headerV2.parentHeaderId.pure[F],
        eventAsUnapplyEvent = (block, state) => state.createUnapply(block),
        applyEvent = (state, delta) => state.applyEvent(delta).as(state),
        unapplyEvent = (state, delta) => state.unapplyEvent(delta).as(state),
        eventStore = eventStore,
        unapplyEventStore = deltaStore,
        parentChildTree = tree
      )
      sTineA     <- eventTree.stateAt(tineA.last.headerV2.id)
      stakeTineA <- sTineA.relativeStakeOf(addresses(0))
      _ = println(stakeTineA)

      sTineB     <- eventTree.stateAt(tineB.last.headerV2.id)
      stakeTineB <- sTineB.relativeStakeOf(addresses(0))
      _ = println(stakeTineB)

      sTineC     <- eventTree.stateAt(commonChain(14).headerV2.id)
      stakeTineC <- sTineC.relativeStakeOf(addresses(0))
      _ = println(stakeTineC)

    } yield ()).guarantee(
      Async[F].delay(system.terminate()).flatMap(_ => Async[F].fromFuture(Async[F].delay(system.whenTerminated)).void)
    )
}

object ConsensusStateCodecs {

  implicit val consensusDeltaCodec: ByteCodec[ConsensusStateUnapply] =
    new ByteCodec[ConsensusStateUnapply] {

      def encode(t: ConsensusStateUnapply, writer: Writer): Unit =
        t match {
          case n: NormalConsensusUnapply =>
            writer.put(0)
            ByteCodec[Seq[(TaktikosAddress, Box.Values.TaktikosRegistration)]].encode(n.registrations, writer)
            ByteCodec[Seq[TaktikosAddress]].encode(n.deregistrations, writer)
            ByteCodec[Seq[(TaktikosAddress, Option[Int128], Option[Int128])]].encode(n.stakeChanges, writer)
          case e: EpochCrossingUnapply =>
            writer.put(1)
            ByteCodec[Seq[(TaktikosAddress, Box.Values.TaktikosRegistration)]]
              .encode(e.normalConsensusDelta.registrations, writer)
            ByteCodec[Seq[TaktikosAddress]].encode(e.normalConsensusDelta.deregistrations, writer)
            ByteCodec[Seq[(TaktikosAddress, Option[Int128], Option[Int128])]]
              .encode(e.normalConsensusDelta.stakeChanges, writer)
            writer.putLong(e.newEpoch)
            ByteCodec[Seq[(TaktikosAddress, Box.Values.TaktikosRegistration, Epoch)]]
              .encode(e.appliedExpirations, writer)
            ByteCodec[Seq[(TaktikosAddress, Box.Values.TaktikosRegistration, Epoch)]]
              .encode(e.appliedDeregistrations, writer)
            ByteCodec[Seq[(TaktikosAddress, Box.Values.TaktikosRegistration, Epoch)]]
              .encode(e.appliedRegistrations, writer)
            ByteCodec[Seq[(TaktikosAddress, Option[Int128], Option[Int128])]]
              .encode(e.appliedStakeChanges, writer)
        }

      def decode(reader: Reader): ConsensusStateUnapply =
        reader.getByte() match {
          case 0 =>
            NormalConsensusUnapply(
              ByteCodec[Seq[(TaktikosAddress, Box.Values.TaktikosRegistration)]].decode(reader).toList,
              ByteCodec[Seq[TaktikosAddress]].decode(reader).toList,
              ByteCodec[Seq[(TaktikosAddress, Option[Int128], Option[Int128])]].decode(reader).toList
            )
          case 1 =>
            EpochCrossingUnapply(
              NormalConsensusUnapply(
                ByteCodec[Seq[(TaktikosAddress, Box.Values.TaktikosRegistration)]].decode(reader).toList,
                ByteCodec[Seq[TaktikosAddress]].decode(reader).toList,
                ByteCodec[Seq[(TaktikosAddress, Option[Int128], Option[Int128])]].decode(reader).toList
              ),
              reader.getLong(),
              ByteCodec[Seq[(TaktikosAddress, Box.Values.TaktikosRegistration, Epoch)]].decode(reader).toList,
              ByteCodec[Seq[(TaktikosAddress, Box.Values.TaktikosRegistration, Epoch)]].decode(reader).toList,
              ByteCodec[Seq[(TaktikosAddress, Box.Values.TaktikosRegistration, Epoch)]].decode(reader).toList,
              ByteCodec[Seq[(TaktikosAddress, Option[Int128], Option[Int128])]].decode(reader).toList
            )
        }
    }
}
