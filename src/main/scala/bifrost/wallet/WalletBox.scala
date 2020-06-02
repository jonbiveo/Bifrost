package bifrost.wallet

import bifrost.modifier.box.GenericBox
import bifrost.modifier.box.proposition.Proposition
import bifrost.serialization.BytesSerializable
import bifrost.utils.serialization.BifrostSerializer
import scorex.crypto.encode.Base58

case class WalletBox[T, P <: Proposition, B <: GenericBox[P, T]]
  (box: B, transactionId: Array[Byte], createdAt: Long)(subclassDeser: BifrostSerializer[B])
  extends BytesSerializable {

  override type M = WalletBox[T, P, B]

  override def serializer: BifrostSerializer[WalletBox[T, P, B]] =
    new WalletBoxSerializer[T, P, B](subclassDeser)

  override def toString: String = s"WalletBox($box, ${Base58.encode(transactionId)}, $createdAt)"
}
