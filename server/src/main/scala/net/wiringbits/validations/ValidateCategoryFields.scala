package net.wiringbits.validations

import net.wiringbits.common.models.{CategoryDescription, CategoryName}

import scala.concurrent.Future

object ValidateCategoryFields {

  /** Validates the name and normalizes the description: blank becomes None, over-limit input fails. */
  def apply(name: String, description: Option[String]): Future[Option[CategoryDescription]] = {
    val result = for {
      _ <- CategoryName.parse(name)
      parsed <- CategoryDescription.parseOptional(description)
    } yield parsed
    result.fold(error => Future.failed(new RuntimeException(error)), Future.successful)
  }
}
