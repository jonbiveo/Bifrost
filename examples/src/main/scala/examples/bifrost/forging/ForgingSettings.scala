package examples.bifrost.forging

import io.circe.syntax._
import scorex.core.settings.Settings

import scala.concurrent.duration._

trait ForgingSettings extends Settings with ForgingConstants {

  lazy val targetBlockDelay: Long = if (isTestnet) 10.seconds.toMillis else 5.seconds.toMillis

  val InitialDifficulty = 15000000L
  val MinimumDifficulty = 10000L

  lazy val offlineGeneration = settingsJSON.get("offlineGeneration").flatMap(_.asBoolean).getOrElse(false)

  lazy val posAttachmentSize = settingsJSON.get("posAttachmentSize").flatMap(_.asNumber).flatMap(_.toInt)
    .getOrElse(DefaulPtosAttachmentSize)

  val DefaulPtosAttachmentSize = 1024

  override def toString: String = (Map("BlockDelay" -> targetBlockDelay.asJson) ++
    settingsJSON.map(s => s._1 -> s._2)).asJson.spaces2
}
