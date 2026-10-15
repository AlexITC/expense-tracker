package net.wiringbits.validations

import net.wiringbits.repositories.MerchantsRepository

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

object ValidateMerchantNameIsAvailable {
  def apply(
      repository: MerchantsRepository,
      userId: UUID,
      name: String,
      excludeId: Option[UUID] = None
  )(implicit ec: ExecutionContext): Future[Unit] = {
    for {
      maybe <- repository.findByName(userId, name)
    } yield {
      val conflict = maybe.exists(m => !excludeId.contains(m.id))
      if (conflict) throw new RuntimeException("A merchant with that name already exists")
    }
  }
}
