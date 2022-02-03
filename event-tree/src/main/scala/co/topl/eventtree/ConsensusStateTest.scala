package co.topl.eventtree

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import cats.effect.{IO, IOApp}
import co.topl.commoninterpreters.{AkkaSchedulerClock, LevelDbStore}
import co.topl.models._
import co.topl.typeclasses.implicits._
import co.topl.codecs.bytes.implicits._
import co.topl.codecs.bytes.ByteCodec.ops._

import java.nio.file.Files
import cats.implicits._
import co.topl.codecs.bytes.{ByteCodec, Reader, Writer}
import co.topl.typeclasses.Identifiable

import scala.concurrent.duration._

object ConsensusStateTest extends IOApp.Simple {
  import ConsensusStateCodecs._

  type F[A] = IO[A]

  implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "ConsensusStateTest")

  val blocks: List[BlockV2] =
    List(
      {
        val header = BlockHeaderV2()
      }
    )

  def run: IO[Unit] =
    for {
      eventStore <- LevelDbStore.Eval.make[F, BlockV2](Files.createTempDirectory("event-store-"))
      deltaStore <- LevelDbStore.Eval.make[F, ConsensusStateDelta](Files.createTempDirectory("delta-store-"))
      tree       <- ParentChildTree.FromRef.make[F, TypedIdentifier]
      _ <- blocks.foldLeftM(()) { case (_, block) =>
        (
          eventStore.put(block.headerV2.id, block),
          tree.associate(block.headerV2.id, block.headerV2.parentHeaderId)
        ).tupled.void
      }
      clock = AkkaSchedulerClock.Eval.make[F](100.milli, 720L)
      eventTree <- EventTree.Eval.make[F, BlockV2, ConsensusState[F], ConsensusStateDelta](
        initialState = ConsensusState.LevelDB.make[F](Files.createTempDirectory("consensus-state"), clock),
        initialEventId = blocks.head.headerV2.parentHeaderId.pure[F],
        eventAsDelta = (block, state) => state.createDelta(block),
        applyDelta = (state, delta) => state.applyDelta(delta).map(_ => state),
        unapplyDelta = (state, delta) => state.unapplyDelta(delta).map(_ => state),
        eventStore = eventStore,
        deltaStore = deltaStore,
        parentChildTree = tree
      )
      _ <- eventTree.stateAt(blocks.last.headerV2.id)
    } yield ()
}

object ConsensusStateCodecs {

  implicit val consensusStateDeltaIdentifiable: Identifiable[ConsensusStateDelta] =
    new Identifiable[ConsensusStateDelta] {

      def idOf(t: ConsensusStateDelta): TypedIdentifier = t match {
        case n: NormalConsensusDelta => n.blockId
        case e: EpochCrossingDelta   => e.blockId
      }

      def typePrefix: TypePrefix = 1: Byte
    }

  implicit val consensusDeltaCodec: ByteCodec[ConsensusStateDelta] =
    new ByteCodec[ConsensusStateDelta] {

      def encode(t: ConsensusStateDelta, writer: Writer): Unit =
        t match {
          case n: NormalConsensusDelta =>
            writer.put(0)
            ByteCodec[Seq[(TaktikosAddress, Box.Values.TaktikosRegistration)]].encode(n.registrations, writer)
            ByteCodec[Seq[TaktikosAddress]].encode(n.deregistrations, writer)
            ByteCodec[Seq[(TaktikosAddress, Option[Int128], Option[Int128])]].encode(n.stakeChanges, writer)
          case e: EpochCrossingDelta =>
            writer.put(1)
            encode(e.normalConsensusDelta, writer)
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

      def decode(reader: Reader): ConsensusStateDelta =
        reader.getByte() match {
          case 0 =>
            NormalConsensusDelta(
              ByteCodec[TypedIdentifier].decode(reader),
              ByteCodec[Seq[(TaktikosAddress, Box.Values.TaktikosRegistration)]].decode(reader).toList,
              ByteCodec[Seq[TaktikosAddress]].decode(reader).toList,
              ByteCodec[Seq[(TaktikosAddress, Option[Int128], Option[Int128])]].decode(reader).toList
            )
          case 1 =>
            EpochCrossingDelta(
              ByteCodec[TypedIdentifier].decode(reader),
              NormalConsensusDelta(
                ByteCodec[TypedIdentifier].decode(reader),
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
