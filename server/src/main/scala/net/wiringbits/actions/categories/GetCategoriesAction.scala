package net.wiringbits.actions.categories

import io.scalaland.chimney.dsl.*
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.repositories.CategoriesRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetCategoriesAction @Inject() (categoriesRepository: CategoriesRepository)(implicit ec: ExecutionContext) {

  def apply(userId: UUID): Future[GetCategories.Response] = {
    for {
      categories <- categoriesRepository.all(userId)
      items = categories.map(_.transformInto[GetCategories.Response.Category])
    } yield GetCategories.Response(items)
  }
}
