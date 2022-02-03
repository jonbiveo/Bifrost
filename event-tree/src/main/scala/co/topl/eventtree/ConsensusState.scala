package co.topl.eventtree

import cats.data.OptionT
import cats.effect.kernel.Sync
import cats.implicits._
import cats.{Monad, MonadThrow}
import co.topl.algebras.ClockAlgebra
import co.topl.algebras.ClockAlgebra.implicits._
import co.topl.codecs.bytes.implicits._
import co.topl.models._
import co.topl.models.utility.HasLength.instances.bigIntLength
import co.topl.models.utility.{Ratio, Sized}
import co.topl.typeclasses.implicits._
import org.iq80.leveldb.impl.Iq80DBFactory
import org.iq80.leveldb.{DB, Options}

import java.nio.charset.StandardCharsets
import java.nio.file.{Path, Paths}
import scala.jdk.CollectionConverters._

trait ConsensusState[F[_]] {
  def registrationOf(address:  TaktikosAddress): F[Option[Box.Values.TaktikosRegistration]]
  def relativeStakeOf(address: TaktikosAddress): F[Option[Ratio]]
  def createDelta(block:       BlockV2): F[ConsensusStateDelta]
  def applyDelta(delta:        ConsensusStateDelta): F[Unit]
  def unapplyDelta(delta:      ConsensusStateDelta): F[Unit]
}

sealed abstract class ConsensusStateDelta

case class NormalConsensusDelta(
  blockId:         TypedIdentifier,
  registrations:   List[(TaktikosAddress, Box.Values.TaktikosRegistration)],
  deregistrations: List[TaktikosAddress],
  stakeChanges:    List[(TaktikosAddress, Option[Int128], Option[Int128])] // (address, old, new)
) extends ConsensusStateDelta

case class EpochCrossingDelta(
  blockId:                TypedIdentifier,
  normalConsensusDelta:   NormalConsensusDelta,
  newEpoch:               Epoch,
  appliedExpirations:     List[(TaktikosAddress, Box.Values.TaktikosRegistration, Epoch)],
  appliedDeregistrations: List[(TaktikosAddress, Box.Values.TaktikosRegistration, Epoch)],
  appliedRegistrations:   List[(TaktikosAddress, Box.Values.TaktikosRegistration, Epoch)],
  appliedStakeChanges:    List[(TaktikosAddress, Option[Int128], Option[Int128])] // (address, old, new)
) extends ConsensusStateDelta

object ConsensusState {

  object LevelDB {
    private val dbFactory = new Iq80DBFactory

    private val CurrentEpochKey = "ce".getBytes(StandardCharsets.UTF_8)
    private val TotalStakeKey = "ts".getBytes(StandardCharsets.UTF_8)

    private val prBytes = "pr".getBytes(StandardCharsets.UTF_8)
    private val pdBytes = "pd".getBytes(StandardCharsets.UTF_8)
    private val eBytes = "e".getBytes(StandardCharsets.UTF_8)
    private val rBytes = "e".getBytes(StandardCharsets.UTF_8)

    def make[F[_]: Sync](path: Path, clock: ClockAlgebra[F]): F[ConsensusState[F]] = {
      def openDb(name: String): F[DB] =
        Sync[F].blocking(dbFactory.open(Paths.get(path.toString, name).toFile, new Options))
      (
        openDb("meta").flatTap(db =>
          Sync[F].blocking {
            if (Option(db.get(CurrentEpochKey)).isEmpty) db.put(CurrentEpochKey, 0L.bytes.toArray)
            if (Option(db.get(TotalStakeKey)).isEmpty) db.put(TotalStakeKey, Int128(10000L).bytes.toArray)
          }
        ),
        openDb("activeRegistrations"),
        openDb("activeStake"),
        openDb("alphaRegistrations"),
        openDb("alphaDeregistrations"),
        openDb("alphaStakeChanges"),
        openDb("betaRegistrations"),
        openDb("betaDeregistrations"),
        openDb("betaStakeChanges")
      ).mapN(
        (
          metaDb:                 DB,
          activeRegistrationsDb:  DB,
          activeStakeDb:          DB,
          alphaRegistrationsDb:   DB,
          alphaDeregistrationsDb: DB,
          alphaStakeChangesDb:    DB,
          betaRegistrationsDb:    DB,
          betaDeregistrationsDb:  DB,
          betaStakeChangesDb:     DB
        ) =>
          new Impl(
            metaDb,
            activeRegistrationsDb,
            activeStakeDb,
            alphaRegistrationsDb,
            alphaDeregistrationsDb,
            alphaStakeChangesDb,
            betaRegistrationsDb,
            betaDeregistrationsDb,
            betaStakeChangesDb,
            clock
          )
      )
    }

    private class Impl[F[_]: Sync](
      metaDb:                 DB,
      activeRegistrationsDb:  DB,
      activeStakeDb:          DB,
      alphaRegistrationsDb:   DB,
      alphaDeregistrationsDb: DB,
      alphaStakeChangesDb:    DB,
      betaRegistrationsDb:    DB,
      betaDeregistrationsDb:  DB,
      betaStakeChangesDb:     DB,
      clock:                  ClockAlgebra[F]
    ) extends ConsensusState[F] {

      def registrationOf(address: TaktikosAddress): F[Option[Box.Values.TaktikosRegistration]] =
        OptionT(
          Sync[F].blocking(
            Option(
              activeRegistrationsDb.get((Bytes(rBytes) ++ address.bytes).toArray)
            )
          )
        )
          .map(byteArray => Bytes(byteArray).decoded[Box.Values.TaktikosRegistration])
          .value

      private def registrationEpochOf(address: TaktikosAddress): F[Option[Long]] =
        OptionT(
          Sync[F].blocking(
            Option(
              activeRegistrationsDb.get((Bytes(eBytes) ++ address.bytes).toArray)
            )
          )
        )
          .map(byteArray => Bytes(byteArray).decoded[Long])
          .value

      def relativeStakeOf(address: TaktikosAddress): F[Option[Ratio]] =
        stakeOf(address)
          .semiflatMap(stake => currentTotalStake.map(ts => Ratio(stake.data, ts.data)))
          .value

      def createDelta(block: BlockV2): F[ConsensusStateDelta] =
        for {
          previousEpoch <- currentEpoch
          newEpoch      <- clock.epochOf(block.headerV2.slot)
          delta <- Monad[F].ifElseM[ConsensusStateDelta](
            (previousEpoch === newEpoch).pure[F] -> calculateNormalDelta(block).widen[ConsensusStateDelta]
          )(calculateEpochCrossingDelta(block, newEpoch).widen[ConsensusStateDelta])
        } yield delta

      def applyDelta(delta: ConsensusStateDelta): F[Unit] =
        delta match {
          case n: NormalConsensusDelta => applyNormalDelta(n)
          case e: EpochCrossingDelta   => applyCrossingDelta(e)
        }

      def unapplyDelta(delta: ConsensusStateDelta): F[Unit] =
        delta match {
          case n: NormalConsensusDelta => unapplyNormalDelta(n)
          case e: EpochCrossingDelta   => unapplyCrossingDelta(e)
        }

      private def applyNormalDelta(delta: NormalConsensusDelta): F[Unit] =
        for {
          (pendingRegistrationsDb, pendingDeregistrationsDb, pendingStakeChangesDb) <- nMinus0Databases
          _ <- Sync[F].blocking {
            val b = pendingRegistrationsDb.createWriteBatch()
            delta.registrations.foreach { case (addr, box) => b.put(addr.bytes.toArray, box.bytes.toArray) }
            pendingRegistrationsDb.write(b)
            b.close()
          }
          _ <- Sync[F].blocking {
            val b = pendingDeregistrationsDb.createWriteBatch()
            delta.deregistrations.foreach(addr => b.put(addr.bytes.toArray, Array.empty))
            pendingDeregistrationsDb.write(b)
            b.close()
          }
          _ <- Sync[F].blocking {
            val b = pendingStakeChangesDb.createWriteBatch()
            delta.stakeChanges.foreach { case (addr, old, newStake) =>
              b.put((addr, old, newStake).bytes.toArray, Array.emptyByteArray)
            }
            pendingStakeChangesDb.write(b)
            b.close()
          }
        } yield ()

      private def applyCrossingDelta(delta: EpochCrossingDelta): F[Unit] =
        for {
          _                                                                               <- applyDelta(delta.normalConsensusDelta)
          (n1PendingRegistrationsDb, n1PendingDeregistrationsDb, n1PendingStakeChangesDb) <- nMinus1Databases
          _                                                                               <- Sync[F].blocking(metaDb.put(CurrentEpochKey, delta.newEpoch.bytes.toArray))
          _ <- Sync[F].blocking {
            val batch1 = activeRegistrationsDb.createWriteBatch()
            (delta.appliedExpirations.map(_._1) ++ delta.appliedDeregistrations.map(_._1))
              .foreach { a =>
                batch1.delete(eBytes ++ a.bytes.toArray)
                batch1.delete(rBytes ++ a.bytes.toArray)
              }
            delta.appliedRegistrations.foreach { case (address, box, epoch) =>
              batch1.put(eBytes ++ address.bytes.toArray, epoch.bytes.toArray)
              batch1.put(rBytes ++ address.bytes.toArray, box.bytes.toArray)
            }
            activeRegistrationsDb.write(batch1)
            batch1.close()
          }
          _ <- Sync[F].blocking {
            val batch2 = activeStakeDb.createWriteBatch()
            delta.appliedStakeChanges.foreach {
              case (address, _, None) =>
                batch2.delete(address.bytes.toArray)
              case (address, _, Some(value)) =>
                batch2.put(address.bytes.toArray, value.bytes.toArray)
            }
            activeStakeDb.write(batch2)
            batch2.close()
            clearDb(n1PendingRegistrationsDb)
            clearDb(n1PendingDeregistrationsDb)
            clearDb(n1PendingStakeChangesDb)
          }
        } yield ()

      private def unapplyNormalDelta(delta: NormalConsensusDelta): F[Unit] =
        for {
          (pendingRegistrationsDb, pendingDeregistrationsDb, pendingStakeChangesDb) <- nMinus0Databases
          _ <- Sync[F].blocking {
            val b = pendingRegistrationsDb.createWriteBatch()
            delta.registrations.foreach { case (addr, box) => b.put(addr.bytes.toArray, box.bytes.toArray) }
            pendingRegistrationsDb.write(b)
            b.close()
          }
          _ <- Sync[F].blocking {
            val b = pendingDeregistrationsDb.createWriteBatch()
            delta.deregistrations.foreach(addr => b.put(addr.bytes.toArray, Array.empty))
            pendingDeregistrationsDb.write(b)
            b.close()
          }
          _ <- Sync[F].blocking {
            val b = pendingStakeChangesDb.createWriteBatch()
            delta.stakeChanges.foreach { case (addr, old, newStake) =>
              b.put((addr, old, newStake).bytes.toArray, Array.emptyByteArray)
            }
            pendingStakeChangesDb.write(b)
            b.close()
          }
        } yield ()

      private def unapplyCrossingDelta(delta: EpochCrossingDelta): F[Unit] =
        for {
          _                                                                               <- unapplyDelta(delta.normalConsensusDelta)
          (n0PendingRegistrationsDb, n0PendingDeregistrationsDb, n0PendingStakeChangesDb) <- nMinus0Databases
          _                                                                               <- Sync[F].blocking(metaDb.put(CurrentEpochKey, (delta.newEpoch - 1).bytes.toArray))
          _ <- Sync[F].blocking {
            val batch = activeRegistrationsDb.createWriteBatch()
            (delta.appliedExpirations ++ delta.appliedDeregistrations).foreach { case (a, box, epoch) =>
              batch.put(eBytes ++ a.bytes.toArray, epoch.bytes.toArray)
              batch.put(rBytes ++ a.bytes.toArray, box.bytes.toArray)
            }
            delta.appliedRegistrations.foreach { case (address, _, _) =>
              batch.delete(eBytes ++ address.bytes.toArray)
              batch.delete(rBytes ++ address.bytes.toArray)
            }
            activeRegistrationsDb.write(batch)
            batch.close()
          }
          _ <- Sync[F].blocking {
            val batch = activeStakeDb.createWriteBatch()
            delta.appliedStakeChanges.foreach {
              case (address, None, _) =>
                batch.delete(address.bytes.toArray)
              case (address, Some(value), _) =>
                batch.put(address.bytes.toArray, value.bytes.toArray)
            }
            activeStakeDb.write(batch)
            batch.close()
          }
          _ <- Sync[F].blocking {
            clearDb(n0PendingRegistrationsDb)
            clearDb(n0PendingDeregistrationsDb)
            clearDb(n0PendingStakeChangesDb)
            delta.appliedDeregistrations.foreach { case (a, _, _) =>
              n0PendingDeregistrationsDb.put(a.bytes.toArray, Array.emptyByteArray)
            }
            delta.appliedRegistrations.foreach { case (a, box, _) =>
              n0PendingRegistrationsDb.put(a.bytes.toArray, box.bytes.toArray)
            }
            delta.appliedStakeChanges.foreach { case (a, old, newStake) =>
              n0PendingStakeChangesDb.put((a, old, newStake).bytes.toArray, Array.emptyByteArray)
            }
          }
        } yield ()

      private def currentEpoch: F[Epoch] =
        Sync[F]
          .blocking(metaDb.get(CurrentEpochKey))
          .map(Bytes(_).decoded[Long])

      private def currentTotalStake: F[Int128] =
        Sync[F]
          .blocking(metaDb.get(TotalStakeKey))
          .map(byteArray => Bytes(byteArray).decoded[Int128])

      private def stakeOf(address: TaktikosAddress): OptionT[F, Int128] =
        OptionT(
          Sync[F].blocking(
            Option(activeStakeDb.get(address.bytes.toArray))
          )
        )
          .map(byteArray => Bytes(byteArray).decoded[Int128])

      private def calculateNormalDelta(block: BlockV2): F[NormalConsensusDelta] =
        Sync[F].defer(
          block.blockBodyV2.transactions
            .flatMap(_.coinOutputs.collect { case a: Transaction.CoinOutputs.Arbit => a }.toList)
            .toList
            .traverse { case Transaction.CoinOutputs.Arbit(_, taktikosAddress, value) =>
              stakeOf(taktikosAddress).fold[(TaktikosAddress, Option[Int128], Option[Int128])](
                (taktikosAddress, None, value.some)
              )(stake => (taktikosAddress, stake.some, Some(Sized.maxUnsafe(stake.data + value.data))))
            }
            .map(stakeChanges =>
              NormalConsensusDelta(
                block.headerV2.id,
                block.blockBodyV2.transactions
                  .flatMap(
                    _.consensusOutputs.collect { case Transaction.ConsensusOutputs.Registration(address, commitment) =>
                      address -> Box.Values.TaktikosRegistration(commitment)
                    }.toList
                  )
                  .toList,
                block.blockBodyV2.transactions
                  .flatMap(
                    _.consensusOutputs.collect { case Transaction.ConsensusOutputs.Deregistration(address) =>
                      address
                    }.toList
                  )
                  .toList,
                stakeChanges
              )
            )
        )

      private def expiringRegistrations(newEpoch: Epoch): F[List[TaktikosAddress]] =
        Sync[F]
          .blocking {
            val it = activeRegistrationsDb.iterator()
            it.seek(eBytes)
            val res = it.asScala
              .takeWhile(_.getKey.startsWith(eBytes))
              .filter(kv => Bytes(kv.getValue).decoded[Long] < (newEpoch - 18L)) // TODO: Don't hardcode
              .toList
            it.close()
            res
          }
          .map(_.map(e => Bytes(e.getKey.drop(eBytes.length)).decoded[TaktikosAddress]))

      private def calculateEpochCrossingDelta(block: BlockV2, newEpoch: Epoch): F[EpochCrossingDelta] =
        Sync[F].defer(
          for {
            normalDelta       <- calculateNormalDelta(block)
            addressesToExpire <- expiringRegistrations(newEpoch)
            registrationsToExpire <- addressesToExpire.traverse(a =>
              (
                a.pure[F],
                getOrNoSuchElement(registrationOf(a), a.show),
                getOrNoSuchElement(registrationEpochOf(a), a.show)
              ).tupled
            )
            deregistrationsToApply <- nMinus1Databases
              .map(_._2)
              .flatMap(pendingDeregistrationsDb =>
                Sync[F].blocking {
                  val it = pendingDeregistrationsDb.iterator()
                  it.seekToFirst()
                  val res = it.asScala.map(_.getKey).toList
                  it.close()
                  res
                }
              )
              .map(_.map(Bytes(_).decoded[TaktikosAddress]))
              .flatMap(
                _.traverse(a =>
                  (
                    a.pure[F],
                    getOrNoSuchElement(registrationOf(a), a.show),
                    getOrNoSuchElement(registrationEpochOf(a), a.show)
                  ).tupled
                )
              )
            registrationsToApply <- nMinus1Databases
              .map(_._1)
              .flatMap(pendingRegistrationsDb =>
                Sync[F].blocking {
                  val it = pendingRegistrationsDb.iterator()
                  it.seekToFirst()
                  val res = it.asScala.toList
                  it.close()
                  res
                }
              )
              .map(
                _.map(e =>
                  (
                    Bytes(e.getKey).decoded[TaktikosAddress],
                    Bytes(e.getValue).decoded[Box.Values.TaktikosRegistration],
                    newEpoch - 2
                  )
                )
              )
            stakeChangesToApply <- nMinus1Databases
              .map(_._3)
              .flatMap(pendingStakeChangesDb =>
                Sync[F].blocking {
                  val it = pendingStakeChangesDb.iterator()
                  it.seekToFirst()
                  val res = it.asScala.map(_.getKey).toList
                  it.close()
                  res
                }
              )
              .map(
                _.map(
                  Bytes(_).decoded[(TaktikosAddress, Option[Int128], Option[Int128])]
                )
              )
          } yield EpochCrossingDelta(
            block.headerV2.id,
            normalDelta,
            newEpoch,
            registrationsToExpire,
            deregistrationsToApply,
            registrationsToApply,
            stakeChangesToApply
          )
        )

      private def nMinus0Databases: F[(DB, DB, DB)] =
        currentEpoch.map(e =>
          (e % 2) match {
            case 0 => (alphaRegistrationsDb, alphaDeregistrationsDb, alphaStakeChangesDb)
            case 1 => (betaRegistrationsDb, betaDeregistrationsDb, betaStakeChangesDb)
          }
        )

      private def nMinus1Databases: F[(DB, DB, DB)] =
        currentEpoch.map(e =>
          (e % 2) match {
            case 0 => (betaRegistrationsDb, betaDeregistrationsDb, betaStakeChangesDb)
            case 1 => (alphaRegistrationsDb, alphaDeregistrationsDb, alphaStakeChangesDb)
          }
        )

      private def currentPendingRegistrations: F[List[(TaktikosAddress, Box.Values.TaktikosRegistration, Epoch)]] =
        Sync[F]
          .blocking {
            val it = activeRegistrationsDb.iterator()
            // pr = pending registration
            it.seek(prBytes)
            val r = it.asScala.takeWhile(_.getKey.startsWith(prBytes)).toList
            it.close()
            r
          }
          .map(r =>
            r.map { e =>
              val address = Bytes(e.getKey.drop(prBytes.length)).decoded[TaktikosAddress]
              val (registration, epoch) = Bytes(e.getValue).decoded[(Box.Values.TaktikosRegistration, Epoch)]
              (address, registration, epoch)
            }
          )

      private def currentPendingDeregistrations: F[List[(TaktikosAddress, Epoch)]] =
        Sync[F]
          .blocking {
            val it = activeRegistrationsDb.iterator()
            // pr = pending registration
            it.seek(pdBytes)
            val r = it.asScala.takeWhile(_.getKey.startsWith(pdBytes)).toList
            it.close()
            r
          }
          .map(r =>
            r.map { e =>
              val address = Bytes(e.getKey.drop(pdBytes.length)).decoded[TaktikosAddress]
              val epoch = Bytes(e.getValue).decoded[Epoch]
              (address, epoch)
            }
          )

      private def clearDb(db: DB): Unit = {
        val it = db.iterator()
        it.asScala.foreach(e => db.delete(e.getKey))
        it.close()
      }

      private def getOrNoSuchElement[T](f: F[Option[T]], key: => String): F[T] =
        OptionT(f).getOrElseF(MonadThrow[F].raiseError[T](new NoSuchElementException(key)))
    }

  }
}
