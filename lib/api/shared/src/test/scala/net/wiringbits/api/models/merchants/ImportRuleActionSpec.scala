package net.wiringbits.api.models.merchants

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.*

import java.util.UUID

class ImportRuleActionSpec extends AnyWordSpec with Matchers {

  private val merchantId = UUID.randomUUID()
  private val categoryId = UUID.randomUUID()

  private def roundTrip(action: ImportRuleAction): JsResult[ImportRuleAction] =
    Json.toJson(action).validate[ImportRuleAction]

  "format" should {
    "round-trip a set_merchant action with an override category and description" in {
      val action = ImportRuleAction.SetMerchant(merchantId, categoryId = Some(categoryId), description = Some("Coffee"))
      roundTrip(action) must be(JsSuccess(action))
    }

    "round-trip a set_category action with a description" in {
      val action = ImportRuleAction.SetCategory(categoryId, description = Some("Coffee"))
      roundTrip(action) must be(JsSuccess(action))
    }

    "round-trip an ignore action" in {
      roundTrip(ImportRuleAction.Ignore) must be(JsSuccess(ImportRuleAction.Ignore))
    }

    "omit absent optional fields when writing" in {
      Json.toJson(ImportRuleAction.SetMerchant(merchantId, categoryId = None, description = None)) must be(
        Json.obj("kind" -> "set_merchant", "merchantId" -> merchantId)
      )
      Json.toJson(ImportRuleAction.SetCategory(categoryId, description = None)) must be(
        Json.obj("kind" -> "set_category", "categoryId" -> categoryId)
      )
    }

    "read a set_merchant payload without the new keys" in {
      Json.obj("kind" -> "set_merchant", "merchantId" -> merchantId).validate[ImportRuleAction] must be(
        JsSuccess(ImportRuleAction.SetMerchant(merchantId, categoryId = None, description = None))
      )
    }

    "read a set_category payload without the new keys" in {
      Json.obj("kind" -> "set_category", "categoryId" -> categoryId).validate[ImportRuleAction] must be(
        JsSuccess(ImportRuleAction.SetCategory(categoryId, description = None))
      )
    }

    "reject a set_merchant payload without merchantId" in {
      Json.obj("kind" -> "set_merchant", "categoryId" -> categoryId).validate[ImportRuleAction].isError must be(true)
    }
  }

  "fromColumns" should {
    "rebuild a set_merchant action with its override category and description" in {
      ImportRuleAction.fromColumns(
        "set_merchant",
        merchantId = Some(merchantId),
        categoryId = None,
        overrideCategoryId = Some(categoryId),
        description = Some("Coffee")
      ) must be(ImportRuleAction.SetMerchant(merchantId, categoryId = Some(categoryId), description = Some("Coffee")))
    }

    "rebuild a set_category action with its description" in {
      ImportRuleAction.fromColumns(
        "set_category",
        merchantId = None,
        categoryId = Some(categoryId),
        overrideCategoryId = None,
        description = Some("Coffee")
      ) must be(ImportRuleAction.SetCategory(categoryId, description = Some("Coffee")))
    }

    "throw for a set_merchant row without merchant_id" in {
      an[IllegalStateException] must be thrownBy ImportRuleAction.fromColumns(
        "set_merchant",
        merchantId = None,
        categoryId = None,
        overrideCategoryId = Some(categoryId),
        description = None
      )
    }

    "throw for a set_category row without category_id" in {
      an[IllegalStateException] must be thrownBy ImportRuleAction.fromColumns(
        "set_category",
        merchantId = None,
        categoryId = None,
        overrideCategoryId = Some(categoryId),
        description = None
      )
    }
  }
}
