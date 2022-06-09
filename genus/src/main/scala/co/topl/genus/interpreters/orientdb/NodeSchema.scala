package co.topl.genus.interpreters.orientdb

import com.tinkerpop.blueprints.impls.orient.OrientVertex

@simulacrum.typeclass
trait NodeSchema[T] {
  def name: String
  def encode(t:          T): Map[String, AnyRef]
  def decode(properties: Map[String, AnyRef]): T
}

object NodeSchema {

  def create[T](name: String, encode: T => Map[String, AnyRef], decode: DecodeHelper => T): NodeSchema[T] = {
    val _name = name
    val _encode = encode
    val _decode = decode
    new NodeSchema[T] {
      def name: String = _name

      def encode(t: T): Map[String, AnyRef] = _encode(t)

      def decode(properties: Map[String, AnyRef]): T = _decode(new DecodeHelper(properties))
    }
  }
}

class DecodeHelper(properties: Map[String, AnyRef]) {
  def apply[T](name: String): T = properties(name).asInstanceOf[T]
}
