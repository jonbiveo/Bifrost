package attestation

import scala.reflect.ClassTag
import scala.util.Try

/**
 * The most general abstraction of fact a prover can provide a non-interactive proof
 * to open a box or to modify an account
 *
 * A proof is non-interactive and thus serializable
 */
sealed trait Proof[P <: Proposition] extends BytesSerializable {

  def isValid(proposition: P, message: Array[Byte]): Boolean

  override type M = Proof[_]

  override def serializer: GjalSerializer[Proof[_]] = ProofSerializer

  override def toString: String = bytes.encodeAsBase58.show

  override def equals(obj: Any): Boolean = obj match {
    case pr: Proof[_] => pr.bytes sameElements bytes
    case _            => false
  }

  override def hashCode(): Int = Ints.fromByteArray(bytes)

}

object Proof {

  sealed trait ProofFromBase58DataError
  case class ProofParseFailure(error: Throwable) extends ProofFromBase58DataError
  case class ParsedIncorrectSignatureType() extends ProofFromBase58DataError

  sealed trait ProofFromStringDataError
  case class InvalidBase58() extends ProofFromStringDataError
  case class ProofFromDataError(inner: ProofFromBase58DataError) extends ProofFromStringDataError

  def fromBase58[T <: Proof[_]: ClassTag](data: Base58Data): Either[ProofFromBase58DataError, T] =
    ProofSerializer.parseBytes(data.value).toEither.leftMap(ProofParseFailure).flatMap {
      case t: T => Right(t)
      case _    => Left(ParsedIncorrectSignatureType())
    }

  def fromString[T <: Proof[_]: ClassTag](str: String): Either[ProofFromStringDataError, T] =
    Base58Data
      .validated(str)
      .toEither
      .leftMap(_ => InvalidBase58())
      .flatMap(fromBase58[T](_).leftMap(ProofFromDataError))

  implicit def jsonEncoder[PR <: Proof[_]]: Encoder[PR] = (proof: PR) => proof.toString.asJson

  implicit def jsonDecoder: Decoder[Proof[_]] = Decoder[Base58Data].emap(fromBase58(_).leftMap(_.toString))
}

/** The proof for a given type of [[Secret]] and [[KnowledgeProposition]] */
sealed trait ProofOfKnowledge[S <: Secret, P <: KnowledgeProposition[S]] extends Proof[P]

/* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */

/**
 * A proof corresponding to a PublicKeyCurve25519 proposition. This is a zero-knowledge proof that argues knowledge of
 * the underlying private key associated with a public key
 *
 * @param signature 25519 signature
 */
case class SignatureCurve25519(private[attestation] val signature: Signature)
    extends ProofOfKnowledge[PrivateKeyCurve25519, PublicKeyPropositionCurve25519] {

  private val signatureLength = signature.value.length

  require(
    signatureLength == 0 || signatureLength == Curve25519.SignatureLength,
    s"$signatureLength != ${Curve25519.SignatureLength}"
  )

  def isValid(proposition: PublicKeyPropositionCurve25519, message: Array[Byte]): Boolean =
    Curve25519.verify(signature, message, proposition.pubKeyBytes)
}

object SignatureCurve25519 {
  lazy val signatureSize: Int = Curve25519.SignatureLength

  /** Helper function to create empty signatures */
  lazy val empty: SignatureCurve25519 = SignatureCurve25519(Signature(Array.emptyByteArray))

  /** Returns a signature filled with 1's for use in genesis signatures */
  lazy val genesis: SignatureCurve25519 =
    SignatureCurve25519(Signature(Array.fill(SignatureCurve25519.signatureSize)(1: Byte)))

  // DummyImplicit required because Base58Data and Signature have same base type after type erasure
  def apply(data: Base58Data)(implicit dummyImplicit: DummyImplicit): SignatureCurve25519 =
    Proof.fromBase58[SignatureCurve25519](data) match {
      case Right(sig)  => sig
      case Left(error) => throw new Exception(s"Error while parsing proof: $error")
    }

  def apply(str: String): SignatureCurve25519 =
    Base58Data.validated(str).map(apply).valueOr(err => throw new Exception(s"Invalid Base-58 String: $err"))

  // see circe documentation for custom encoder / decoders
  // https://circe.github.io/circe/codecs/custom-codecs.html
  implicit val jsonEncoder: Encoder[SignatureCurve25519] = (sig: SignatureCurve25519) => sig.toString.asJson
  implicit val jsonKeyEncoder: KeyEncoder[SignatureCurve25519] = (sig: SignatureCurve25519) => sig.toString
  implicit val jsonDecoder: Decoder[SignatureCurve25519] = Decoder[Base58Data].map(apply)
  implicit val jsonKeyDecoder: KeyDecoder[SignatureCurve25519] = KeyDecoder[Base58Data].map(apply)
}

/* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */
/**
 * A proof corresponding to a ThresholdPropositionCurve25519 proposition. This is a zero-knowledge proof that argues
 * knowledge of the underlying private key associated with a public key
 *
 * @param signatures set of 25519 signatures
 */
case class ThresholdSignatureCurve25519(private[attestation] val signatures: Set[SignatureCurve25519])
    extends ProofOfKnowledge[PrivateKeyCurve25519, ThresholdPropositionCurve25519] {

  signatures.foreach { sig =>
    require(sig.signature.value.length == SignatureCurve25519.signatureSize)
  }

  override def isValid(proposition: ThresholdPropositionCurve25519, message: Array[Byte]): Boolean = Try {
    // check that we have at least m signatures
    // JAA - the Try wraps this to expression so this check may prematurely exit evaluation and return false
    //       (i.e. the check should fail quickly)
    require(proposition.pubKeyProps.size >= proposition.threshold)

    // only need to check until the threshold is exceeded
    val numValidSigs = signatures.foldLeft(0) { (acc, sig) =>
      if (acc < proposition.threshold) {
        if (
          proposition.pubKeyProps
            .exists(prop => Curve25519.verify(sig.signature, message, prop.pubKeyBytes))
        ) {
          1
        } else {
          0
        }
      } else {
        0
      }
    }

    require(numValidSigs >= proposition.threshold)

  }.isSuccess

}

object ThresholdSignatureCurve25519 {

  def apply(data: Base58Data): ThresholdSignatureCurve25519 =
    Proof.fromBase58[ThresholdSignatureCurve25519](data) match {
      case Right(sig)  => sig
      case Left(error) => throw new Exception(s"Invalid signature: $error")
    }

  def apply(str: String): ThresholdSignatureCurve25519 =
    Base58Data.validated(str).map(apply).valueOr(err => throw new Exception(s"Invalid Base-58 String: $err"))

  /** Helper function to create empty signatures */
  def empty(): ThresholdSignatureCurve25519 = ThresholdSignatureCurve25519(Set[SignatureCurve25519]())

  // see circe documentation for custom encoder / decoders
  // https://circe.github.io/circe/codecs/custom-codecs.html
  implicit val jsonEncoder: Encoder[ThresholdSignatureCurve25519] = (sig: ThresholdSignatureCurve25519) =>
    sig.toString.asJson

  implicit val jsonKeyEncoder: KeyEncoder[ThresholdSignatureCurve25519] = (sig: ThresholdSignatureCurve25519) =>
    sig.toString
  implicit val jsonDecoder: Decoder[ThresholdSignatureCurve25519] = Decoder[Base58Data].map(apply)
  implicit val jsonKeyDecoder: KeyDecoder[ThresholdSignatureCurve25519] = KeyDecoder[Base58Data].map(apply)
}
