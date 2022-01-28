package co.topl.demo

import cats.data.{NonEmptyChain, OptionT}
import cats.effect._
import cats.effect.std.Semaphore
import cats.implicits._
import cats.{Eq, Monad, MonadThrow, Show}
import co.topl.algebras.Store
import co.topl.models.{Bytes, TypePrefix, TypedBytes, TypedIdentifier}
import co.topl.typeclasses.Identifiable
import co.topl.typeclasses.implicits._

trait EventTree[F[_], Event, State] {
  def stateAt(eventId: TypedIdentifier): F[State]
}

object EventTree {

  object Eval {

    def make[F[_]: Async, Event, State, StateDelta](
      initialState:    F[State],
      initialEventId:  F[TypedIdentifier],
      eventAsDelta:    (Event, State) => F[StateDelta],
      applyDelta:      (State, StateDelta) => F[State],
      unapplyDelta:    (State, StateDelta) => F[State],
      eventStore:      Store[F, Event],
      deltaStore:      Store[F, StateDelta],
      parentChildTree: ParentChildTree[F, TypedIdentifier]
    ): F[EventTree[F, Event, State]] = for {
      permit            <- Semaphore[F](1).map(_.permit)
      currentStateRef   <- initialState.flatMap(Ref.of[F, State])
      currentEventIdRef <- initialEventId.flatMap(Ref.of[F, TypedIdentifier])
    } yield new EventTree[F, Event, State] {

      def stateAt(eventId: TypedIdentifier): F[State] =
        permit.use(_ =>
          for {
            currentEventId <- currentEventIdRef.get
            state <- Monad[F].ifElseM(
              Async[F].delay(currentEventId === eventId) -> currentStateRef.get
            )(
              Async[F].defer(
                parentChildTree.findCommonAncestor(currentEventId, eventId).flatMap { case (unapplyChain, applyChain) =>
                  for {
                    currentState <- currentStateRef.get
                    stateAtCommonAncestor <- unapplyChain.tail.reverse.foldLeftM(currentState) {
                      case (state, eventId) =>
                        for {
                          delta <- OptionT(deltaStore.get(eventId))
                            .getOrElseF(MonadThrow[F].raiseError(new NoSuchElementException))
                          _        <- deltaStore.remove(eventId)
                          newState <- unapplyDelta(state, delta)
                          _        <- currentStateRef.set(newState)
                          _        <- currentEventIdRef.set(eventId)
                        } yield newState
                    }
                    newState <- applyChain.tail.foldLeftM(stateAtCommonAncestor) { case (state, eventId) =>
                      for {
                        event <- OptionT(eventStore.get(eventId))
                          .getOrElseF(MonadThrow[F].raiseError(new NoSuchElementException))
                        delta    <- eventAsDelta(event, state)
                        _        <- deltaStore.put(delta)
                        newState <- applyDelta(state, delta)
                        _        <- currentStateRef.set(newState)
                        _        <- currentEventIdRef.set(eventId)
                      } yield newState
                    }
                  } yield newState
                }
              )
            )
          } yield state
        )
    }
  }
}

trait ParentChildTree[F[_], T] {
  def parentOf(t:           T): F[Option[T]]
  def associate(child:      T, parent: T): F[Unit]
  def heightOf(t:           T): F[Long]
  def findCommonAncestor(a: T, b:      T): F[(NonEmptyChain[T], NonEmptyChain[T])]
}

object ParentChildTree {

  object FromRef {

    def make[F[_]: Sync, T: Eq: Show]: F[ParentChildTree[F, T]] =
      Ref
        .of[F, Map[T, T]](Map.empty[T, T])
        .map(ref =>
          new ParentChildTree[F, T] {

            def parentOf(t: T): F[Option[T]] =
              ref.get.map(_.get(t))

            def associate(child: T, parent: T): F[Unit] =
              ref.update(_.updated(child, parent))

            def heightOf(t: T): F[Long] =
              (t, 0L)
                .tailRecM { case (t, distance) =>
                  OptionT(parentOf(t))
                    .map(p => (p, distance + 1).asLeft)
                    .getOrElse((t, distance).asRight)
                }
                .map(_._2)

            def findCommonAncestor(a: T, b: T): F[(NonEmptyChain[T], NonEmptyChain[T])] =
              if (a === b) Sync[F].delay((NonEmptyChain(a), NonEmptyChain(b)))
              else
                for {
                  (aHeight, bHeight) <- (heightOf(a), heightOf(b)).tupled
                  prependWithParent = (c: NonEmptyChain[T]) =>
                    OptionT(parentOf(c.head))
                      .getOrElseF(MonadThrow[F].raiseError(new NoSuchElementException(c.head.show)))
                      .map(c.prepend)
                  (aAtEqualHeight, bAtEqualHeight) <- Monad[F]
                    .ifElseM(
                      Sync[F].delay(aHeight === bHeight) -> Sync[F].delay((NonEmptyChain(a), NonEmptyChain(b))),
                      Sync[F].delay(aHeight < bHeight) -> Sync[F].defer(
                        (NonEmptyChain(b), bHeight)
                          .iterateUntilM { case (chain, height) =>
                            prependWithParent(chain).map(_ -> (height - 1))
                          }(_._2 === aHeight)
                          .map(NonEmptyChain(a) -> _._1)
                      )
                    )(
                      Sync[F].defer(
                        (NonEmptyChain(a), aHeight)
                          .iterateUntilM { case (chain, height) =>
                            prependWithParent(chain).map(_ -> (height - 1))
                          }(_._2 === bHeight)
                          .map(_._1 -> NonEmptyChain(b))
                      )
                    )
                  (chainA, chainB) <- (aAtEqualHeight, bAtEqualHeight).iterateUntilM { case (aChain, bChain) =>
                    (prependWithParent(aChain), prependWithParent(bChain)).tupled
                  } { case (aChain, bChain) => aChain.head === bChain.head }
                } yield (chainA, chainB)
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

  implicit val identifiableLedgerDelta: Identifiable[LedgerDelta] =
    new Identifiable[LedgerDelta] {
      def idOf(l: LedgerDelta): TypedIdentifier = TypedBytes(typePrefix, l.tx.id.dataBytes)

      def typePrefix: TypePrefix = 2: Byte
    }

  case class LedgerDelta(previousTxId: TypedIdentifier, senderPreviousBalance: Long, tx: Tx)

  def run: IO[Unit] = for {
    eventStore <- RefStore.Eval.make[F, Tx]()
    deltaStore <- RefStore.Eval.make[F, LedgerDelta]()
    tree       <- ParentChildTree.FromRef.make[F, TypedIdentifier]
    _          <- eventStore.put(Tx(id = "a".asTxId, from = ("alice", 10), to = "bob"))
    _          <- eventStore.put(Tx(id = "b".asTxId, from = ("alice", 10), to = "chelsea"))
    _          <- eventStore.put(Tx(id = "c1".asTxId, from = ("alice", 5), to = "bob"))
    _          <- eventStore.put(Tx(id = "c2".asTxId, from = ("alice", 5), to = "chelsea"))
    _          <- tree.associate(child = "c1".asTxId, parent = "b".asTxId)
    _          <- tree.associate(child = "c2".asTxId, parent = "b".asTxId)
    _          <- tree.associate(child = "b".asTxId, parent = "a".asTxId)
    _          <- tree.associate(child = "a".asTxId, parent = "-1".asTxId)
    eventTree <- EventTree.Eval.make[F, Tx, Ledger, LedgerDelta](
      initialState = Sync[F].delay(Ledger("-1".asTxId, Map("alice" -> 100L))),
      initialEventId = Sync[F].delay("-1".asTxId),
      eventAsDelta =
        (tx, ledger) => Sync[F].delay(LedgerDelta(ledger.latestTxId, ledger.balances.getOrElse(tx.from._1, 0L), tx)),
      applyDelta = (ledger, delta) => Sync[F].delay(ledger.withTx(delta.tx)),
      unapplyDelta = (ledger, delta) =>
        Sync[F].delay(
          Ledger(
            delta.previousTxId,
            ledger.balances
              .updated(delta.tx.from._1, delta.senderPreviousBalance)
              .updatedWith(delta.tx.to)(_.map(_ - delta.tx.from._2))
          )
        ),
      eventStore = eventStore,
      deltaStore = deltaStore,
      parentChildTree = tree
    )
    ledgerC1 <- eventTree.stateAt("c1".asTxId)
    _ = println(ledgerC1)
    ledgerC2 <- eventTree.stateAt("c2".asTxId)
    _ = println(ledgerC2)
  } yield ()

  implicit class StringOps(string: String) {
    def asTxId: TypedIdentifier = TypedBytes(1: Byte, Bytes(string.getBytes()))
  }
}
