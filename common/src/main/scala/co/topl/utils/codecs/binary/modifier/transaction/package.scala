package co.topl.utils.codecs.binary.modifier

import co.topl.attestation._
import co.topl.modifier.box.Box.Nonce
import co.topl.modifier.box.{SimpleValue, TokenValueHolder}
import co.topl.modifier.transaction.{ArbitTransfer, AssetTransfer, PolyTransfer, Transaction}
import co.topl.utils.Identifiable
import co.topl.utils.codecs.binary.attestation.codecs._
import co.topl.utils.codecs.binary.modifier.box.codecs._
import co.topl.utils.codecs.binary.valuetypes.codecs._
import co.topl.utils.codecs.binary.valuetypes.implicits._
import scodec.{Attempt, Codec, Err}
import scodec.codecs.{byte, discriminated}
import shapeless._

import scala.collection.immutable.ListMap

package object transaction {

  trait Codecs {

    implicit def polyTransferWithPropositionCodec[P <: Proposition: Identifiable: EvidenceProducer]
      : Codec[PolyTransfer[P]] =
      (listCodec(tupleCodec(addressCodec, longCodec)).as[IndexedSeq[(Address, Nonce)]] ::
        listCodec(
          tupleCodec(
            addressCodec,
            tokenValueHolderCodec.xmap[SimpleValue](token => token.asInstanceOf[SimpleValue], value => value)
          )
        ).as[IndexedSeq[(Address, SimpleValue)]] ::
        listMapCodec(propositionCodec, proofCodec) ::
        int128Codec ::
        uLongCodec ::
        optionCodec(latin1DataCodec) ::
        boolCodec)
        .xmapc { case from :: to :: attestation :: fee :: timestamp :: data :: minting :: HNil =>
          PolyTransfer[P](
            from,
            to,
            attestation.asInstanceOf[ListMap[P, Proof[P]]],
            fee,
            timestamp,
            data,
            minting
          )
        } { polyTransfer =>
          HList(
            polyTransfer.from,
            polyTransfer.to,
            polyTransfer.attestation.asInstanceOf[ListMap[Proposition, Proof[P]]],
            polyTransfer.fee,
            polyTransfer.timestamp,
            polyTransfer.data,
            polyTransfer.minting
          )
        }
        .as[PolyTransfer[P]]

    implicit def arbitTransferWithPropositionCodec[P <: Proposition: Identifiable: EvidenceProducer]
      : Codec[ArbitTransfer[P]] =
      (listCodec(tupleCodec(addressCodec, longCodec)).as[IndexedSeq[(Address, Nonce)]] ::
        listCodec(
          tupleCodec(
            addressCodec,
            tokenValueHolderCodec.xmap[SimpleValue](token => token.asInstanceOf[SimpleValue], value => value)
          )
        ).as[IndexedSeq[(Address, SimpleValue)]] ::
        listMapCodec(propositionCodec, proofCodec) ::
        int128Codec ::
        uLongCodec ::
        optionCodec(latin1DataCodec) ::
        boolCodec)
        .xmapc { case from :: to :: attestation :: fee :: timestamp :: data :: minting :: HNil =>
          ArbitTransfer[P](from, to, attestation.asInstanceOf[ListMap[P, Proof[P]]], fee, timestamp, data, minting)
        } { arbitTransfer =>
          HList(
            arbitTransfer.from,
            arbitTransfer.to,
            arbitTransfer.attestation.asInstanceOf[ListMap[Proposition, Proof[P]]],
            arbitTransfer.fee,
            arbitTransfer.timestamp,
            arbitTransfer.data,
            arbitTransfer.minting
          )
        }
        .as[ArbitTransfer[P]]

    implicit def assetTransferWithPropositionCodec[P <: Proposition: Identifiable: EvidenceProducer]
      : Codec[AssetTransfer[P]] =
      (listCodec(tupleCodec(addressCodec, longCodec)).as[IndexedSeq[(Address, Nonce)]] ::
        listCodec(tupleCodec(addressCodec, tokenValueHolderCodec)).as[IndexedSeq[(Address, TokenValueHolder)]] ::
        listMapCodec(propositionCodec, proofCodec) ::
        int128Codec ::
        uLongCodec ::
        optionCodec(latin1DataCodec) ::
        boolCodec)
        .xmapc { case from :: to :: attestation :: fee :: timestamp :: data :: minting :: HNil =>
          AssetTransfer[P](from, to, attestation.asInstanceOf[ListMap[P, Proof[P]]], fee, timestamp, data, minting)
        } { assetTransfer =>
          HList(
            assetTransfer.from,
            assetTransfer.to,
            assetTransfer.attestation.asInstanceOf[ListMap[Proposition, Proof[P]]],
            assetTransfer.fee,
            assetTransfer.timestamp,
            assetTransfer.data,
            assetTransfer.minting
          )
        }
        .as[AssetTransfer[P]]

    implicit val polyTransferCodec: Codec[PolyTransfer[_ <: Proposition]] =
      byteCodec.consume[PolyTransfer[_ <: Proposition]] {
        case PublicKeyPropositionCurve25519.typePrefix =>
          polyTransferWithPropositionCodec[PublicKeyPropositionCurve25519]
            .exmapc[PolyTransfer[_ <: Proposition]](transfer => Attempt.successful(transfer)) {
              case x: PolyTransfer[PublicKeyPropositionCurve25519] =>
                Attempt.successful(x)
              case _ => Attempt.failure(Err("invalid proposition type"))
            }
        case ThresholdPropositionCurve25519.typePrefix =>
          polyTransferWithPropositionCodec[ThresholdPropositionCurve25519]
            .exmapc[PolyTransfer[_ <: Proposition]](transfer => Attempt.successful(transfer)) {
              case x: PolyTransfer[ThresholdPropositionCurve25519] =>
                Attempt.successful(x)
              case _ => Attempt.failure(Err("invalid proposition type"))
            }
        case PublicKeyPropositionEd25519.typePrefix =>
          polyTransferWithPropositionCodec[PublicKeyPropositionEd25519]
            .exmapc[PolyTransfer[_ <: Proposition]](transfer => Attempt.successful(transfer)) {
              case x: PolyTransfer[PublicKeyPropositionEd25519] =>
                Attempt.successful(x)
              case _ => Attempt.failure(Err("invalid proposition type"))
            }
      }(transfer => transfer.getPropIdentifier.typePrefix)

    implicit val arbitTransferCodec: Codec[ArbitTransfer[_ <: Proposition]] =
      byteCodec.consume[ArbitTransfer[_ <: Proposition]] {
        case PublicKeyPropositionCurve25519.typePrefix =>
          arbitTransferWithPropositionCodec[PublicKeyPropositionCurve25519]
            .exmapc[ArbitTransfer[_ <: Proposition]](transfer => Attempt.successful(transfer)) {
              case x: ArbitTransfer[PublicKeyPropositionCurve25519] =>
                Attempt.successful(x)
              case _ => Attempt.failure(Err("invalid proposition type"))
            }
        case ThresholdPropositionCurve25519.typePrefix =>
          arbitTransferWithPropositionCodec[ThresholdPropositionCurve25519]
            .exmapc[ArbitTransfer[_ <: Proposition]](transfer => Attempt.successful(transfer)) {
              case x: ArbitTransfer[ThresholdPropositionCurve25519] =>
                Attempt.successful(x)
              case _ => Attempt.failure(Err("invalid proposition type"))
            }
        case PublicKeyPropositionEd25519.typePrefix =>
          arbitTransferWithPropositionCodec[PublicKeyPropositionEd25519]
            .exmapc[ArbitTransfer[_ <: Proposition]](transfer => Attempt.successful(transfer)) {
              case x: ArbitTransfer[PublicKeyPropositionEd25519] =>
                Attempt.successful(x)
              case _ => Attempt.failure(Err("invalid proposition type"))
            }
      }(transfer => transfer.getPropIdentifier.typePrefix)

    implicit val assetTransferCodec: Codec[AssetTransfer[_ <: Proposition]] =
      byteCodec.consume[AssetTransfer[_ <: Proposition]] {
        case PublicKeyPropositionCurve25519.typePrefix =>
          assetTransferWithPropositionCodec[PublicKeyPropositionCurve25519]
            .exmapc[AssetTransfer[_ <: Proposition]](transfer => Attempt.successful(transfer)) {
              case x: AssetTransfer[PublicKeyPropositionCurve25519] =>
                Attempt.successful(x)
              case _ => Attempt.failure(Err("invalid proposition type"))
            }
        case ThresholdPropositionCurve25519.typePrefix =>
          assetTransferWithPropositionCodec[ThresholdPropositionCurve25519]
            .exmapc[AssetTransfer[_ <: Proposition]](transfer => Attempt.successful(transfer)) {
              case x: AssetTransfer[ThresholdPropositionCurve25519] =>
                Attempt.successful(x)
              case _ => Attempt.failure(Err("invalid proposition type"))
            }
        case PublicKeyPropositionEd25519.typePrefix =>
          assetTransferWithPropositionCodec[PublicKeyPropositionEd25519]
            .exmapc[AssetTransfer[_ <: Proposition]](transfer => Attempt.successful(transfer)) {
              case x: AssetTransfer[PublicKeyPropositionEd25519] =>
                Attempt.successful(x)
              case _ => Attempt.failure(Err("invalid proposition type"))
            }
      }(transfer => transfer.getPropIdentifier.typePrefix)

    implicit val transactionCodec: Codec[Transaction.TX] =
      discriminated[Transaction.TX]
        .by(byte)
        .typecase(PolyTransfer.typePrefix, polyTransferCodec)
        .typecase(ArbitTransfer.typePrefix, arbitTransferCodec)
        .typecase(AssetTransfer.typePrefix, assetTransferCodec)
  }

  object codecs extends Codecs

}
