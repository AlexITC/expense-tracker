package net.wiringbits.components.pages

import net.wiringbits.api.ApiClient
import net.wiringbits.api.models.categories.{CreateCategory, GetCategories}
import net.wiringbits.common.models.CategoryName
import net.wiringbits.ui.forms.ExistingOrNewValue

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
              .createCategory(CreateCategory.Request(name = CategoryName.trusted(trimmed), description = None))
              .map(r =>
                (
                  r.id,
                  Some(
                    GetCategories.Response
                      .Category(id = r.id, name = r.name, createdAt = Instant.now(), description = None)
                  )
                )
              )
        }
    }

  /** Resolves a validated select/create selection to a category id: the picked id, else an existing category by
    * case-insensitive name, else a newly created one. The name is already a validated [[CategoryName]], so nothing is
    * parsed here.
    */
  private[pages] def findOrCreateCategory(
      client: ApiClient,
      categories: List[GetCategories.Response.Category],
      selection: ExistingOrNewValue[CategoryName, UUID]
  )(implicit ec: ExecutionContext): Future[UUID] =
    findOrCreateCategoryReturningCreated(client, categories, selection).map(_._1)

  /** Like [[findOrCreateCategory]], also returning the newly created category (if any) so the caller can add it to its
    * list.
    */
  private[pages] def findOrCreateCategoryReturningCreated(
      client: ApiClient,
      categories: List[GetCategories.Response.Category],
      selection: ExistingOrNewValue[CategoryName, UUID]
  )(implicit ec: ExecutionContext): Future[(UUID, Option[GetCategories.Response.Category])] =
    selection.id match {
      case Some(id) => Future.successful((id, None))
      case None =>
        categories.find(_.name.equalsIgnoreCase(selection.value.string)) match {
          case Some(existing) => Future.successful((existing.id, None))
          case None =>
            client
              .createCategory(CreateCategory.Request(name = selection.value, description = None))
              .map(r =>
                (
                  r.id,
                  Some(
                    GetCategories.Response
                      .Category(id = r.id, name = r.name, createdAt = Instant.now(), description = None)
                  )
                )
              )
        }
    }

  private[pages] def findOrCreateCategoryOpt(
      client: ApiClient,
      categories: List[GetCategories.Response.Category],
      selection: Option[ExistingOrNewValue[CategoryName, UUID]]
  )(implicit ec: ExecutionContext): Future[Option[UUID]] =
    selection match {
      case None => Future.successful(None)
      case Some(ref) => findOrCreateCategory(client, categories, ref).map(Some(_))
    }
}
