package net.wiringbits.validations

object ValidateSessionVersion {
  def apply(current: Option[Int], presented: Int): Unit = {
    current match {
      case None => throw new RuntimeException("The session user doesn't exist")
      case Some(version) if version != presented => throw new RuntimeException("The session was revoked")
      case Some(_) => ()
    }
  }
}
