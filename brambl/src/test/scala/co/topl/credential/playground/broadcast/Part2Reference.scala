package co.topl.credential.playground.broadcast

import cats.data.Chain
import cats.effect.{IO, IOApp}
import cats.implicits._
import co.topl.codecs.bytes.tetra.instances._
import co.topl.codecs.bytes.typeclasses.implicits._
import co.topl.credential.Credential
import co.topl.models.ModelGenerators._
import co.topl.models._
import co.topl.typeclasses.KeyInitializer
import co.topl.typeclasses.implicits._

import scala.collection.immutable.ListSet

/**
 * Scenario: James, Nick, and Sean are roommates.  They need to pay the utility bill for the month.  The three roomates
 * own a "shared account" which requires permission from at least 2 of the roommates to be spent.  James is out of town,
 * so Nick and Sean will submit the payment this month.
 *
 * Nick starts by generating a new SK `GenerateAndSaveKeyAndPrintPropositionRef`.  It will print a proposition and an address.
 * Nick should send the proposition to Sean.  Nick should copy and save the address locally (for Step 3)
 *
 * Sean takes over with `CreatePartiallyProvenTransaction` and pastes Nick's proposition.
 * This step will produce bytes for a Transaction.  Sean sends these bytes to Nick.
 *
 * Nick takes over with `CompleteAndBroadcastTransaction`.  Nick pastes the Transaction bytes.
 * Nick pastes the address he saved locally to load the SK.
 */

object GenerateAndSaveKeyAndPrintPropositionRef extends IOApp.Simple with Utils {

  def run: IO[Unit] =
    for {
      sk <- KeyInitializer[SecretKeys.Ed25519].random().pure[F]
      vk = sk.vk
      proposition = vk.asProposition
      spendingAddress = proposition.spendingAddress
      _ = println(s"Your proposition is: ${proposition.immutableBytes.toBase58}")
      _ = println(s"Your spending address is: ${spendingAddress.immutableBytes.toBase58}")
      typedEvidence = spendingAddress.typedEvidence
      _ <- credentialIO.write(typedEvidence, sk.persistedBytes, "password")
    } yield ()
}

object CreatePartiallyProvenTransaction extends IOApp.Simple with Utils {

  def run: IO[Unit] =
    for {
      seanSK <- loadKey[SecretKeys.Ed25519]("3sr8RXwCrYWEamYJvNA5etamBAvwy68bxBajxiGRj1Kfi", "password")

      seanProposition: Proposition = seanSK.vk.asProposition

      // 3i8MyD3nhJme8seZ2sko6akkysaELL8eErHUeEuZSuGsX
      jamesProposition: Proposition = decodeProposition("ckV8PfJwozMd9AVfq3cFQS6oF5k2zd7DFLBgakLLpxWp")
      // 3yENTdjgjxvsDKSN2naUyLUh7TUQqMTTy6qbKWRUZZd7w
      nickProposition: Proposition = decodeProposition("eh5s7Dy5B31ETTUjrn3SNnAvnacXYcjyvfGGq7c8qjws")

      utilityCompanyAddress: SpendingAddress = decodeSpendingAddress("3prVS1QJ5tHqWVyMbzB7Bus3jvkwPmkuAeBoJcdcHCcP5")

      thresholdProposition: Propositions.Compositional.Threshold =
        ListSet(seanProposition, jamesProposition, nickProposition).threshold(2)

      roomatesAddress: SpendingAddress =
        thresholdProposition.spendingAddress

      unprovenTransaction: Transaction.Unproven =
        Transaction.Unproven(
          inputs = Chain(
            Transaction.Unproven.Input(
              arbitraryBoxId.arbitrary.first,
              thresholdProposition,
              Box.Values.Poly(500)
            )
          ),
          outputs = Chain(
            Transaction.Output(
              toFullAddress(utilityCompanyAddress),
              Box.Values.Poly(80),
              minting = false
            ),
            Transaction.Output(
              toFullAddress(roomatesAddress),
              Box.Values.Poly(420),
              minting = false
            )
          ),
          chronology = Transaction.Chronology(System.currentTimeMillis(), 0, Long.MaxValue),
          data = asTransactionData("Utility bill for June 30, 2020").some
        )

      seanCredential = Credential.Knowledge.Ed25519(seanSK, unprovenTransaction)
      credential = Credential.Compositional.Threshold(thresholdProposition, List(seanCredential))
      provenTransaction = unprovenTransaction.prove { case `thresholdProposition` =>
        credential.prove(Proofs.False)
      }
      _ = println(s"Your transaction is $provenTransaction")
      _ = println(s"Your proof is ${provenTransaction.inputs.headOption.get.proof}")
      _ = println(
        s"Send these transaction bytes to your roommate to sign and broadcast: ${provenTransaction.immutableBytes.toBase58}"
      )
    } yield ()
}

object CompleteAndBroadcastTransaction extends IOApp.Simple with Utils {

  val partiallyProvenTransactionBytesBase58 =
    "2WR4TP94riARai68q59kio92XhzGfAn3NNJzbr5kMHmnUsjKEaJwqjSCE89WsXk8trWAeFAVkaeAPrHzoCJMSRzj3K99gh8BUaDeT59ViUdB91QRuZKLrFNWCWL4SffuSDUzixMBksX87crniv4XJcVmXBR6jCmUWGvNwU3aQxZsXbacTaMfgCKGmB9qXLH45iF6562mqV6n2Jcm36eo2yjLCWjbJgs99CmUEqcXhQPHUhsjW37KPMcRZwYAzzs3WKX4qq2KDqdphnqF9tsnokRFJwk1sDkUnCQXLsMNcoL6ksxgvjjZZpbGCH5pRrNbgBmsVkFgE1dbLHLfb4QJLbwrcEuEL3feA8L9NiPCZZnoMJFHvRkccvFukN4gW1EfYbAWw642stsGu7V5ciBuBUfySLNXH1WpGS2vBQSqxXWZvT9oiw9EBzQC5LyiVV2ujmiQJdRG66NuJSJStHJgjXRFXSt8HbxRyi39ktyZDLx8nSERz9EJHPbPubDdtt3VZtv419aNM2D3mzeQDZKmdv5Bio2YYVMko7LFPJhw1jvB1gEtf2mcoByvzJj7s9Ys2KbtnAsB7G1eP2L5djPDVHzmBApSWGegeANeMmPuY3zLF9NUKZnr7oWAiQN7SehWr542vkLYoSoaPpWLFUjPyat7kS27VSWth6c2uJEDYPSpHchNpGwdCzp678PD43Nv7kBAQ5A1ecEmZdcTQTijnLxxibVmZdHE3bNaeSPdYhumoDdPUkcwitom8CFtC4LmM7M86dwLGqNm14XZnsjFRvD4No27R6RrYXuAbwtMaD4cT8syBmEUm7acBzctqThwiVwfC8HX6azzFXbkjoHyLdgk7vYw"

  val partiallyProvenTransaction: Transaction =
    Bytes.fromValidBase58(partiallyProvenTransactionBytesBase58).decodeImmutable[Transaction].getOrElse(???)
  val unprovenTransaction: Transaction.Unproven = partiallyProvenTransaction.unproven

  def run: IO[Unit] =
    for {
      nickSK <- loadKey[SecretKeys.Ed25519]("3yENTdjgjxvsDKSN2naUyLUh7TUQqMTTy6qbKWRUZZd7w", "password")
      thresholdProposition = partiallyProvenTransaction.inputs.headOption.get.proposition
        .asInstanceOf[Propositions.Compositional.Threshold]
      nickCredential = Credential.Knowledge.Ed25519(nickSK, unprovenTransaction)
      thresholdCredential = Credential.Compositional.Threshold(thresholdProposition, List(nickCredential))
      provenTransaction = partiallyProvenTransaction.prove { case (`thresholdProposition`, currentProof) =>
        thresholdCredential.prove(currentProof)
      }
      _ = println(s"The proven transaction is: $provenTransaction")
      _ = println(s"The updated proof is ${provenTransaction.inputs.headOption.get.proof}")
      _ <- broadcastTx(provenTransaction)
    } yield ()
}
