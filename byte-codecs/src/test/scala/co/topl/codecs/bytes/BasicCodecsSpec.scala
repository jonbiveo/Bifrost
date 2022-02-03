package co.topl.codecs.bytes

import cats.data.{Chain, NonEmptyChain}
import co.topl.codecs.bytes.implicits._
import co.topl.models.utility.HasLength.instances.bytesLength
import co.topl.models.utility.Sized
import co.topl.models.{
  BlockBodyV2,
  BlockHeaderV2,
  BlockV2,
  Bytes,
  DionAddress,
  EligibilityCertificate,
  Int128,
  NetworkPrefix,
  OperationalCertificate,
  Proofs,
  TaktikosAddress,
  Transaction,
  TypedBytes,
  TypedEvidence,
  VerificationKeys
}
import org.scalamock.scalatest.MockFactory
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

import scala.collection.immutable.ListMap

class BasicCodecsSpec
    extends AnyFlatSpec
    with ScalaCheckDrivenPropertyChecks
    with Matchers
    with MockFactory
    with EitherValues {

  behavior of "BasicCodecs"

  val addresses = List(
    TaktikosAddress(
      Sized.strictUnsafe(Bytes.fill(32)(1: Byte)),
      VerificationKeys.Ed25519(Sized.strictUnsafe(Bytes.fill(32)(1: Byte))),
      Proofs.Knowledge.Ed25519(Sized.strictUnsafe(Bytes.fill(64)(1: Byte)))
    )
  )

  val dionAddresses = List(
    DionAddress(NetworkPrefix(1: Byte), TypedEvidence(1: Byte, Sized.strictUnsafe(Bytes.fill(32)(1: Byte))))
  )

  it should "encode and decode a taktikos address" in {
    testCodec(addresses(0))
  }

  it should "encode and decode typed evidence" in {
    testCodec(dionAddresses(0).typedEvidence)
  }

  it should "encode and decode a dion address" in {
    testCodec(dionAddresses(0))
  }

  it should "encode and decode a coin output" in {
    testCodec(Transaction.CoinOutputs.Arbit(dionAddresses(0), addresses(0), Int128(50)): Transaction.CoinOutput)
  }

  it should "Encode and decode a transaction" in {
    val tx = Transaction(
      inputs = ListMap.empty,
      feeOutput = None,
      coinOutputs = NonEmptyChain(
        Transaction.CoinOutputs.Arbit(dionAddresses(0), addresses(0), Int128(50)): Transaction.CoinOutput
      ),
      consensusOutputs = Chain(),
      fee = Int128(0),
      timestamp = 0L,
      data = None,
      minting = false
    )

    testCodec(tx)
  }

  val header = BlockHeaderV2(
    parentHeaderId = TypedBytes(1: Byte, Bytes.fill(32)(1: Byte)),
    parentSlot = 1,
    txRoot = Sized.strictUnsafe(Bytes.fill(32)(1: Byte)),
    bloomFilter = Sized.strictUnsafe(Bytes.fill(256)(1: Byte)),
    timestamp = 2,
    height = 2,
    slot = 2,
    eligibilityCertificate = EligibilityCertificate(
      Proofs.Knowledge.VrfEd25519(Sized.strictUnsafe(Bytes.fill(80)(1: Byte))),
      VerificationKeys.VrfEd25519(Sized.strictUnsafe(Bytes.fill(32)(1: Byte))),
      Sized.strictUnsafe(Bytes.fill(32)(1: Byte)),
      Sized.strictUnsafe(Bytes.fill(32)(1: Byte))
    ),
    operationalCertificate = OperationalCertificate(
      VerificationKeys.KesProduct(Sized.strictUnsafe(Bytes.fill(32)(1: Byte)), 0),
      Proofs.Knowledge.KesProduct(
        Proofs.Knowledge.KesSum(
          VerificationKeys.Ed25519(Sized.strictUnsafe(Bytes.fill(32)(1: Byte))),
          Proofs.Knowledge.Ed25519(Sized.strictUnsafe(Bytes.fill(64)(1: Byte))),
          Vector.empty
        ),
        Proofs.Knowledge.KesSum(
          VerificationKeys.Ed25519(Sized.strictUnsafe(Bytes.fill(32)(1: Byte))),
          Proofs.Knowledge.Ed25519(Sized.strictUnsafe(Bytes.fill(64)(1: Byte))),
          Vector.empty
        ),
        Sized.strictUnsafe(Bytes.fill(32)(1: Byte))
      ),
      VerificationKeys.Ed25519(Sized.strictUnsafe(Bytes.fill(32)(1: Byte))),
      Proofs.Knowledge.Ed25519(Sized.strictUnsafe(Bytes.fill(64)(1: Byte)))
    ),
    metadata = None,
    address = addresses(0)
  )

  it should "encode and decode a header" in {

    testCodec(header.operationalCertificate)
    testCodec(header.eligibilityCertificate)

    testCodec(header)
  }

  it should "encode and decode a block" in {
    val body =
      BlockBodyV2(TypedBytes(1: Byte, Bytes.fill(32)(1: Byte)), Nil)

    testCodec(BlockV2(header, body))
  }

  private def testCodec[T: ByteCodec](t: T): Unit =
    t.bytes.decoded[T] shouldBe t

}
