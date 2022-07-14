package co.topl.credential.playground.broadcast

import cats.data.Chain
import cats.effect.{IO, IOApp}
import cats.implicits._
import co.topl.codecs.bytes.tetra.instances._
import co.topl.codecs.bytes.typeclasses.implicits._
import co.topl.credential.Credential
import co.topl.crypto.hash.Blake2b256
import co.topl.models.ModelGenerators._
import co.topl.models._
import co.topl.typeclasses.implicits._

import java.nio.charset.StandardCharsets

// James does this
object HashLockRef extends IOApp.Simple with Utils {

  def run: IO[Unit] =
    for {
      secretData <- Bytes("password".getBytes(StandardCharsets.UTF_8)).pure[F]
      digest = new Blake2b256().hash(secretData)
      proposition = Propositions.Knowledge.HashLock(digest)
      spendingAddress = proposition.spendingAddress
      _ = println(s"Your proposition is: ${proposition.immutableBytes.toBase58}")
      _ = println(s"Your spending address is: ${spendingAddress.immutableBytes.toBase58}")
    } yield ()
}

// Sean does this
object HashLock2Ref extends IOApp.Simple with Utils {

  def run: IO[Unit] =
    for {
      address <- decodeSpendingAddress("").pure[F]
      unprovenTransaction = Transaction.Unproven(
        inputs = Chain(
          Transaction.Unproven
            .Input(arbitraryBoxId.arbitrary.first, arbitraryProposition.arbitrary.first, Box.Values.Poly(5))
        ),
        outputs = Chain(Transaction.Output(toFullAddress(address), Box.Values.Poly(5), minting = false)),
        chronology = Transaction.Chronology(System.currentTimeMillis(), 0L, Long.MaxValue),
        data = None
      )
      boxId = Box.Id(unprovenTransaction.id, 0)
      _ = println(show"Your box is located at $boxId")
    } yield ()
}

// James does this
object HashLock3Ref extends IOApp.Simple with Utils {

  def run: IO[Unit] =
    for {
      secretData <- Bytes("password".getBytes(StandardCharsets.UTF_8)).pure[F]
      digest = new Blake2b256().hash(secretData)
      proposition = Propositions.Knowledge.HashLock(digest)
      nextAddress = arbitrarySpendingAddress.arbitrary.first
      boxId = Box.Id(decodeId(""), 0)
      unprovenTransaction = Transaction.Unproven(
        inputs = Chain(Transaction.Unproven.Input(boxId, proposition, Box.Values.Poly(5))),
        outputs = Chain(Transaction.Output(toFullAddress(nextAddress), Box.Values.Poly(5), minting = false)),
        chronology = Transaction.Chronology(System.currentTimeMillis(), 0L, Long.MaxValue),
        data = None
      )
      credential = Credential.Knowledge.HashLock(secretData)
      transaction = unprovenTransaction.prove { case `proposition` => credential.proof }
      _ <- broadcastTx(transaction)
    } yield ()
}
