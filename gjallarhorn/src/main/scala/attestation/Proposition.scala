package attestation

import scala.util.{Failure, Success, Try}

/**
 * Propositions are challenges that must be satisfied by the prover.
 * In most cases, propositions are used by transactions issuers (spenders) to prove the right
 * to use a UTXO in a transaction.
 */
sealed trait Proposition extends BytesSerializable {

  val propTypeString: String
  val propTypePrefix: EvidenceTypePrefix

  override type M = Proposition
  override def serializer: GjalSerializer[Proposition] = PropositionSerializer

  def address(implicit networkPrefix: NetworkPrefix): Address

  override def toString: String = bytes.encodeAsBase58.show

  override def equals(obj: Any): Boolean = obj match {
    case prop: Proposition => prop.bytes sameElements bytes
    case _                 => false
  }

  override def hashCode(): Int = Ints.fromByteArray(bytes)
}

object Proposition {

  def fromBase58(data: Base58Data): Try[_ <: Proposition] = PropositionSerializer.parseBytes(data.value)

  def fromString(str: String): Try[_ <: Proposition] = Try(Base58Data.unsafe(str)).flatMap(fromBase58)

  implicit def jsonKeyEncoder[P <: Proposition]: KeyEncoder[P] = (prop: P) => prop.toString

  implicit val jsonKeyDecoder: KeyDecoder[Proposition] =
    json => Base58Data.validated(json).toOption.flatMap(fromBase58(_).toOption)
}

/**
 * Knowledge propositions require the prover to supply a proof attesting to their knowledge of secret information.
 * @tparam S secret type
 */
sealed trait KnowledgeProposition[S <: Secret] extends Proposition

/* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */

/**
 * A public key with a single signature
 * @param pubKeyBytes the public key bytes
 */
case class PublicKeyPropositionCurve25519(private[attestation] val pubKeyBytes: PublicKey)
    extends KnowledgeProposition[PrivateKeyCurve25519] {

  require(
    pubKeyBytes.value.length == Curve25519.KeyLength,
    s"Incorrect pubKey length, ${Curve25519.KeyLength} expected, ${pubKeyBytes.value.length} found"
  )

  val propTypeString: String = PublicKeyPropositionCurve25519.typeString
  val propTypePrefix: EvidenceTypePrefix = PublicKeyPropositionCurve25519.typePrefix

  def address(implicit networkPrefix: NetworkPrefix): Address = Address.from(this)

}

object PublicKeyPropositionCurve25519 {
  // type prefix used for address creation
  val typePrefix: EvidenceTypePrefix = 1: Byte
  val typeString: String = "PublicKeyCurve25519"

  def apply(str: String): PublicKeyPropositionCurve25519 =
    Proposition.fromString(str) match {
      case Success(pk: PublicKeyPropositionCurve25519) => pk
      case Success(_)                                  => throw new Error("Invalid proposition generation")
      case Failure(ex)                                 => throw ex
    }

  def fromBase58(data: Base58Data): PublicKeyPropositionCurve25519 =
    Proposition.fromBase58(data) match {
      case Success(prop: PublicKeyPropositionCurve25519) => prop
      case Success(_)                                    => throw new Error("Invalid proposition generation")
      case Failure(ex)                                   => throw ex
    }

  implicit val evProducer: EvidenceProducer[PublicKeyPropositionCurve25519] =
    EvidenceProducer.instance[PublicKeyPropositionCurve25519] { prop: PublicKeyPropositionCurve25519 =>
      Evidence(typePrefix, EvidenceContent(blake2b256.hash(prop.bytes)))
    }

  implicit val identifier: Identifiable[PublicKeyPropositionCurve25519] = Identifiable.instance { () =>
    Identifier(typeString, typePrefix)
  }

  // see circe documentation for custom encoder / decoders
  // https://circe.github.io/circe/codecs/custom-codecs.html
  implicit val jsonEncoder: Encoder[PublicKeyPropositionCurve25519] = (prop: PublicKeyPropositionCurve25519) =>
    prop.toString.asJson

  implicit val jsonKeyEncoder: KeyEncoder[PublicKeyPropositionCurve25519] = (prop: PublicKeyPropositionCurve25519) =>
    prop.toString
  implicit val jsonDecoder: Decoder[PublicKeyPropositionCurve25519] = Decoder[Base58Data].map(fromBase58)
  implicit val jsonKeyDecoder: KeyDecoder[PublicKeyPropositionCurve25519] = KeyDecoder[Base58Data].map(fromBase58)
}

/* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */

/**
 * A multi-signature proposition
 * @param threshold the number of signatures required
 * @param pubKeyProps the set of public keys
 */
case class ThresholdPropositionCurve25519(threshold: Int, pubKeyProps: Set[PublicKeyPropositionCurve25519])
    extends KnowledgeProposition[PrivateKeyCurve25519] {

  pubKeyProps.foreach { prop =>
    require(
      prop.pubKeyBytes.value.length == Curve25519.KeyLength,
      s"Incorrect pubKey length, ${Curve25519.KeyLength} expected, ${prop.pubKeyBytes.value.length} found"
    )
  }

  val propTypeString: String = ThresholdPropositionCurve25519.typeString
  val propTypePrefix: EvidenceTypePrefix = ThresholdPropositionCurve25519.typePrefix

  def address(implicit networkPrefix: NetworkPrefix): Address = Address.from(this)

}

object ThresholdPropositionCurve25519 {
  // type prefix used for address creation
  val typePrefix: EvidenceTypePrefix = 2: Byte
  val typeString: String = "ThresholdCurve25519"

  def apply(str: String): ThresholdPropositionCurve25519 =
    Proposition.fromString(str) match {
      case Success(prop: ThresholdPropositionCurve25519) => prop
      case Success(_)                                    => throw new Error("Invalid proposition generation")
      case Failure(ex)                                   => throw ex
    }

  def fromBase58(data: Base58Data): ThresholdPropositionCurve25519 =
    Proposition.fromBase58(data) match {
      case Success(prop: ThresholdPropositionCurve25519) => prop
      case Success(_)                                    => throw new Error("Invalid proposition generation")
      case Failure(ex)                                   => throw ex
    }

  implicit val evProducer: EvidenceProducer[ThresholdPropositionCurve25519] =
    EvidenceProducer.instance[ThresholdPropositionCurve25519] { prop: ThresholdPropositionCurve25519 =>
      Evidence(typePrefix, EvidenceContent(blake2b256.hash(prop.bytes)))
    }

  implicit val identifier: Identifiable[ThresholdPropositionCurve25519] = Identifiable.instance { () =>
    Identifier(typeString, typePrefix)
  }

  // see circe documentation for custom encoder / decoders
  // https://circe.github.io/circe/codecs/custom-codecs.html
  implicit val jsonEncoder: Encoder[ThresholdPropositionCurve25519] = (prop: ThresholdPropositionCurve25519) =>
    prop.toString.asJson

  implicit val jsonKeyEncoder: KeyEncoder[ThresholdPropositionCurve25519] = (prop: ThresholdPropositionCurve25519) =>
    prop.toString
  implicit val jsonDecoder: Decoder[ThresholdPropositionCurve25519] = Decoder[Base58Data].map(fromBase58)
  implicit val jsonKeyDecoder: KeyDecoder[ThresholdPropositionCurve25519] = KeyDecoder[Base58Data].map(fromBase58)
}
