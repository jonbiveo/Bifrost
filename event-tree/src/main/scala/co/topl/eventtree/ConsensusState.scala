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

  /**
   * Retrieve the registration for the given address.  The returned registration must be at least 2 epochs old
   */
  def registrationOf(address: TaktikosAddress): F[Option[Box.Values.TaktikosRegistration]]

  /**
   * Retrieve the relative stake for the given address.  The returned relative stake is based on transactions up to epoch N-2
   */
  def relativeStakeOf(address: TaktikosAddress): F[Option[Ratio]]

  /**
   * Turn a Block into a ConsensusDelta using the current state
   */
  def createUnapply(block: BlockV2): F[ConsensusStateUnapply]

  /**
   * Apply a ConsensusDelta to the current state
   */
  def applyEvent(block: BlockV2): F[Unit]

  /**
   * Undo a ConsensusDelta from the current state
   */
  def unapplyEvent(delta: ConsensusStateUnapply): F[Unit]
}

/**
 * Represents a change in ConsensusState, usually derived from a Block
 */
sealed abstract class ConsensusStateUnapply

/**
 * A ConsensusState change that takes place in the middle of an epoch.  We do not need to update any sort of "ledger"
 * for these deltas; instead, the transaction data is simply recorded for future use
 * @param registrations Any registrations that took place in this block
 * @param deregistrations Any deregistrations that took place in this block
 * @param stakeChanges Any stake/arbit changes that took place in this block
 */
case class NormalConsensusUnapply(
  registrations:   List[(TaktikosAddress, Box.Values.TaktikosRegistration)],
  deregistrations: List[TaktikosAddress],
  stakeChanges:    List[(TaktikosAddress, Option[Int128], Option[Int128])] // (address, old, new)
) extends ConsensusStateUnapply

/**
 * A ConsensusState change that takes place at the dawn of a new epoch.  This delta applies values from epoch N-1 to the
 * underlying state. The delta must track any registrations, deregistrations, expirations, and stake changes that are applied
 * as a result of this delta such that they can be unapplied later
 * @param normalConsensusDelta The normal changes that happened in this block
 * @param appliedExpirations Any registrations that expired at the conclusion of the previous epoch
 * @param appliedDeregistrations Any deregistrations that were applied by this delta
 * @param appliedRegistrations Any registrations that were applied by this delta
 * @param appliedStakeChanges Any stake changes that were applied by this delta
 */
case class EpochCrossingUnapply(
  normalConsensusDelta:   NormalConsensusUnapply,
  newEpoch:               Epoch,
  appliedExpirations:     List[(TaktikosAddress, Box.Values.TaktikosRegistration, Epoch)],
  appliedDeregistrations: List[(TaktikosAddress, Box.Values.TaktikosRegistration, Epoch)],
  appliedRegistrations:   List[(TaktikosAddress, Box.Values.TaktikosRegistration, Epoch)],
  appliedStakeChanges:    List[(TaktikosAddress, Option[Int128], Option[Int128])] // (address, old, new)
) extends ConsensusStateUnapply

object ConsensusState {

  /**
   * Implements a ConsensusState using several LevelDB databases
   */
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

    /**
     * The implementation class
     * @param metaDb a database for meta information, like CurrentEpoch and TotalStake
     * @param activeRegistrationsDb Registrations that are active and valid for validation purposes (i.e. registered 2+ epochs ago)
     * @param activeStakeDb Stake that is active and valid for validation purposes (i.e. transferred 2+ epochs ago)
     * @param alphaRegistrationsDb Stores pending registrations.  "alpha" means this database is either the N-1 or N-2 store, depending on the CurrentEpoch
     * @param alphaDeregistrationsDb Stores pending deregistrations.  "alpha" means this database is either the N-1 or N-2 store, depending on the CurrentEpoch
     * @param alphaStakeChangesDb Stores pending stake changes.  "alpha" means this database is either the N-1 or N-2 store, depending on the CurrentEpoch
     * @param betaRegistrationsDb Stores pending registrations.  "beta" means this database is either the N-1 or N-2 store, depending on the CurrentEpoch
     * @param betaDeregistrationsDb  Stores pending stake changes.  "beta" means this database is either the N-1 or N-2 store, depending on the CurrentEpoch
     * @param betaStakeChangesDb Stores pending stake changes.  "beta" means this database is either the N-1 or N-2 store, depending on the CurrentEpoch
     */
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

      def createUnapply(block: BlockV2): F[ConsensusStateUnapply] =
        for {
          previousEpoch <- currentEpoch
          newEpoch      <- clock.epochOf(block.headerV2.slot)
          delta <- Monad[F].ifElseM[ConsensusStateUnapply](
            (previousEpoch === newEpoch).pure[F] -> createNormalUnapply(block).widen[ConsensusStateUnapply]
          )(createEpochCrossingUnapply(block, newEpoch).widen[ConsensusStateUnapply])
        } yield delta

      def unapplyEvent(delta: ConsensusStateUnapply): F[Unit] =
        delta match {
          case n: NormalConsensusUnapply => unapplyNormalDelta(n)
          case e: EpochCrossingUnapply   => unapplyCrossingDelta(e)
        }

      private def unapplyNormalDelta(delta: NormalConsensusUnapply): F[Unit] =
        for {
          (pendingRegistrationsDb, pendingDeregistrationsDb, pendingStakeChangesDb) <- nMinus0Databases
          _ <- Sync[F].blocking {
            val b = pendingRegistrationsDb.createWriteBatch()
            delta.registrations.foreach { case (addr, _) => b.delete(addr.bytes.toArray) }
            pendingRegistrationsDb.write(b)
            b.close()
          }
          _ <- Sync[F].blocking {
            val b = pendingDeregistrationsDb.createWriteBatch()
            delta.deregistrations.foreach(addr => b.delete(addr.bytes.toArray))
            pendingDeregistrationsDb.write(b)
            b.close()
          }
          _ <- Sync[F].blocking {
            val b = pendingStakeChangesDb.createWriteBatch()
            delta.stakeChanges.foreach { case (addr, old, newStake) =>
              b.delete((addr, old, newStake).bytes.toArray)
            }
            pendingStakeChangesDb.write(b)
            b.close()
          }
        } yield ()

      private def unapplyCrossingDelta(delta: EpochCrossingUnapply): F[Unit] =
        for {
          _                                                                               <- unapplyNormalDelta(delta.normalConsensusDelta)
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

      def applyEvent(block: BlockV2): F[Unit] =
        Sync[F].defer(
          for {
            previousEpoch <- currentEpoch
            newEpoch      <- clock.epochOf(block.headerV2.slot)
            _ <- Monad[F].ifElseM[Unit](
              (previousEpoch === newEpoch).pure[F] -> Sync[F].defer(applyNormalEvent(block))
            )(Sync[F].defer(applyEpochCrossingEvent(block, newEpoch)))
          } yield ()
        )

      private def applyNormalEvent(block: BlockV2): F[Unit] =
        for {
          (pendingRegistrationsDb, pendingDeregistrationsDb, pendingStakeChangesDb) <- nMinus0Databases
          arbitOutputs = block.blockBodyV2.transactions
            .flatMap(_.coinOutputs.collect { case a: Transaction.CoinOutputs.Arbit => a }.toList)
            .toList
          stakeChanges <- arbitOutputs
            .traverse { case Transaction.CoinOutputs.Arbit(_, taktikosAddress, value) =>
              stakeOf(taktikosAddress).fold[(TaktikosAddress, Option[Int128], Option[Int128])](
                (taktikosAddress, None, value.some)
              )(stake => (taktikosAddress, stake.some, Some(Sized.maxUnsafe(stake.data + value.data))))
            }
          _ <- Sync[F].blocking {
            val b = pendingStakeChangesDb.createWriteBatch()
            stakeChanges.foreach { case (addr, old, newStake) =>
              b.put((addr, old, newStake).bytes.toArray, Array.emptyByteArray)
            }
            pendingStakeChangesDb.write(b)
            b.close()
          }

          registrations = block.blockBodyV2.transactions
            .flatMap(
              _.consensusOutputs.collect { case Transaction.ConsensusOutputs.Registration(address, commitment) =>
                address -> Box.Values.TaktikosRegistration(commitment)
              }.toList
            )
            .toList
          _ <- Sync[F].blocking {
            val b = pendingRegistrationsDb.createWriteBatch()
            registrations.foreach { case (addr, box) => b.put(addr.bytes.toArray, box.bytes.toArray) }
            pendingRegistrationsDb.write(b)
            b.close()
          }

          deregistrations = block.blockBodyV2.transactions
            .flatMap(
              _.consensusOutputs.collect { case Transaction.ConsensusOutputs.Deregistration(address) =>
                address
              }.toList
            )
            .toList

          _ <- Sync[F].blocking {
            val b = pendingDeregistrationsDb.createWriteBatch()
            deregistrations.foreach(addr => b.put(addr.bytes.toArray, Array.empty))
            pendingDeregistrationsDb.write(b)
            b.close()
          }
        } yield ()

      private def applyEpochCrossingEvent(block: BlockV2, newEpoch: Epoch): F[Unit] =
        for {
          (n1PendingRegistrationsDb, n1PendingDeregistrationsDb, n1PendingStakeChangesDb) <- nMinus1Databases
          addressesToExpire                                                               <- expiringRegistrations(newEpoch)
          registrationsToExpire <- addressesToExpire.traverse(a =>
            (
              a.pure[F],
              getOrNoSuchElement(registrationOf(a), a.show),
              getOrNoSuchElement(registrationEpochOf(a), a.show)
            ).tupled
          )
          deregistrationsToApply <-
            Sync[F]
              .blocking {
                val it = n1PendingRegistrationsDb.iterator()
                it.seekToFirst()
                val res = it.asScala.map(_.getKey).toList
                it.close()
                res
              }
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
          registrationsToApply <-
            Sync[F]
              .blocking {
                val it = n1PendingRegistrationsDb.iterator()
                it.seekToFirst()
                val res = it.asScala.toList
                it.close()
                res
              }
              .map(
                _.map(e =>
                  (
                    Bytes(e.getKey).decoded[TaktikosAddress],
                    Bytes(e.getValue).decoded[Box.Values.TaktikosRegistration],
                    newEpoch - 2
                  )
                )
              )
          stakeChangesToApply <-
            Sync[F]
              .blocking {
                val it = n1PendingStakeChangesDb.iterator()
                it.seekToFirst()
                val res = it.asScala.map(_.getKey).toList
                it.close()
                res
              }
              .map(
                _.map(
                  Bytes(_).decoded[(TaktikosAddress, Option[Int128], Option[Int128])]
                )
              )

          _ <- applyNormalEvent(block)
          _ <- Sync[F].blocking(metaDb.put(CurrentEpochKey, newEpoch.bytes.toArray))

          _ <- Sync[F].blocking {
            val activeRegistrationsBatch = activeRegistrationsDb.createWriteBatch()
            (registrationsToExpire.map(_._1) ++ deregistrationsToApply.map(_._1))
              .foreach { a =>
                activeRegistrationsBatch.delete(eBytes ++ a.bytes.toArray)
                activeRegistrationsBatch.delete(rBytes ++ a.bytes.toArray)
              }
            registrationsToApply.foreach { case (address, box, epoch) =>
              activeRegistrationsBatch.put(eBytes ++ address.bytes.toArray, epoch.bytes.toArray)
              activeRegistrationsBatch.put(rBytes ++ address.bytes.toArray, box.bytes.toArray)
            }
            activeRegistrationsDb.write(activeRegistrationsBatch)
            activeRegistrationsBatch.close()
          }

          _ <- Sync[F].blocking {
            // TODO: Update Total Stake
            // TODO: Subtract stake that is "spent"
            val activeStakeBatch = activeStakeDb.createWriteBatch()
            stakeChangesToApply.foreach {
              case (address, _, None) =>
                activeStakeBatch.delete(address.bytes.toArray)
              case (address, _, Some(value)) =>
                activeStakeBatch.put(address.bytes.toArray, value.bytes.toArray)
            }
            activeStakeDb.write(activeStakeBatch)
            activeStakeBatch.close()
            clearDb(n1PendingRegistrationsDb)
            clearDb(n1PendingDeregistrationsDb)
            clearDb(n1PendingStakeChangesDb)
          }
        } yield ()

      private def createNormalUnapply(block: BlockV2): F[NormalConsensusUnapply] =
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
              NormalConsensusUnapply(
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

      private def createEpochCrossingUnapply(block: BlockV2, newEpoch: Epoch): F[EpochCrossingUnapply] =
        Sync[F].defer(
          for {
            normalDelta       <- createNormalUnapply(block)
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
          } yield EpochCrossingUnapply(
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
