package co.topl.eventtree

import cats.data.OptionT
import cats.implicits._
import cats.effect.kernel.Sync
import co.topl.algebras.{ClockAlgebra, Store}
import ClockAlgebra.implicits._
import cats.Monad
import co.topl.codecs.bytes.ByteCodec
import co.topl.codecs.bytes.implicits._
import co.topl.models.Box.Values
import co.topl.models.utility.Ratio
import co.topl.models._
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Path

trait ConsensusState[F[_]] {
  def registrationOf(address:  TaktikosAddress): F[Option[Box.Values.TaktikosRegistration]]
  def relativeStakeOf(address: TaktikosAddress): F[Option[Ratio]]
  def createDelta(block:       BlockV2): F[ConsensusStateDelta]
  def applyDelta(delta:        ConsensusStateDelta): F[Unit]
  def unapplyDelta(delta:      ConsensusStateDelta): F[Unit]
}

sealed abstract class ConsensusStateDelta

case class NormalConsensusDelta(
  registrations:   List[(TaktikosAddress, Box.Values.TaktikosRegistration)],
  deregistrations: List[TaktikosAddress],
  stakeChanges:    List[(TaktikosAddress, Option[Ratio], Option[Ratio])] // (address, old, new)
)

case class EpochCrossingDelta(
  normalConsensusDelta: NormalConsensusDelta,
  newEpoch:             Epoch,
  appliedDegistrations: List[
    (TaktikosAddress, Box.Values.TaktikosRegistration, Epoch)
  ], // includes expired and deregistered
  appliedRegistrations: List[(TaktikosAddress, Box.Values.TaktikosRegistration, Epoch)],
  appliedStakeChanges:  List[(TaktikosAddress, Option[Ratio], Option[Ratio])] // (address, old, new)
)

object ConsensusState {

  object LevelDB {
    private val dbFactory = new Iq80DBFactory

    private val CurrentEpochKey = "ce".getBytes(StandardCharsets.UTF_8)

    def make[F[_]: Sync](path: Path, clock: ClockAlgebra[F]): F[ConsensusState[F]] =
      Sync[F]
        .blocking(dbFactory.open(path.toFile, new Options))
        .map(db =>
          new ConsensusState[F] {

            def registrationOf(address: TaktikosAddress): F[Option[Values.TaktikosRegistration]] =
              OptionT(
                Sync[F].blocking(
                  Option(db.get((Bytes("r".getBytes(StandardCharsets.UTF_8)) ++ keyifyAddress(address)).toArray))
                )
              )
                .map(byteArray => Bytes(byteArray).decoded[Box.Values.TaktikosRegistration])
                .value

            def relativeStakeOf(address: TaktikosAddress): F[Option[Ratio]] =
              OptionT(
                Sync[F].blocking(
                  Option(db.get((Bytes("s".getBytes(StandardCharsets.UTF_8)) ++ keyifyAddress(address)).toArray))
                )
              )
                .map(byteArray => Bytes(byteArray).decoded[Ratio])
                .value

            def createDelta(block: BlockV2): F[ConsensusStateDelta] =
              for {
                previousEpoch <- Sync[F].blocking(db.get(CurrentEpochKey)).map(ByteBuffer.wrap(_).getLong)
                newEpoch      <- clock.epochOf(block.headerV2.slot)
                delta <- Monad[F].ifElseM(
                  (previousEpoch === newEpoch).pure[F] -> Sync[F].delay(
                    NormalConsensusDelta(
                      block.blockBodyV2.transactions.flatMap(
                        _.consensusOutputs.collect { case Transaction.ConsensusOutputs.Registration(commitment) =>
                          Box.Values.TaktikosRegistration(commitment)
                        }.toList
                      )
                    )
                  )
                )
              } yield ???

            def applyDelta(delta: ConsensusStateDelta): F[Unit] = ???

            def unapplyDelta(delta: ConsensusStateDelta): F[Unit] = ???

            private def keyifyAddress(address: TaktikosAddress): Bytes = ???
          }
        )

  }
}
