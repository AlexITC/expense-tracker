package net.wiringbits.components.pages

import net.wiringbits.api.ApiClient
import net.wiringbits.api.models.merchants.{CreateMerchant, GetMerchants}
import net.wiringbits.common.models.MerchantName
import net.wiringbits.ui.forms.ExistingOrNewValue

import java.time.Instant
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/** Resolves the merchant a submit should use, shared by the forms that let the user pick or type one. */
object MerchantResolver {
  // Picked id, else an existing merchant by case-insensitive name, else a newly created one (returned so the caller
  // can add it to its list). A newly created merchant takes the given category as its default. The name is already a
  // validated MerchantName, so nothing is parsed here.
  private[pages] def findOrCreateMerchant(
      client: ApiClient,
      merchants: List[GetMerchants.Response.Merchant],
      selection: ExistingOrNewValue[MerchantName, UUID],
      categoryId: Option[UUID]
  )(implicit ec: ExecutionContext): Future[(UUID, Option[GetMerchants.Response.Merchant])] =
    selection.id match {
      case Some(id) => Future.successful((id, None))
      case None =>
        merchants.find(_.name.equalsIgnoreCase(selection.value.string)) match {
          case Some(existing) => Future.successful((existing.id, None))
          case None =>
            client
              .createMerchant(
                CreateMerchant.Request(name = selection.value, notes = None, categoryId = categoryId, taxId = None)
              )
              .map { resp =>
                val created = GetMerchants.Response.Merchant(
                  id = resp.id,
                  name = resp.name,
                  notes = None,
                  categoryId = categoryId,
                  taxId = None,
                  reviewed = false,
                  createdAt = Instant.now()
                )
                (resp.id, Some(created))
              }
        }
    }
}
