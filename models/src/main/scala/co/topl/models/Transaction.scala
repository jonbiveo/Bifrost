package co.topl.models

import cats.data.{Chain, NonEmptyChain}

import scala.collection.immutable.ListMap

case class Transaction(
  inputs:           ListMap[BoxReference, (Proposition, Proof)],
  feeOutput:        Option[Transaction.CoinOutputs.Poly],
  coinOutputs:      NonEmptyChain[Transaction.CoinOutput],
  consensusOutputs: Chain[Transaction.ConsensusOutput],
  fee:              Int128,
  timestamp:        Timestamp,
  data:             Option[TransactionData],
  minting:          Boolean
)

// TODO: Syntactic and Semantic Validation
object Transaction {
  sealed abstract class CoinOutput

  object CoinOutputs {
    case class Poly(dionAddress: DionAddress, value: Int128) extends CoinOutput
    case class Arbit(dionAddress: DionAddress, taktikosAddress: TaktikosAddress, value: Int128) extends CoinOutput
    case class Asset(dionAddress: DionAddress, value: Box.Values.Asset) extends CoinOutput
  }

  sealed abstract class ConsensusOutput

  object ConsensusOutputs {
    case class Registration(address: TaktikosAddress, commitment: Proofs.Knowledge.KesProduct) extends ConsensusOutput
    case class Deregistration(address: TaktikosAddress) extends ConsensusOutput
  }

  case class Unproven(
    inputs:      List[BoxReference],
    feeOutput:   Option[Transaction.CoinOutputs.Poly],
    coinOutputs: NonEmptyChain[Transaction.CoinOutput],
    fee:         Int128,
    timestamp:   Timestamp,
    data:        Option[TransactionData],
    minting:     Boolean
  )

  object Unproven {

    def apply(transaction: Transaction): Unproven =
      Unproven(
        transaction.inputs.keys.toList,
        transaction.feeOutput,
        transaction.coinOutputs,
        transaction.fee,
        transaction.timestamp,
        transaction.data,
        transaction.minting
      )
  }

}
