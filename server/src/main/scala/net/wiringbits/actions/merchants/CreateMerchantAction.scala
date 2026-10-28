package net.wiringbits.actions.merchants

import com.github.f4b6a3.uuid.UuidCreator
import io.scalaland.chimney.dsl.*
import net.wiringbits.api.models.merchants.CreateMerchant
import net.wiringbits.config.MerchantsConfig
import net.wiringbits.repositories.MerchantsRepository
import net.wiringbits.repositories.models.Merchant

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class CreateMerchantAction @Inject() (
    merchantsRepository: MerchantsRepository,
    merchantsConfig: MerchantsConfig
)(implicit ec: ExecutionContext, clock: Clock) {

  def apply(userId: UUID, request: CreateMerchant.Request): Future[CreateMerchant.Response] = {
    for {
      _ <- validate(userId, request)
      now = clock.instant()
      create = request
        .into[Merchant.Create]
        .withFieldConst(_.id, UuidCreator.getTimeOrderedEpoch())
        .withFieldConst(_.userId, userId)
        .withFieldConst(_.reviewed, true)
        .withFieldConst(_.createdAt, now)
        .withFieldConst(_.updatedAt, now)
        .transform
      _ <- merchantsRepository.create(create)
    } yield create.transformInto[CreateMerchant.Response]
  }

  private def validate(userId: UUID, request: CreateMerchant.Request): Future[Unit] = {
    if (request.name.isBlank) Future.failed(new RuntimeException("The name is required"))
    else if (request.name.length > 100) Future.failed(new RuntimeException("Name must be 100 characters or less"))
    else if (request.notes.exists(_.length > 255))
      Future.failed(new RuntimeException("Notes must be 255 characters or less"))
    else if (request.taxId.exists(_.isBlank))
      Future.failed(new RuntimeException("Tax ID cannot be blank"))
    else if (request.taxId.exists(_.length > 50))
      Future.failed(new RuntimeException("Tax ID must be 50 characters or less"))
    else
      merchantsRepository.count(userId).flatMap { count =>
        if (count >= merchantsConfig.maxPerUser)
          Future.failed(new RuntimeException(s"Maximum number of merchants (${merchantsConfig.maxPerUser}) reached"))
        else Future.unit
      }
  }
}
