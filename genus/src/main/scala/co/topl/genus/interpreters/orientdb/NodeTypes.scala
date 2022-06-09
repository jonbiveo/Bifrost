package co.topl.genus.interpreters.orientdb

object NodeTypes {
  case object CanonicalHead

  case class Header(
    id:             String,
    parentHeaderId: String,
    parentSlot:     Long,
    txRoot:         String,
    timestamp:      Long,
    height:         Long,
    slot:           Long,
    address:        String
  )

  case class Transaction(
    id:                String,
    creationTimestamp: Long,
    minimumSlot:       Long,
    maximumSlot:       Long,
    data:              Option[String]
  )

  // TODO: Edge to parent TransactionOutput
  case class TransactionInput(
    proposition: String,
    proof:       String
  )

  // TODO: Edge to address.spendingAddress
  // TODO: Edge to address.stakingAddress
  case class TransactionOutput(
    address: String,
    // TODO
//    value:   BoxValue,
    minting: Boolean
  )

}

object NodeSchemas {

  implicit val canonicalHeadNodeSchema: NodeSchema[NodeTypes.CanonicalHead.type] =
    NodeSchema.create("CanonicalHead", _ => Map.empty, _ => NodeTypes.CanonicalHead)

  implicit val headerNodeSchema: NodeSchema[NodeTypes.Header] =
    NodeSchema.create(
      "Header",
      h =>
        Map(
          "id"             -> h.id,
          "parentHeaderId" -> h.parentHeaderId,
          "parentSlot"     -> h.parentSlot,
          "txRoot"         -> h.txRoot,
          "timestamp"      -> h.timestamp,
          "height"         -> h.height,
          "slot"           -> h.slot,
          "address"        -> h.address
        ),
      v =>
        NodeTypes.Header(
          v("id"),
          v("parentHeaderId"),
          v("parentSlot"),
          v("txRoot"),
          v("timestamp"),
          v("height"),
          v("slot"),
          v("address")
        )
    )

  implicit val transactionNodeSchema: NodeSchema[NodeTypes.Transaction] =
    NodeSchema.create(
      "Transaction",
      t =>
        Map(
          "id"                -> t.id,
          "creationTimestamp" -> t.creationTimestamp,
          "minimumSlot"       -> t.minimumSlot,
          "maximumSlot"       -> t.maximumSlot,
          "data"              -> t.data
        ),
      v =>
        NodeTypes.Transaction(
          v("id"),
          v("creationTimestamp"),
          v("minimumSlot"),
          v("maximumSlot"),
          v("data")
        )
    )

  implicit val transactionInputNodeSchema: NodeSchema[NodeTypes.TransactionInput] =
    NodeSchema.create(
      "TransactionInput",
      t => Map("proposition" -> t.proposition, "proof" -> t.proof),
      v => NodeTypes.TransactionInput(v("proposition"), v("proof"))
    )

  implicit val transactionOutputNodeSchema: NodeSchema[NodeTypes.TransactionOutput] =
    NodeSchema.create(
      "TransactionOutput",
      t => Map("address" -> t.address, "minting" -> t.minting),
      v => NodeTypes.TransactionOutput(v("address"), v("minting"))
    )
}
