package co.topl.utils

import cats.data.{Validated, ValidatedNec}
import cats.implicits._

import scala.language.implicitConversions

/**
 * A typeclass which encodes a value to a byte array
 *
 * Usage:
 * {{{
 * import cats.implicits._
 * import co.topl.utils.AsBytes.implicits._
 *
 * sealed abstract class StringAsBytesFailure
 * implicit val stringAsBytes: AsBytes[StringAsBytesFailure, String] = _.getBytes("UTF-8").validNec
 *
 * "Test".encodeAsBytes[StringAsBytesFailure]
 * }}}
 * @tparam Decoded The domain-specific representation
 * @tparam EncodingFailure The domain-specific failure conditions from encoding the domain object.  If encoding is
 *                         never expected to fail, you may specify `Infallible` as the type which grants access to
 *                         a helper operation for direct encoding without validation
 */
trait AsBytes[EncodingFailure, Decoded] {
  def encode(decoded: Decoded): ValidatedNec[EncodingFailure, Array[Byte]]
}

object AsBytes {

  def infallible[Decoded](f: Decoded => Array[Byte]): AsBytes[Infallible, Decoded] =
    f(_).validNec[Infallible]

  class Ops[T](val instance: T) extends AnyVal {

    def encodeAsBytes[EncodingFailure](implicit
      encoder: AsBytes[EncodingFailure, T]
    ): ValidatedNec[EncodingFailure, Array[Byte]] =
      encoder.encode(instance)

    /**
     * Encodes the given value as bytes, but guarantees a successful result.
     * @param encoder An infallible encoder.  Not all encoders are infallible.  Use #encodeAsBytes for fallible encoders.
     * @return a byte array
     */
    def infalliblyEncodeAsBytes(implicit encoder: AsBytes[Infallible, T]): Array[Byte] =
      encoder.encode(instance) match {
        case Validated.Valid(a)   => a
        case Validated.Invalid(e) => throw new IllegalStateException(s"Infallible encoder failed: $e")
      }
  }

  trait ToOps {
    implicit def toEncoderOps[T](target: T): Ops[T] = new Ops(target)
  }

  trait Instances {
    val identityBytesEncoder: AsBytes[Nothing, Array[Byte]] = _.validNec[Nothing]
  }

  object implicits extends ToOps with Instances
}

/**
 * A typeclass which decodes a value from a byte array
 *
 * Usage:
 * {{{
 * import cats.implicits._
 * import co.topl.utils.FromBytes.implicits._
 *
 * sealed abstract class BytesAsStringFailure
 * implicit val bytesAsString: FromBytes[BytesAsStringFailure, String] = new String(_, "UTF-8").validNec
 *
 * val bytes: Array[Byte] = ???
 * bytes.decodeTo[BytesAsStringFailure, String]
 * }}}
 * @tparam Decoded The domain-specific representation
 * @tparam DecodeFailure The domain-specific failure conditions from decoding the domain object.  If decoding is
 *                         never expected to fail, you may specify `Infallible` as the type which grants access to
 *                         a helper operation for direct decoding without validation
 */
trait FromBytes[DecodeFailure, Decoded] {
  def decode(encoded: Array[Byte]): ValidatedNec[DecodeFailure, Decoded]
}

object FromBytes {

  def infallible[Decoded](f: Array[Byte] => Decoded): FromBytes[Infallible, Decoded] =
    f(_).validNec[Infallible]

  class Ops(val instance: Array[Byte]) extends AnyVal {

    def decodeTo[DecodeFailure, Decoded](implicit
      decoder: FromBytes[DecodeFailure, Decoded]
    ): ValidatedNec[DecodeFailure, Decoded] =
      decoder.decode(instance)

    /**
     * Decodes the given value from bytes, but guarantees a successful result.
     * @param decoder An infallible decoder.  Not all decoder are infallible.  Use #decodeTo for fallible decoder.
     * @return a Decoded value
     */
    def infalliblyDecodeTo[Decoded](implicit decoder: FromBytes[Infallible, Decoded]): Decoded =
      decoder.decode(instance) match {
        case Validated.Valid(a)   => a
        case Validated.Invalid(e) => throw new IllegalStateException(s"Infallible decoder failed: $e")
      }
  }

  trait ToOps {
    implicit def toDecoderOps(target: Array[Byte]): Ops = new Ops(target)
  }

  trait Instances {
    val identityBytesDecoder: FromBytes[Nothing, Array[Byte]] = _.validNec[Nothing]
  }

  object implicits extends ToOps with Instances
}

/**
 * This type is used in Codecs that have no failure cases and are only expected to succeed.  Because the constructor
 * is private, implementations are unable to instantiate one, thus guaranteeing a successful encode or decode attempt.
 */
final class Infallible private ()
