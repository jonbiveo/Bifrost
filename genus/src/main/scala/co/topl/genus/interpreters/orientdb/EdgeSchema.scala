package co.topl.genus.interpreters.orientdb

@simulacrum.typeclass
trait EdgeSchema[T, Src, Dest] {
  def name: String
  def encode(t:          T): Map[String, AnyRef]
  def decode(properties: Map[String, AnyRef]): T
}

object EdgeSchema {

  def create[T, Src, Dest](
    name:   String,
    encode: T => Map[String, AnyRef],
    decode: DecodeHelper => T
  ): EdgeSchema[T, Src, Dest] = {
    val _name = name
    val _encode = encode
    val _decode = decode
    new EdgeSchema[T, Src, Dest] {
      def name: String = _name

      def encode(t: T): Map[String, AnyRef] = _encode(t)

      def decode(properties: Map[String, AnyRef]): T = _decode(new DecodeHelper(properties))
    }
  }
}
