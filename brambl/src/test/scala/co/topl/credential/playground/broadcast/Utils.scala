package co.topl.credential.playground.broadcast

import cats.implicits._
import cats.data.OptionT
import cats.effect.IO
import co.topl.codecs.bytes.typeclasses.Persistable
import co.topl.crypto.signing.Ed25519
import co.topl.models.{
  Bytes,
  FullAddress,
  NetworkPrefix,
  Proposition,
  SecretKeys,
  SpendingAddress,
  StakingAddress,
  StakingAddresses,
  Transaction,
  TransactionData,
  TypedEvidence
}
import co.topl.codecs.bytes.tetra.instances._
import co.topl.codecs.bytes.typeclasses.implicits._
import co.topl.credential.{CredentialIO, DiskCredentialIO}
import co.topl.models.utility.HasLength.instances.latin1DataLength
import co.topl.models.utility.Sized
import co.topl.models.utility.StringDataTypes.Latin1Data
import co.topl.typeclasses.KeyInitializer
import co.topl.typeclasses.implicits._

import java.nio.file.{Files, Path, Paths}

trait Utils {
  type F[A] = IO[A]
  implicit val ed25519: Ed25519 = new Ed25519

  protected val keyFilesDir: Path = Paths.get(".bifrost", "quivr-demo")
  Files.createDirectories(keyFilesDir)

  protected val credentialIO: CredentialIO[F] =
    DiskCredentialIO[F](keyFilesDir)

  protected def decodeSpendingAddress(addressBase58: String): SpendingAddress =
    Bytes.fromValidBase58(addressBase58).decodeImmutable[SpendingAddress].getOrElse(???)

  protected def decodeProposition(propositionBase58: String): Proposition =
    Bytes.fromValidBase58(propositionBase58).decodeImmutable[Proposition].getOrElse(???)

  protected def loadKey[SK: Persistable](evidenceBase58: String, password: String): F[SK] = for {
    typedEvidence <- Bytes
      .fromValidBase58(evidenceBase58)
      .decodeImmutable[TypedEvidence]
      .getOrElse(???)
      .pure[F]
    (bytes, _) <- OptionT(credentialIO.unlock(typedEvidence, "password")).getOrElse(???)
    sk = bytes.decodePersisted[SK].getOrElse(???)
  } yield sk

  protected val stakingAddress: StakingAddress =
    StakingAddresses.Operator(ed25519.getVerificationKey(KeyInitializer[SecretKeys.Ed25519].random()))

  protected val walletSK: SecretKeys.Ed25519 =
    KeyInitializer[SecretKeys.Ed25519].random()

  protected def toFullAddress(spendingAddress: SpendingAddress): FullAddress =
    FullAddress(
      NetworkPrefix(1: Byte),
      spendingAddress,
      stakingAddress,
      ed25519.sign(walletSK, (spendingAddress, stakingAddress).signableBytes)
    )

  protected def broadcastTx(transaction: Transaction): F[Unit] =
    IO.delay {
      println(show"Broadcasting transaction id=${transaction.id.asTypedBytes} ${transaction.toString}")
    }

  protected def asTransactionData(data: String): TransactionData =
    Sized.maxUnsafe(Latin1Data.unsafe(data))

}
