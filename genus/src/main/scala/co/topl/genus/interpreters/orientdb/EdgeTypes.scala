package co.topl.genus.interpreters.orientdb

object EdgeTypes {
  case object CanonicalHead
  case object HeaderToParentHeader
  case class TransactionToHeader(index: Short)
  case class TransactionToInput(index: Short)
  case class TransactionToOutput(index: Short)
  case object InputToOutput
}

object EdgeSchemas {

  implicit val canonicalHeadEdgeSchema
    : EdgeSchema[EdgeTypes.CanonicalHead.type, NodeTypes.CanonicalHead.type, NodeTypes.Header] =
    EdgeSchema.create("CanonicalHead", _ => Map.empty, _ => EdgeTypes.CanonicalHead)

  implicit val headerToParentEdgeSchema
    : EdgeSchema[EdgeTypes.HeaderToParentHeader.type, NodeTypes.Header, NodeTypes.Header] =
    EdgeSchema.create("HeaderToParentHeader", _ => Map.empty, _ => EdgeTypes.HeaderToParentHeader)

  implicit val transactionToHeaderEdgeSchema
    : EdgeSchema[EdgeTypes.TransactionToHeader, NodeTypes.Transaction, NodeTypes.Header] =
    EdgeSchema.create(
      "TransactionToHeader",
      t => Map("index" -> t.index),
      v => EdgeTypes.TransactionToHeader(v("index"))
    )

  implicit val transactionToInputEdgeSchema
    : EdgeSchema[EdgeTypes.TransactionToInput, NodeTypes.Transaction, NodeTypes.TransactionInput] =
    EdgeSchema.create("TransactionToInput", t => Map("index" -> t.index), v => EdgeTypes.TransactionToInput(v("index")))

  implicit val transactionToOutputEdgeSchema
    : EdgeSchema[EdgeTypes.TransactionToOutput, NodeTypes.Transaction, NodeTypes.TransactionOutput] =
    EdgeSchema.create(
      "TransactionToOutput",
      t => Map("index" -> t.index),
      v => EdgeTypes.TransactionToOutput(v("index"))
    )

  implicit val inputToOutputEdgeSchema
    : EdgeSchema[EdgeTypes.InputToOutput.type, NodeTypes.TransactionInput, NodeTypes.TransactionOutput] =
    EdgeSchema.create("InputToOutput", _ => Map.empty, _ => EdgeTypes.InputToOutput)

}
