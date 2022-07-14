package co.topl.credential.playground.broadcast

import cats.data.{Chain, OptionT}
import cats.effect.{IO, IOApp}
import cats.implicits._
import co.topl.codecs.bytes.tetra.instances._
import co.topl.codecs.bytes.typeclasses.implicits._
import co.topl.credential.{Credential, DiskCredentialIO}
import co.topl.crypto.generation.KeyInitializer
import co.topl.crypto.generation.KeyInitializer.Instances.ed25519Initializer
import co.topl.crypto.signing.Ed25519
import co.topl.models.ModelGenerators._
import co.topl.models._
import co.topl.typeclasses.implicits._

import java.nio.file.{Files, Path, Paths}

object GenerateAndSaveKeyRef extends IOApp.Simple {
  type F[A] = IO[A]
  implicit val ed25519: Ed25519 = new Ed25519

  private val keyFilesDir: Path = Paths.get(".bifrost", "quivr-demo")
  Files.createDirectories(keyFilesDir)

  override def run: IO[Unit] =
    for {
      credentialIO <- DiskCredentialIO[F](keyFilesDir).pure[F]
      sk = KeyInitializer[SecretKeys.Ed25519].random()
      vk = sk.vk
      proposition = vk.asProposition
      spendingAddress = proposition.spendingAddress
      _ = println(s"Your spending address is: ${spendingAddress.immutableBytes.toBase58}")
      typedEvidence = spendingAddress.typedEvidence
      _ <- credentialIO.write(typedEvidence, sk.persistedBytes, "password")
    } yield ()
}

object SendPolysToPartyBRef extends IOApp.Simple {
  private val walletSKTypedEvidenceBase58 = ""
  private val partyATypedEvidenceBase58 = ""
  private val partyBAddressBase58 = ""
  private val keyFilesDir: Path = Paths.get(".bifrost", "quivr-demo")

  type F[A] = IO[A]
  implicit val ed25519: Ed25519 = new Ed25519

  Files.createDirectories(keyFilesDir)

  def run: IO[Unit] =
    for {
      credentialIO <- DiskCredentialIO[F](keyFilesDir).pure[F]

      // Load the offline wallet secret key
      walletSKTypedEvidence = Bytes
        .fromValidBase58(walletSKTypedEvidenceBase58)
        .decodeImmutable[TypedEvidence]
        .getOrElse(???)
      (walletSKBytes, _) <- OptionT(credentialIO.unlock(walletSKTypedEvidence, "password")).getOrElse(???)
      walletSK = walletSKBytes.decodePersisted[SecretKeys.Ed25519].getOrElse(???)

      // Load Party A's SK
      partyATypedEvidence = Bytes
        .fromValidBase58(partyATypedEvidenceBase58)
        .decodeImmutable[TypedEvidence]
        .getOrElse(???)
      (skBytes, _) <- OptionT(credentialIO.unlock(partyATypedEvidence, "password")).getOrElse(???)
      partyASK = skBytes.decodePersisted[SecretKeys.Ed25519].getOrElse(???)
      partyAProposition = partyASK.vk.asProposition

      // Establish Party B's spending address
      partyBAddress = Bytes.fromValidBase58(partyBAddressBase58).decodeImmutable[SpendingAddress].getOrElse(???)

      input = Transaction.Unproven.Input(
        arbitraryBoxId.arbitrary.first,
        partyASK.vk.asProposition,
        Box.Values.Poly(50)
      )

      output = Transaction.Output(
        fullAddress(partyBAddress, walletSK),
        Box.Values.Poly(50),
        minting = false
      )
      unprovenTransaction = Transaction.Unproven(
        Chain(input),
        Chain(output),
        Transaction.Chronology(System.currentTimeMillis(), 0L, Long.MaxValue),
        None
      )

      credential = Credential.Knowledge.Ed25519(partyASK, unprovenTransaction)

      provenTransaction = unprovenTransaction.prove { case `partyAProposition` =>
        credential.prove(Proofs.Undefined)
      }

      _ <- broadcastTx(provenTransaction)
    } yield ()

  private val stakingAddress: StakingAddress =
    StakingAddresses.Operator(ed25519.getVerificationKey(KeyInitializer[SecretKeys.Ed25519].random()))

  private def fullAddress(spendingAddress: SpendingAddress, offlineSK: SecretKeys.Ed25519): FullAddress =
    FullAddress(
      NetworkPrefix(1: Byte),
      spendingAddress,
      stakingAddress,
      ed25519.sign(offlineSK, (spendingAddress, stakingAddress).signableBytes)
    )

  private def broadcastTx(transaction: Transaction): F[Unit] =
    IO.delay {
      println(show"Broadcasting transaction id=${transaction.id.asTypedBytes} ${transaction.toString}")
    }

}
