package bifrost.modifier.block

import bifrost.modifier.box.{ArbitBox, BifrostBoxSerializer}
import com.google.common.primitives.{Bytes, Ints, Longs}
import io.circe.Json
import io.circe.syntax._
import bifrost.nodeView.NodeViewModifier.ModifierTypeId
import bifrost.PersistentNodeViewModifier
import bifrost.modifier.block.Block._
import bifrost.crypto.{FastCryptographicHash, PrivateKey25519, Signature25519}
import bifrost.serialization.Serializer
import bifrost.modifier.transaction.bifrostTransaction.BifrostTransaction
import bifrost.modifier.box.proposition.ProofOfKnowledgeProposition
import bifrost.modifier.transaction.serialization.BifrostTransactionCompanion
import bifrost.nodeView.{NodeViewModifier, PersistentNodeViewModifier}
import scorex.crypto.encode.Base58
import scorex.crypto.signatures.Curve25519
import serializer.BloomTopics

import scala.annotation.tailrec
import scala.collection.BitSet
import scala.util.Try

/**
 * A block is an atomic piece of data network participates are agreed on.
 *
 * A block has:
 * - transactional data: a sequence of transactions, where a transaction is an atomic state update.
 * Some metadata is possible as well(transactions Merkle tree root, state Merkle tree root etc).
 *
 * - consensus data to check whether block was generated by a right party in a right way. E.g.
 * "baseTarget" & "generatorSignature" fields in the Nxt block structure, nonce & difficulty in the
 * Bitcoin block structure.
 *
 * - a signature(s) of a block generator(s)
 *
 * - additional data: block structure version no, timestamp etc
 */

case class Block(parentId: BlockId,
                 timestamp: Timestamp,
                 forgerBox: ArbitBox,
                 signature: Signature25519,
                 txs: Seq[BifrostTransaction],
                 inflation: Long = 0L,
                 protocolVersion: Version)
  extends PersistentNodeViewModifier[ProofOfKnowledgeProposition[PrivateKey25519], BifrostTransaction] {

  type M = Block

  lazy val modifierTypeId: Byte = Block.ModifierTypeId

  lazy val transactions: Option[Seq[BifrostTransaction]] = Some(txs)

  lazy val serializer = BlockCompanion

  lazy val version: Version = protocolVersion

  lazy val id: BlockId = FastCryptographicHash(serializer.messageToSign(this))

  lazy val json: Json = Map(
    "id" -> Base58.encode(id).asJson,
    "parentId" -> Base58.encode(parentId).asJson,
    "timestamp" -> timestamp.asJson,
    "generatorBox" -> Base58.encode(BifrostBoxSerializer.toBytes(forgerBox)).asJson,
    "signature" -> Base58.encode(signature.signature).asJson,
    "txs" -> txs.map(_.json).asJson,
    "inflation" -> inflation.asJson,
    "version" -> version.asJson,
    "blockSize" -> serializer.toBytes(this).length.asJson
  ).asJson
}

object Block {
  val BlockIdLength: Int = NodeViewModifier.ModifierIdSize
  val ModifierTypeId = 3: Byte
  val SignatureLength = 64

  type BlockId = NodeViewModifier.ModifierId
  type Timestamp = Long
  type Version = Byte
  type GenerationSignature = Array[Byte]
  type BaseTarget = Long

  def create(parentId: BlockId,
             timestamp: Block.Timestamp,
             txs: Seq[BifrostTransaction],
             box: ArbitBox,
             //attachment: Array[Byte],
             privateKey: PrivateKey25519,
             inflation: Long,
             version: Version): Block = {
    assert(box.proposition.pubKeyBytes sameElements privateKey.publicKeyBytes)

    val unsigned = Block(parentId, timestamp, box, Signature25519(Array.empty), txs, inflation, version)
    if (parentId sameElements Array.fill(32)(1: Byte)) {
      // genesis block will skip signature check
      val genesisSignature = Array.fill(Curve25519.SignatureLength25519)(1: Byte)
      unsigned.copy(signature = Signature25519(genesisSignature))
    } else {
      val signature = Curve25519.sign(privateKey.privKeyBytes, unsigned.bytes)
      unsigned.copy(signature = Signature25519(signature))
    }
  }

  def createBloom(txs: Seq[BifrostTransaction]): Array[Byte] = {
    val bloomBitSet = txs.foldLeft(BitSet.empty)(
      (total, b) =>
        b.bloomTopics match {
          case Some(e) => total ++ Bloom.calcBloom(e.head, e.tail)
          case None => total
        }
    ).toSeq
    BloomTopics(bloomBitSet).toByteArray
  }
}

