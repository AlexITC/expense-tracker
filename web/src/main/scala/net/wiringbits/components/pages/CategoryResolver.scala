package net.wiringbits.components.pages

import net.wiringbits.api.ApiClient
import net.wiringbits.api.models.categories.{CreateCategory, GetCategories}

import java.time.Instant
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/** Resolves the category a submit should use, shared by the forms that let the user pick or type one. */
object CategoryResolver {
  // Picked id, else an existing category by case-insensitive name, else a newly created one (returned so the caller
  // can add it to its list).
  private[pages] def resolveOrCreateCategory(
      client: ApiClient,
      categories: List[GetCategories.Response.Category],
      pickedId: Option[UUID],
      name: String
  )(implicit ec: ExecutionContext): Future[(UUID, Option[GetCategories.Response.Category])] =
    pickedId match {
      case Some(id) => Future.successful((id, None))
      case None =>
        val trimmed = name.trim
        categories.find(_.name.equalsIgnoreCase(trimmed)) match {
          case Some(existing) => Future.successful((existing.id, None))
          case None =>
            client
              .createCategory(CreateCategory.Request(name = trimmed))
              .map(r =>
                (r.id, Some(GetCategories.Response.Category(id = r.id, name = r.name, createdAt = Instant.now())))
              )
        }
    }
}
