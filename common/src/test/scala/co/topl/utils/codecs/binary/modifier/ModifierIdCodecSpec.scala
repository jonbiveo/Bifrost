package co.topl.utils.codecs.binary.modifier

import cats.{Eq, Show}
import co.topl.modifier.ModifierId
import co.topl.utils.CommonGenerators
import co.topl.utils.codecs.binary.CodecCompatabilityBehavior
import co.topl.utils.codecs.binary.legacy.modifier.ModifierIdSerializer
import co.topl.utils.codecs.binary.modifier.codecs.modifierIdCodec

class ModifierIdCodecSpec extends CodecCompatabilityBehavior with CommonGenerators {

  implicit private val eq: Eq[ModifierId] = Eq.fromUniversalEquals
  implicit private val show: Show[ModifierId] = Show.fromToString

  codecCompatabilityBehavior("modifier ID", modifierIdCodec, ModifierIdSerializer, modifierIdGen)
}
