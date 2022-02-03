package co.topl.codecs.bytes

import co.topl.codecs.bytes.ByteCodec.ops._
import co.topl.models
import co.topl.models.BlockHeaderV2.Unsigned
import co.topl.models.Proofs.Knowledge
import co.topl.models._
import co.topl.models.utility.HasLength.instances._
import co.topl.models.utility.Lengths._
import co.topl.models.utility.StringDataTypes.Latin1Data
import co.topl.models.utility.{KesBinaryTree, Length, Lengths, Ratio, Sized}

trait BasicCodecs {

  implicit def strictSizedBytesCodec[L <: Length](implicit l: L): ByteCodec[Sized.Strict[Bytes, L]] =
    new ByteCodec[Sized.Strict[Bytes, L]] {
      def encode(t: Sized.Strict[Bytes, L], writer: Writer): Unit = writer.putBytes(t.data.toArray)

      def decode(reader: Reader): Sized.Strict[Bytes, L] = Sized.strictUnsafe(Bytes(reader.getBytes(l.value)))
    }

  implicit def strictSizedTypedBytesCodec[L <: Length](implicit l: L): ByteCodec[Sized.Strict[TypedBytes, L]] =
    new ByteCodec[Sized.Strict[TypedBytes, L]] {
      def encode(t: Sized.Strict[TypedBytes, L], writer: Writer): Unit = writer.putBytes(t.data.allBytes.toArray)

      def decode(reader: Reader): Sized.Strict[TypedBytes, L] =
        Sized.strictUnsafe(TypedBytes(Bytes(reader.getBytes(l.value))))
    }

  implicit def seqCodec[T: ByteCodec]: ByteCodec[Seq[T]] = new ByteCodec[Seq[T]] {

    def encode(t: Seq[T], writer: Writer): Unit = {
      writer.putInt(t.length)
      t.foreach(_.writeBytesTo(writer))
    }

    def decode(reader: Reader): Seq[T] =
      Seq.fill(reader.getInt())(ByteCodec[T].decode(reader))
  }

  implicit val longCodec: ByteCodec[Long] =
    new ByteCodec[Long] {
      def encode(t: Long, writer: Writer): Unit = writer.putLong(t)

      def decode(reader: Reader): Long = reader.getLong()
    }

  implicit def tuple2Codec[T1: ByteCodec, T2: ByteCodec]: ByteCodec[(T1, T2)] =
    new ByteCodec[(T1, T2)] {

      def encode(t: (T1, T2), writer: Writer): Unit = {
        t._1.writeBytesTo(writer)
        t._2.writeBytesTo(writer)
      }

      def decode(reader: Reader): (T1, T2) =
        (ByteCodec[T1].decode(reader), ByteCodec[T2].decode(reader))
    }

  implicit def tuple3Codec[T1: ByteCodec, T2: ByteCodec, T3: ByteCodec]: ByteCodec[(T1, T2, T3)] =
    new ByteCodec[(T1, T2, T3)] {

      def encode(t: (T1, T2, T3), writer: Writer): Unit = {
        t._1.writeBytesTo(writer)
        t._2.writeBytesTo(writer)
        t._3.writeBytesTo(writer)
      }

      def decode(reader: Reader): (T1, T2, T3) =
        (ByteCodec[T1].decode(reader), ByteCodec[T2].decode(reader), ByteCodec[T3].decode(reader))
    }

  implicit def optionCodec[T: ByteCodec]: ByteCodec[Option[T]] = new ByteCodec[Option[T]] {

    def encode(t: Option[T], writer: Writer): Unit =
      t match {
        case Some(value) =>
          writer.putInt(1)
          ByteCodec[T].encode(value, writer)
        case None =>
          writer.putInt(0)
      }

    def decode(reader: Reader): Option[T] =
      Option.when(reader.getInt() == 1)(ByteCodec[T].decode(reader))
  }

  implicit val taktikosAddressCodec: ByteCodec[TaktikosAddress] =
    new ByteCodec[TaktikosAddress] {

      override def encode(t: TaktikosAddress, writer: Writer): Unit = {
        t.paymentVKEvidence.writeBytesTo(writer)
        t.poolVK.writeBytesTo(writer)
        t.signature.writeBytesTo(writer)
      }

      override def decode(reader: Reader): TaktikosAddress = ???
    }

  implicit val int128Codec: ByteCodec[Int128] =
    new ByteCodec[Int128] {

      def encode(t: Int128, writer: Writer): Unit = {
        val arr = t.data.toByteArray
        writer.putInt(arr.length)
        writer.putBytes(arr)
      }

      def decode(reader: Reader): Int128 =
        Sized.maxUnsafe[BigInt, Lengths.`128`.type](BigInt(reader.getBytes(reader.getInt)))
    }

  implicit val sizedMaxLatin1DataCodec: ByteCodec[Sized.Max[Latin1Data, Lengths.`32`.type]] =
    new ByteCodec[Sized.Max[Latin1Data, Lengths.`32`.type]] {

      def encode(t: Sized.Max[Latin1Data, Lengths.`32`.type], writer: Writer): Unit = {
        writer.putInt(t.data.value.length)
        writer.putBytes(t.data.value)
      }

      def decode(reader: Reader): Sized.Max[Latin1Data, Lengths.`32`.type] =
        Sized.maxUnsafe(
          Latin1Data.fromData(reader.getBytes(reader.getInt()))
        )
    }

  implicit val typedBytesCodec: ByteCodec[TypedBytes] =
    new ByteCodec[TypedBytes] {

      def encode(t: TypedBytes, writer: Writer): Unit = {
        writer.putInt(t.allBytes.length.toInt)
        writer.putBytes(t.allBytes.toArray)
      }

      def decode(reader: Reader): TypedBytes =
        TypedBytes(Bytes(reader.getBytes(reader.getInt())))
    }

  implicit val blockHeaderV2Codec: ByteCodec[BlockHeaderV2] = new ByteCodec[BlockHeaderV2] {

    override def encode(t: BlockHeaderV2, writer: Writer): Unit = {
      t.parentHeaderId.writeBytesTo(writer)
      writer.putLong(t.parentSlot)
      t.txRoot.writeBytesTo(writer)
      t.bloomFilter.writeBytesTo(writer)
      writer.putLong(t.timestamp)
      writer.putLong(t.slot)
      t.eligibilityCertificate.writeBytesTo(writer)
      t.operationalCertificate.writeBytesTo(writer)
      t.metadata.writeBytesTo(writer)
      t.address.writeBytesTo(writer)
    }

    override def decode(reader: Reader): BlockHeaderV2 =
      BlockHeaderV2(
        ByteCodec[TypedBytes].decode(reader),
        reader.getLong(),
        ByteCodec[TxRoot].decode(reader),
        ByteCodec[BloomFilter].decode(reader),
        reader.getLong(),
        reader.getLong(),
        reader.getLong(),
        ByteCodec[EligibilityCertificate].decode(reader),
        ByteCodec[OperationalCertificate].decode(reader),
        ByteCodec[Option[Sized.Max[Latin1Data, Lengths.`32`.type]]].decode(reader),
        ByteCodec[TaktikosAddress].decode(reader)
      )
  }

  implicit val blockBodyV2Codec: ByteCodec[BlockBodyV2] = new ByteCodec[BlockBodyV2] {

    override def encode(t: BlockBodyV2, writer: Writer): Unit = {
      t.headerId.writeBytesTo(writer)
      t.transactions.writeBytesTo(writer)
    }

    override def decode(reader: Reader): BlockBodyV2 =
      BlockBodyV2(
        ByteCodec[TypedBytes].decode(reader),
        ByteCodec[Seq[Transaction]].decode(reader)
      )
  }

  implicit val blockV2Codec: ByteCodec[BlockV2] =
    new ByteCodec[BlockV2] {

      def encode(t: BlockV2, writer: Writer): Unit = {
        t.headerV2.writeBytesTo(writer)
        t.blockBodyV2.writeBytesTo(writer)
      }

      def decode(reader: Reader): BlockV2 =
        BlockV2(
          ByteCodec[BlockHeaderV2].decode(reader),
          ByteCodec[BlockBodyV2].decode(reader)
        )
    }

  implicit val blockV1Codec: ByteCodec[BlockV1] = new ByteCodec[BlockV1] {
    override def encode(t: BlockV1, writer: Writer): Unit = ???

    override def decode(reader: Reader): BlockV1 = ???
  }

  implicit val transactionCodec: ByteCodec[Transaction] = new ByteCodec[Transaction] {
    override def encode(t: Transaction, writer: Writer): Unit = ???

    override def decode(reader: Reader): Transaction = ???
  }

  implicit val boxCodec: ByteCodec[Box[_]] = new ByteCodec[Box[_]] {
    override def encode(t: Box[_], writer: Writer): Unit = ???

    override def decode(reader: Reader): Box[_] = ???
  }

  implicit val ratioCodec: ByteCodec[Ratio] = new ByteCodec[Ratio] {

    override def encode(t: Ratio, writer: Writer): Unit = {
      val numeratorBytes = t.numerator.toByteArray
      writer.putInt(numeratorBytes.length)
      writer.putBytes(numeratorBytes)
      val denominatorBytes = t.denominator.toByteArray
      writer.putInt(denominatorBytes.length)
      writer.putBytes(denominatorBytes)
    }

    override def decode(reader: Reader): Ratio =
      Ratio(
        BigInt(reader.getBytes(reader.getInt())),
        BigInt(reader.getBytes(reader.getInt()))
      )
  }

  implicit val publicKeyEd25519Codec: ByteCodec[VerificationKeys.Ed25519] = new ByteCodec[VerificationKeys.Ed25519] {

    def encode(t: VerificationKeys.Ed25519, writer: Writer): Unit =
      t.bytes.writeBytesTo(writer)

    def decode(reader: Reader): VerificationKeys.Ed25519 =
      VerificationKeys.Ed25519(ByteCodec[Sized.Strict[Bytes, VerificationKeys.Ed25519.Length]].decode(reader))

  }

  implicit val privateKeyCurve25519Codec: ByteCodec[SecretKeys.Curve25519] = new ByteCodec[SecretKeys.Curve25519] {
    def encode(t: SecretKeys.Curve25519, writer: Writer): Unit = writer.putBytes(t.bytes.data.toArray)

    def decode(reader: Reader): SecretKeys.Curve25519 =
      SecretKeys.Curve25519(ByteCodec[Sized.Strict[Bytes, SecretKeys.Curve25519.Length]].decode(reader))

  }

  implicit val privateKeyEd25519Codec: ByteCodec[SecretKeys.Ed25519] = new ByteCodec[SecretKeys.Ed25519] {
    def encode(t: SecretKeys.Ed25519, writer: Writer): Unit = writer.putBytes(t.bytes.data.toArray)

    def decode(reader: Reader): SecretKeys.Ed25519 =
      SecretKeys.Ed25519(ByteCodec[Sized.Strict[Bytes, SecretKeys.Ed25519.Length]].decode(reader))

  }

  implicit val publicKeyExtendedEd25519Codec: ByteCodec[VerificationKeys.ExtendedEd25519] =
    new ByteCodec[VerificationKeys.ExtendedEd25519] {

      def encode(t: VerificationKeys.ExtendedEd25519, writer: Writer): Unit = {
        t.vk.writeBytesTo(writer)
        t.chainCode.writeBytesTo(writer)
      }

      def decode(reader: Reader): VerificationKeys.ExtendedEd25519 =
        VerificationKeys.ExtendedEd25519(
          ByteCodec[VerificationKeys.Ed25519].decode(reader),
          ByteCodec[Sized.Strict[Bytes, VerificationKeys.ExtendedEd25519.ChainCodeLength]].decode(reader)
        )
    }

  implicit val privateKeyExtendedEd25519Codec: ByteCodec[SecretKeys.ExtendedEd25519] =
    new ByteCodec[SecretKeys.ExtendedEd25519] {

      def encode(t: SecretKeys.ExtendedEd25519, writer: Writer): Unit = {
        writer.putBytes(t.leftKey.data.toArray)
        writer.putBytes(t.rightKey.data.toArray)
        writer.putBytes(t.chainCode.data.toArray)
      }

      def decode(reader: Reader): SecretKeys.ExtendedEd25519 =
        SecretKeys.ExtendedEd25519(
          ByteCodec[Sized.Strict[Bytes, SecretKeys.ExtendedEd25519.LeftLength]].decode(reader),
          ByteCodec[Sized.Strict[Bytes, SecretKeys.ExtendedEd25519.RightLength]].decode(reader),
          ByteCodec[Sized.Strict[Bytes, SecretKeys.ExtendedEd25519.ChainCodeLength]].decode(reader)
        )

    }

  implicit val kesBinaryTreeCodec: ByteCodec[KesBinaryTree] =
    new ByteCodec[KesBinaryTree] {

      def encode(t: KesBinaryTree, writer: Writer): Unit = t match {
        case KesBinaryTree.MerkleNode(seed, witnessLeft, witnessRight, left, right) =>
          writer.putUByte(0)
          writer.putBytes(seed)
          writer.putBytes(witnessLeft)
          writer.putBytes(witnessRight)
          encode(left, writer)
          encode(right, writer)
        case KesBinaryTree.SigningLeaf(sk, vk) =>
          writer.putUByte(1)
          writer.putBytes(sk)
          writer.putBytes(vk)
        case KesBinaryTree.Empty =>
          writer.putUByte(2)
      }

      def decode(reader: Reader): KesBinaryTree =
        reader.getUByte() match {
          case 0 =>
            KesBinaryTree.MerkleNode(
              reader.getBytes(32),
              reader.getBytes(32),
              reader.getBytes(32),
              decode(reader),
              decode(reader)
            )
          case 1 =>
            KesBinaryTree.SigningLeaf(
              reader.getBytes(32),
              reader.getBytes(32)
            )
          case 2 =>
            KesBinaryTree.Empty
        }
    }

  implicit val kesSumProofCodec: ByteCodec[Proofs.Knowledge.KesSum] =
    new ByteCodec[Knowledge.KesSum] {

      def encode(t: Knowledge.KesSum, writer: Writer): Unit = {
        t.verificationKey.writeBytesTo(writer)
        t.signature.writeBytesTo(writer)
        seqCodec[Sized.Strict[Bytes, Proofs.Knowledge.KesSum.DigestLength]].encode(t.witness, writer)
      }

      def decode(reader: Reader): Knowledge.KesSum =
        Knowledge.KesSum(
          ByteCodec[VerificationKeys.Ed25519].decode(reader),
          ByteCodec[Proofs.Knowledge.Ed25519].decode(reader),
          seqCodec[Sized.Strict[Bytes, Proofs.Knowledge.KesSum.DigestLength]].decode(reader).toVector
        )
    }

  implicit val secretKeyKesProductCodec: ByteCodec[SecretKeys.KesProduct] =
    new ByteCodec[SecretKeys.KesProduct] {

      def encode(t: SecretKeys.KesProduct, writer: Writer): Unit = {
        t.superTree.writeBytesTo(writer)
        t.subTree.writeBytesTo(writer)
        writer.putBytes(t.nextSubSeed)
        t.subSignature.writeBytesTo(writer)
        writer.putLong(t.offset)
      }

      def decode(reader: Reader): SecretKeys.KesProduct =
        SecretKeys.KesProduct(
          ByteCodec[KesBinaryTree].decode(reader),
          ByteCodec[KesBinaryTree].decode(reader),
          reader.getBytes(32),
          ByteCodec[Proofs.Knowledge.KesSum].decode(reader),
          reader.getLong()
        )

    }

  implicit val proofSignatureEd25519Codec: ByteCodec[Proofs.Knowledge.Ed25519] =
    new ByteCodec[Proofs.Knowledge.Ed25519] {

      def encode(t: Proofs.Knowledge.Ed25519, writer: Writer): Unit =
        t.bytes.writeBytesTo(writer)

      def decode(reader: Reader): Proofs.Knowledge.Ed25519 =
        Proofs.Knowledge.Ed25519(ByteCodec[Sized.Strict[Bytes, Proofs.Knowledge.Ed25519.Length]].decode(reader))
    }

  implicit val vrfSignatureCodec: ByteCodec[Proofs.Knowledge.VrfEd25519] =
    new ByteCodec[Proofs.Knowledge.VrfEd25519] {

      def encode(t: Proofs.Knowledge.VrfEd25519, writer: Writer): Unit =
        t.bytes.writeBytesTo(writer)

      def decode(reader: Reader): Proofs.Knowledge.VrfEd25519 =
        Proofs.Knowledge.VrfEd25519(ByteCodec[Sized.Strict[Bytes, Proofs.Knowledge.VrfEd25519.Length]].decode(reader))
    }

  implicit val vkVrfCodec: ByteCodec[VerificationKeys.VrfEd25519] =
    new ByteCodec[VerificationKeys.VrfEd25519] {

      def encode(t: VerificationKeys.VrfEd25519, writer: Writer): Unit =
        t.bytes.writeBytesTo(writer)

      def decode(reader: Reader): VerificationKeys.VrfEd25519 =
        VerificationKeys.VrfEd25519(ByteCodec[Sized.Strict[Bytes, VerificationKeys.VrfEd25519.Length]].decode(reader))
    }

  implicit val eligibilityCertificateCodec: ByteCodec[EligibilityCertificate] = new ByteCodec[EligibilityCertificate] {

    override def encode(t: EligibilityCertificate, writer: Writer): Unit = {
      t.vrfSig.writeBytesTo(writer)
      t.vkVRF.writeBytesTo(writer)
      writer.putBytes(t.thresholdEvidence.data.toArray)
    }

    override def decode(reader: Reader): EligibilityCertificate =
      EligibilityCertificate(
        ByteCodec[Proofs.Knowledge.VrfEd25519].decode(reader),
        ByteCodec[VerificationKeys.VrfEd25519].decode(reader),
        ByteCodec[Evidence].decode(reader),
        ByteCodec[Eta].decode(reader)
      )
  }

  implicit val operationalCertificateCodec: ByteCodec[OperationalCertificate] =
    new ByteCodec[OperationalCertificate] {

      def encode(t: OperationalCertificate, writer: Writer): Unit = {
        t.parentVK.writeBytesTo(writer)
        t.parentSignature.writeBytesTo(writer)
        t.childVK.writeBytesTo(writer)
        t.childSignature.writeBytesTo(writer)
      }

      def decode(reader: Reader): OperationalCertificate = OperationalCertificate(
        ByteCodec[VerificationKeys.KesProduct].decode(reader),
        ByteCodec[Proofs.Knowledge.KesProduct].decode(reader),
        ByteCodec[VerificationKeys.Ed25519].decode(reader),
        ByteCodec[Proofs.Knowledge.Ed25519].decode(reader)
      )
    }

  implicit val kesProductVKCodec: ByteCodec[VerificationKeys.KesProduct] =
    new ByteCodec[VerificationKeys.KesProduct] {

      def encode(t: VerificationKeys.KesProduct, writer: Writer): Unit = {
        t.bytes.writeBytesTo(writer)
        writer.putInt(t.step)
      }

      def decode(reader: Reader): VerificationKeys.KesProduct =
        VerificationKeys.KesProduct(
          ByteCodec[Sized.Strict[Bytes, VerificationKeys.KesProduct.Length]].decode(reader),
          reader.getInt()
        )
    }

  implicit val kesProductSignatureCodec: ByteCodec[Proofs.Knowledge.KesProduct] =
    new ByteCodec[Proofs.Knowledge.KesProduct] {

      def encode(t: Proofs.Knowledge.KesProduct, writer: Writer): Unit = {
        t.superSignature.writeBytesTo(writer)
        t.subSignature.writeBytesTo(writer)
        t.subRoot.writeBytesTo(writer)
      }

      def decode(reader: Reader): Proofs.Knowledge.KesProduct =
        Proofs.Knowledge.KesProduct(
          ByteCodec[Proofs.Knowledge.KesSum].decode(reader),
          ByteCodec[Proofs.Knowledge.KesSum].decode(reader),
          ByteCodec[Sized.Strict[Bytes, Proofs.Knowledge.KesProduct.DigestLength]].decode(reader)
        )
    }

  implicit val partialOperationalCertificateCodec: ByteCodec[BlockHeaderV2.Unsigned.PartialOperationalCertificate] =
    new ByteCodec[Unsigned.PartialOperationalCertificate] {

      def encode(t: Unsigned.PartialOperationalCertificate, writer: Writer): Unit = {
        t.parentVK.writeBytesTo(writer)
        t.parentSignature.writeBytesTo(writer)
        t.childVK.writeBytesTo(writer)
      }

      def decode(reader: Reader): BlockHeaderV2.Unsigned.PartialOperationalCertificate =
        BlockHeaderV2.Unsigned.PartialOperationalCertificate(
          ByteCodec[VerificationKeys.KesProduct].decode(reader),
          ByteCodec[Proofs.Knowledge.KesProduct].decode(reader),
          ByteCodec[VerificationKeys.Ed25519].decode(reader)
        )
    }

  implicit val registrationBoxValueCodec: ByteCodec[Box.Values.TaktikosRegistration] =
    new ByteCodec[Box.Values.TaktikosRegistration] {

      def encode(t: Box.Values.TaktikosRegistration, writer: Writer): Unit =
        t.commitment.writeBytesTo(writer)

      def decode(reader: Reader): Box.Values.TaktikosRegistration =
        Box.Values.TaktikosRegistration(
          ByteCodec[Proofs.Knowledge.KesProduct].decode(reader)
        )
    }
}

object BasicCodecs extends BasicCodecs
