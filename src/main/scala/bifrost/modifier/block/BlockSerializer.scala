package bifrost.modifier.block

import bifrost.crypto.Signature25519
import bifrost.crypto.serialization.Signature25519Serializer
import bifrost.modifier.ModifierId
import bifrost.modifier.box.ArbitBox
import bifrost.modifier.box.serialization.BoxSerializer
import bifrost.modifier.transaction.bifrostTransaction.Transaction
import bifrost.modifier.transaction.serialization.TransactionSerializer
import bifrost.utils.{bytesToId, idToBytes}
import bifrost.utils.serialization.{BifrostSerializer, Reader, Writer}
import bifrost.utils.Extensions._
import com.google.common.primitives.{Bytes, Ints, Longs}

import scala.annotation.tailrec
import scala.util.Try

object BlockSerializer extends BifrostSerializer[Block] {

  override def serialize(block: Block, w: Writer): Unit = {
    /* parentId: ModifierId */
    w.putBytes(block.parentId.hashBytes)

    /* timestamp: Long */
    w.putULong(block.timestamp)

    /* version: Byte */
    w.put(block.version)

    /* generatorBox: ArbitBox */
    BoxSerializer.serialize(block.forgerBox, w)

    /* inflation: Long */
    w.putLong(block.inflation)

    /* signature: Signature25519 */
    Signature25519Serializer.serialize(block.signature, w)

    /* txsLength: Int */
    w.putUInt(block.txs.length)

    /* txs: Seq[Transaction] */
    block.txs.foreach(tx => TransactionSerializer.serialize(tx, w))
  }

  override def parse(r: Reader): Block = {
    // The order of the getByte, getLong... calls should not be changed

    // TODO: Jing - maybe we could check that the size of bytes to read in reader is less or equal to the max size of a block

    // TODO: Jing - here using ModifierId instead of bytesToId, we could get rid of bytesToId soon
    val parentId: ModifierId = ModifierId(r.getBytes(Block.blockIdLength))

    val timestamp: Long = r.getULong()

    // TODO: Jing - scorex uses toIntExact to make sure the Long does not exceed the length of an Int
    // TODO: Jing - generatorBoxLen is probably not useful anymore
    //    val generatorBoxLen: Int = r.getUInt().toIntExact

    // Version should be used in the future to determine if we need additional procedures in parsing
    val version: Byte = r.getByte()

    // TODO: Jing - BoxSerializer.parseBytes: should switch to using .getBytes later
    val generatorBox: ArbitBox = BoxSerializer.parse(r).asInstanceOf[ArbitBox]

    // TODO: Jing - Can inflation be negative?
    val inflation: Long = r.getLong()

    val signature: Signature25519 = Signature25519Serializer.parse(r)

    val txsLength: Int = r.getUInt().toIntExact
    /* implement parse in TransactionSerializer and its specific transactionCompanions 3 layer of companions */
    val txs: Seq[Transaction] = (0 until txsLength).map(_ => TransactionSerializer.parse(r))

    Block(parentId, timestamp, generatorBox, signature, txs, inflation, version)
  }
}
