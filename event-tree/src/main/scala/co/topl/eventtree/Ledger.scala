package co.topl.eventtree

import cats.MonadThrow
import cats.data.OptionT
import cats.implicits._
import co.topl.algebras.Store
import co.topl.models._
import co.topl.typeclasses.implicits._

trait Ledger[F[_]] {
  def eventId: F[TypedIdentifier]
  def setEventId(id:        TypedIdentifier): F[Unit]
  def balanceOf(name:       String): F[Option[Long]]
  def modifyBalanceOf(name: String, f: Option[Long] => F[Option[Long]]): F[Unit]
}

object Ledger {

  object Eval {
    val CurrentEventIdId = TypedBytes(-1: Byte, Bytes(-1: Byte))

    def make[F[_]: MonadThrow](store: Store[F, Bytes]): Ledger[F] = new Ledger[F] {

      def eventId: F[TypedIdentifier] =
        OptionT(store.get(CurrentEventIdId)).foldF(
          (new NoSuchElementException(CurrentEventIdId.show).raiseError[F, TypedIdentifier])
        )(TypedBytes(_).pure[F])

      def setEventId(id: TypedIdentifier): F[Unit] =
        store.put(CurrentEventIdId, id.allBytes)

      def balanceOf(name: String): F[Option[Long]] =
        OptionT
          .fromOption[F](Bytes.encodeUtf8(name).toOption)
          .map(TypedBytes(1: Byte, _))
          .flatMapF(store.get)
          .map(_.toLong())
          .value

      def modifyBalanceOf(name: String, f: Option[Long] => F[Option[Long]]): F[Unit] =
        OptionT
          .fromOption[F](Bytes.encodeUtf8(name).toOption)
          .map(TypedBytes(1: Byte, _))
          .getOrElseF(???)
          .flatTap(key =>
            OptionT(store.get(key))
              .map(_.toLong())
              .flatTransform(f)
              .foldF(store.remove(key))(t => store.put(key, Bytes.fromLong(t)))
          )
          .void
    }
  }
}
