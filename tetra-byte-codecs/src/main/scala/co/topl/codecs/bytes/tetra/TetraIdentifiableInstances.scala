package co.topl.codecs.bytes.tetra

import co.topl.codecs.bytes.tetra.TetraImmutableCodecs._
import co.topl.codecs.bytes.typeclasses.Identifiable
import co.topl.codecs.bytes.typeclasses.implicits._
import co.topl.crypto.hash.Blake2b256
import co.topl.models._

trait TetraIdentifiableInstances {

  implicit val identifiableBlockHeaderV2: Identifiable[BlockHeaderV2] =
    (header: BlockHeaderV2) => {
      val bytes =
        header.parentHeaderId.allBytes ++ header.txRoot.data ++ header.bloomFilter.data ++ Bytes(
          BigInt(header.timestamp).toByteArray
        ) ++
        Bytes(BigInt(header.height).toByteArray) ++
        Bytes(BigInt(header.slot).toByteArray) ++
        header.eligibilityCertificate.immutableBytes ++
        header.operationalCertificate.immutableBytes ++
        Bytes(header.metadata.fold(Array.emptyByteArray)(_.data.bytes)) ++
        header.address.immutableBytes

      (IdentifierTypes.Block.HeaderV2, new Blake2b256().hash(bytes).data)
    }

  implicit val unprovenTransactionIdentifiable: Identifiable[Transaction.Unproven] =
    transaction => (IdentifierTypes.Transaction, new Blake2b256().hash(transaction.immutableBytes).data)

  implicit val transactionIdentifiable: Identifiable[Transaction] =
    transaction =>
      unprovenTransactionIdentifiable.idOf(
        Transaction
          .Unproven(
            transaction.inputs.map(i => Transaction.Unproven.Input(i.boxId, i.proposition, i.value)),
            transaction.outputs,
            transaction.chronology,
            transaction.data
          )
      )

}

object TetraIdentifiableInstances extends TetraIdentifiableInstances
