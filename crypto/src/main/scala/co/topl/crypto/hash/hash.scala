package co.topl.crypto

import cats.data.{NonEmptyChain, Validated}
import io.estatico.newtype.macros.newtype

/* Forked from https://github.com/input-output-hk/scrypto */

package object hash {

  @newtype
  case class Digest(toBytes: Array[Byte])

  object Digest {

    /** Gets a validated Digest guaranteed to be the correct digest size.
     * @param bytes the bytes to convert to a digest
     * @return the digest or an invalid error
     */
    def validated(bytes: Array[Byte]): Validated[InvalidDigestError, Digest] =
      Validated.cond(bytes.length == 32, Digest(bytes), IncorrectSize)

  }

  sealed trait InvalidDigestError
  case object IncorrectSize extends InvalidDigestError

  /** A hash function which hashes a message into a digest of type T.
   * @tparam T the digest type
   */
  trait Hash[T] {
    val digestSize: Int
    def hash(prefix: Option[Byte], messages: NonEmptyChain[Array[Byte]]): Digest
  }

  object Hash {

    /** Instantiates the hash function from the implicit scope.
     * @tparam T the hash type
     * @return the hash function
     */
    def apply[T : Hash]: Hash[T] = implicitly[Hash[T]]

    /** Hashes the given message.
     * @param message the message to hash
     * @tparam T the hash type
     * @return the hash digest
     */
    def apply[T : Hash](message: Array[Byte]): Digest =
      apply.hash(None, NonEmptyChain(message))

    /** Hashes the given set of messages with a prefix
     * @param prefix the prefix to apply to the hash
     * @param messages the messages to hash
     * @tparam T the hash type
     * @return the hash digest
     */
    def apply[T : Hash](prefix: Byte, messages: NonEmptyChain[Array[Byte]]): Digest =
      apply.hash(Some(prefix), messages)

    /** Hashes the given message with a prefix.
     * @param prefix the prefix to apply to the hash
     * @param message the message to hash
     * @tparam T the hash type
     * @return a hashed digest
     */
    def apply[T : Hash](prefix: Byte, message: Array[Byte]): Digest =
      apply.hash(Some(prefix), NonEmptyChain(message))

    /** Hashes the given message.
     * @param message the message to hash
     * @tparam T the hash type
     * @return the hash digest
     */
    def apply[T : Hash](message: String): Digest =
      apply.hash(None, NonEmptyChain(message.getBytes))

    /** Gets the digest size produced by the hash.
     * @tparam T the hash type
     * @return the size of the hash digest
     */
    def digestSize[T : Hash]: Int = apply.digestSize

  }

}
