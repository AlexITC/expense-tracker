package net.wiringbits.models

import com.typesafe.config.Config
import play.api.ConfigLoader

case class JevApiKey(string: String) extends SecretValue(string)

object JevApiKey {

  implicit val configLoader: ConfigLoader[JevApiKey] = (config: Config, path: String) => {
    JevApiKey(string = config.getString(path))
  }
}
