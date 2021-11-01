package co.topl.utils.codecs.binary.modifier.transaction

import cats.{Eq, Show}
import co.topl.attestation.Proposition
import co.topl.modifier.transaction.{ArbitTransfer, AssetTransfer}
import co.topl.utils.CommonGenerators
import co.topl.utils.codecs.binary.CodecCompatabilityBehavior
import co.topl.utils.codecs.binary.legacy.modifier.transaction.AssetTransferSerializer
import co.topl.utils.codecs.binary.modifier.codecs.assetTransferCodec

class AssetTransferCodecSpec extends CodecCompatabilityBehavior with CommonGenerators {

  implicit private val eq: Eq[AssetTransfer[_ <: Proposition]] = Eq.fromUniversalEquals
  implicit private val show: Show[AssetTransfer[_ <: Proposition]] = Show.fromToString

  codecCompatabilityBehavior("asset transfer", assetTransferCodec, AssetTransferSerializer, assetTransferGen)
}
