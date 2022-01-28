package co.topl.demo

import cats.MonadThrow
import cats.data.{NonEmptyChain, OptionT}
import cats.effect._
import cats.implicits._
import co.topl.algebras.Store
import co.topl.models.{Bytes, TypePrefix, TypedBytes, TypedIdentifier}
import co.topl.typeclasses.Identifiable
import co.topl.typeclasses.implicits._

trait EventTree[F[_], Event, State] {
  def stateAt(eventId: TypedIdentifier): F[State]
}

object EventTree {

  object Eval {

    def make[F[_]: MonadThrow, Event, State](
      applyEvent:      (Event, State) => F[State],
      emptyState:      F[State],
      eventStore:      Store[F, Event],
      stateStore:      Store[F, State],
      parentChildTree: ParentChildTree[F, TypedIdentifier]
    ): EventTree[F, Event, State] =
      eventId =>
        OptionT(stateStore.get(eventId))
          .getOrElseF(
            for {
              // Step 1: Find the most recent cached state along the event-chain, and build a chain of eventIds back to that point
              (eventIds, stateOpt) <-
                (NonEmptyChain(eventId), None: Option[State])
                  .iterateUntilM { case (eventIds, _) =>
                    OptionT(parentChildTree.parentOf(eventIds.head))
                      .semiflatMap(parentId => stateStore.get(parentId).map(s => (eventIds.prepend(parentId), s)))
                      .getOrElseF(emptyState.map(e => (eventIds, e.some)))
                  }(_._2.nonEmpty)
              baseState <-
                OptionT.fromOption[F](stateOpt).getOrElseF(emptyState)
              state <-
                eventIds.foldLeftM[F, State](baseState)((state, eventId) =>
                  OptionT(eventStore.get(eventId))
                    .getOrElseF(MonadThrow[F].raiseError(new NoSuchElementException(eventId.show)))
                    .flatMap(event => applyEvent(event, state))
                )
            } yield state
          )
  }
}

trait ParentChildTree[F[_], T] {
  def parentOf(t:      T): F[Option[T]]
  def associate(child: T, parent: T): F[Unit]
}

object ParentChildTree {

  object FromRef {

    def make[F[_]: Concurrent, T]: F[ParentChildTree[F, T]] =
      Ref
        .of(Map.empty[T, T])
        .map(ref =>
          new ParentChildTree[F, T] {

            def parentOf(t: T): F[Option[T]] =
              ref.get.map(_.get(t))

            def associate(child: T, parent: T): F[Unit] =
              ref.update(_.updated(child, parent))
          }
        )
  }
}

object EventTreeTest extends IOApp.Simple {
  type F[A] = IO[A]

  case class Tx(id: TypedIdentifier, from: (String, Long), to: String)

  case class Ledger(latestTxId: TypedIdentifier, balances: Map[String, Long]) {

    def withTx(tx: Tx): Ledger = {
      val newBalances =
        balances
          .updated(tx.from._1, balances(tx.from._1) - tx.from._2)
          .updated(tx.to, balances.getOrElse(tx.to, 0L) + tx.from._2)
      Ledger(tx.id, newBalances)
    }

  }

  implicit val identifiableTx: Identifiable[Tx] =
    new Identifiable[Tx] {
      def idOf(tx: Tx): TypedIdentifier = tx.id

      def typePrefix: TypePrefix = 1: Byte
    }

  implicit val identifiableLedger: Identifiable[Ledger] =
    new Identifiable[Ledger] {
      def idOf(l: Ledger): TypedIdentifier = TypedBytes(typePrefix, l.latestTxId.dataBytes)

      def typePrefix: TypePrefix = 2: Byte
    }

  def run: IO[Unit] = for {
    eventStore <- RefStore.Eval.make[F, Tx]()
    stateStore <- RefStore.Eval.make[F, Ledger]()
    tree       <- ParentChildTree.FromRef.make[F, TypedIdentifier]
    _          <- eventStore.put(Tx(id = "a".asTxId, from = ("alice", 10), to = "bob"))
    _          <- eventStore.put(Tx(id = "b".asTxId, from = ("alice", 10), to = "chelsea"))
    _          <- eventStore.put(Tx(id = "c1".asTxId, from = ("alice", 5), to = "bob"))
    _          <- eventStore.put(Tx(id = "c2".asTxId, from = ("alice", 5), to = "chelsea"))
    _          <- tree.associate(child = "c1".asTxId, parent = "b".asTxId)
    _          <- tree.associate(child = "c2".asTxId, parent = "b".asTxId)
    _          <- tree.associate(child = "b".asTxId, parent = "a".asTxId)
    eventTree = EventTree.Eval.make[F, Tx, Ledger](
      applyEvent = (event, state) => Sync[F].delay(state.withTx(event)),
      emptyState = Sync[F].delay(Ledger("-1".asTxId, Map("alice" -> 100L))),
      eventStore = eventStore,
      stateStore = stateStore,
      parentChildTree = tree
    )
    ledgerC1 <- eventTree.stateAt("c1".asTxId)
    ledgerC2 <- eventTree.stateAt("c2".asTxId)
    _ = println(ledgerC1)
    _ = println(ledgerC2)
  } yield ()

  implicit class StringOps(string: String) {
    def asTxId: TypedIdentifier = TypedBytes(1: Byte, Bytes(string.getBytes()))
  }
}
