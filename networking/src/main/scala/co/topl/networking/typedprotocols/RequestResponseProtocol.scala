package co.topl.networking.typedprotocols

import cats.Applicative
import cats.implicits._
import co.topl.networking.Parties

/**
 * A classification of Typed Protocol in which the client makes a request and the server produces a response
 * @tparam Query The information specified by the client in the request (i.e. an ID)
 * @tparam T The data returned by the server
 */
trait RequestResponseProtocol[Query, T] {

  /**
   * The state transitions for a server-side instance of this protocol
   * @param fetch Function to locally retrieve the data requested by the client
   */
  class ServerStateTransitions[F[_]: Applicative](fetch: Query => F[Option[T]]) {

    implicit val startNoneIdle: StateTransition[
      F,
      TypedProtocol.CommonMessages.Start.type,
      TypedProtocol.CommonStates.None.type,
      TypedProtocol.CommonStates.Idle.type
    ] =
      (_, _, _) => TypedProtocolState(Parties.B.some, TypedProtocol.CommonStates.Idle).pure[F]

    implicit val getIdleBusy: StateTransition[
      F,
      TypedProtocol.CommonMessages.Get[Query],
      TypedProtocol.CommonStates.Idle.type,
      TypedProtocol.CommonStates.Busy.type
    ] =
      (message, _, _) => fetch(message.query).as(TypedProtocolState(Parties.A.some, TypedProtocol.CommonStates.Busy))

    implicit val responseBusyIdle: StateTransition[F, TypedProtocol.CommonMessages.Response[
      T
    ], TypedProtocol.CommonStates.Busy.type, TypedProtocol.CommonStates.Idle.type] =
      (_, _, _) => TypedProtocolState(Parties.B.some, TypedProtocol.CommonStates.Idle).pure[F]

    implicit val doneIdleDone: StateTransition[
      F,
      TypedProtocol.CommonMessages.Done.type,
      TypedProtocol.CommonStates.Idle.type,
      TypedProtocol.CommonStates.Done.type
    ] =
      (_, _, _) => TypedProtocolState(none, TypedProtocol.CommonStates.Done).pure[F]
  }

  /**
   * The state transitions for a client-side instance of this protocol
   * @param responseReceived Function to handle data returned by the server
   */
  class ClientStateTransitions[F[_]: Applicative](responseReceived: Option[T] => F[Unit]) {

    implicit val startNoneIdle: StateTransition[
      F,
      TypedProtocol.CommonMessages.Start.type,
      TypedProtocol.CommonStates.None.type,
      TypedProtocol.CommonStates.Idle.type
    ] =
      (_, _, _) => TypedProtocolState(Parties.B.some, TypedProtocol.CommonStates.Idle).pure[F]

    implicit val getIdleBusy: StateTransition[
      F,
      TypedProtocol.CommonMessages.Get[Query],
      TypedProtocol.CommonStates.Idle.type,
      TypedProtocol.CommonStates.Busy.type
    ] =
      (_, _, _) => TypedProtocolState(Parties.A.some, TypedProtocol.CommonStates.Busy).pure[F]

    implicit val responseBusyIdle: StateTransition[F, TypedProtocol.CommonMessages.Response[
      T
    ], TypedProtocol.CommonStates.Busy.type, TypedProtocol.CommonStates.Idle.type] =
      (message, _, _) =>
        responseReceived(message.dataOpt).as(TypedProtocolState(Parties.B.some, TypedProtocol.CommonStates.Idle))

    implicit val doneIdleDone: StateTransition[
      F,
      TypedProtocol.CommonMessages.Done.type,
      TypedProtocol.CommonStates.Idle.type,
      TypedProtocol.CommonStates.Done.type
    ] =
      (_, _, _) => TypedProtocolState(none, TypedProtocol.CommonStates.Done).pure[F]
  }

}
