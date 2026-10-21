package utils

import net.wiringbits.common.models.{Email, Name}

import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/** Random values for columns that are UNIQUE across the whole database, so tests don't collide with each other. */
object RandomData {
  def randomString(prefix: String = "t"): String = prefix + UUID.randomUUID().toString.replace("-", "")

  def randomEmail(): Email = Email.trusted(s"test-${UUID.randomUUID()}@wiringbits.net")

  def randomName(): Name = Name.trusted(randomString("name"))

  def randomTelegramUserId(): Long = ThreadLocalRandom.current().nextLong(1L, Long.MaxValue)

  def randomTokenHash(): String =
    (UUID.randomUUID().toString + UUID.randomUUID().toString).replace("-", "")
}
