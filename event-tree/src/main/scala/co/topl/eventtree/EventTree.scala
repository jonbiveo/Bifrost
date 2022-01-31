package co.topl.eventtree

import cats._
import cats.data.{Chain, OptionT}
import cats.effect._
import cats.effect.std.Semaphore
import cats.implicits._
import co.topl.algebras.{Store, StoreReader}
import co.topl.models._
import co.topl.typeclasses._
import co.topl.typeclasses.implicits._

trait EventTree[F[_], Event, State] {
  def stateAt(eventId: TypedIdentifier): F[State]
}

object EventTree {

  object Eval {

    def make[F[_]: Async, Event, State, StateDelta: Identifiable](
      initialState:    F[State],
      initialEventId:  F[TypedIdentifier],
      eventAsDelta:    (Event, State) => F[StateDelta],
      applyDelta:      (State, StateDelta) => F[State],
      unapplyDelta:    (State, StateDelta) => F[State],
      eventStore:      StoreReader[F, Event],
      deltaStore:      Store[F, StateDelta],
      parentChildTree: ParentChildTree[F, TypedIdentifier]
    ): F[EventTree[F, Event, State]] = for {
      permit            <- Semaphore[F](1).map(_.permit)
      currentStateRef   <- initialState.flatMap(Ref.of[F, State])
      currentEventIdRef <- initialEventId.flatMap(Ref.of[F, TypedIdentifier])
    } yield new Impl[F, Event, State, StateDelta](
      eventAsDelta,
      applyDelta,
      unapplyDelta,
      eventStore,
      deltaStore,
      parentChildTree,
      permit,
      currentStateRef,
      currentEventIdRef
    )

    private class Impl[F[_]: Async, Event, State, StateDelta: Identifiable](
      eventAsDelta:      (Event, State) => F[StateDelta],
      applyDelta:        (State, StateDelta) => F[State],
      unapplyDelta:      (State, StateDelta) => F[State],
      eventStore:        StoreReader[F, Event],
      deltaStore:        Store[F, StateDelta],
      parentChildTree:   ParentChildTree[F, TypedIdentifier],
      permit:            Resource[F, Unit],
      currentStateRef:   Ref[F, State],
      currentEventIdRef: Ref[F, TypedIdentifier]
    ) extends EventTree[F, Event, State] {

      def stateAt(eventId: TypedIdentifier): F[State] =
        permit.use(_ =>
          for {
            currentEventId <- currentEventIdRef.get
            state <- Monad[F].ifElseM(
              Async[F].delay(currentEventId === eventId) -> currentStateRef.get
            )(
              Async[F].defer(
                for {
                  ((unapplyChain, applyChain), currentState) <- (
                    parentChildTree.findCommonAncestor(currentEventId, eventId),
                    currentStateRef.get
                  ).tupled
                  stateAtCommonAncestor <- unapplyEvents(unapplyChain.tail, currentState)
                  newState              <- applyEvents(applyChain.tail, stateAtCommonAncestor)
                } yield newState
              )
            )
          } yield state
        )

      private def unapplyEvents(eventIds: Chain[TypedIdentifier], currentState: State): F[State] =
        eventIds.reverse.foldLeftM(currentState) { case (state, eventId) =>
          for {
            delta    <- getDelta(eventId)
            _        <- deltaStore.remove(eventId)
            newState <- unapplyDelta(state, delta)
            _        <- (currentStateRef.set(newState), currentEventIdRef.set(eventId)).tupled
          } yield newState
        }

      private def applyEvents(eventIds: Chain[TypedIdentifier], currentState: State): F[State] =
        eventIds.foldLeftM(currentState) { case (state, eventId) =>
          for {
            event    <- getEvent(eventId)
            delta    <- eventAsDelta(event, state)
            _        <- deltaStore.put(eventId, delta)
            newState <- applyDelta(state, delta)
            _        <- (currentStateRef.set(newState), currentEventIdRef.set(eventId)).tupled
          } yield newState
        }

      private def getEvent(eventId: TypedIdentifier): F[Event] =
        OptionT(eventStore.get(eventId))
          .getOrElseF(MonadThrow[F].raiseError(new NoSuchElementException(eventId.toString)))

      private def getDelta(eventId: TypedIdentifier): F[StateDelta] =
        OptionT(deltaStore.get(eventId))
          .getOrElseF(MonadThrow[F].raiseError(new NoSuchElementException(eventId.toString)))
    }
  }
}
