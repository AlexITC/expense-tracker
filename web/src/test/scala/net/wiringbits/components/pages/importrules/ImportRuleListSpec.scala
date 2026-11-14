package net.wiringbits.components.pages.importrules

import monix.execution.Scheduler
import monix.reactive.subjects.Var
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.{GetImportRules, GetMerchants, ImportRuleAction, ImportRuleActionKind}
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.common.models.Email
import net.wiringbits.core.{I18nLang, ReactTestDom}
import net.wiringbits.models.AuthState
import net.wiringbits.{API, AppContext}
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import slinky.core.facade.ReactElement
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.BrowserRouter

import java.time.Instant
import java.util.UUID

class ImportRuleListSpec extends AnyWordSpec with ReactTestDom {

  // ImportRuleList never calls ctx.api at render time, so null is safe here.
  private def testCtx(): AppContext = {
    given Scheduler = Scheduler.trampoline()
    AppContext(
      api = null.asInstanceOf[API],
      $auth = Var[AuthState](AuthState.Unauthenticated),
      $lang = Var[I18nLang](I18nLang.English),
      $darkMode = Var[Boolean](false),
      contactEmail = Email.trusted("test@test.com"),
      contactPhone = "+1 555 555 5555",
      executionContext = scala.concurrent.ExecutionContext.global
    )
  }

  private def rule(
      pattern: String,
      merchantName: Option[String] = None,
      autoCreated: Boolean = false
  ): GetImportRules.Rule =
    GetImportRules.Rule(
      id = UUID.randomUUID(),
      action = merchantName.fold[ImportRuleAction](ImportRuleAction.Ignore)(_ =>
        ImportRuleAction.SetMerchant(UUID.randomUUID(), categoryId = None, description = None)
      ),
      pattern = pattern,
      amount = None,
      merchantName = merchantName,
      categoryName = None,
      autoCreated = autoCreated,
      createdAt = Instant.EPOCH,
      updatedAt = Instant.EPOCH
    )

  private def categoryRule(pattern: String, categoryName: String): GetImportRules.Rule =
    rule(pattern).copy(
      action = ImportRuleAction.SetCategory(UUID.randomUUID(), description = None),
      categoryName = Some(categoryName)
    )

  private val ruleA = rule("AMAZON")
  private val ruleB = rule("netflix", Some("Netflix"))
  private val ruleC = rule("UBER EATS", Some("Uber Eats"))
  private val allRules = Seq(ruleA, ruleB, ruleC)

  private val sampleRulesResponse = GetImportRules.Response(
    data = List(
      rule("AMAZON PRIME", Some("Amazon")),
      rule("SPAM FILTER", autoCreated = true),
      rule("NETFLIX", Some("Netflix")),
      categoryRule("STARBUCKS", "Coffee")
    )
  )

  private val sampleCategoriesResponse = GetCategories.Response(
    data = List(GetCategories.Response.Category(id = UUID.randomUUID(), name = "Coffee", createdAt = Instant.EPOCH))
  )

  private val sampleMerchantsResponse = GetMerchants.Response(
    data = List(
      GetMerchants.Response.Merchant(
        id = UUID.randomUUID(),
        name = "Amazon",
        notes = None,
        categoryId = None,
        taxId = None,
        reviewed = false,
        createdAt = Instant.EPOCH
      ),
      GetMerchants.Response.Merchant(
        id = UUID.randomUUID(),
        name = "Netflix",
        notes = None,
        categoryId = None,
        taxId = None,
        reviewed = false,
        createdAt = Instant.EPOCH
      )
    )
  )

  "fromApi" should {
    "copy id, action, pattern, merchant and category" in {
      val r = categoryRule("uber", "Transport")
      ImportRuleMatcher.Rule.fromApi(r) must be(
        ImportRuleMatcher.Rule(r.id, r.action, "uber", amount = None)
      )
    }
  }

  private def form(kind: ImportRuleActionKind, pattern: String = "AMZN MKTP"): ImportRuleList.RuleForm =
    ImportRuleList.RuleForm.empty.copy(
      kind = kind,
      pattern = pattern,
      merchantInput = "Shop",
      categoryInput = "Food"
    )

  "parseAmount" should {
    "treat blank as no amount" in {
      ImportRuleList.parseAmount("  ") must be(Right(None))
    }

    "parse a signed decimal" in {
      ImportRuleList.parseAmount(" -12.50 ") must be(Right(Some(BigDecimal("-12.50"))))
    }

    "reject text that is not a number" in {
      ImportRuleList.parseAmount("12,50").isLeft must be(true)
      ImportRuleList.parseAmount("abc").isLeft must be(true)
    }
  }

  "checkInputs" should {
    "return the parsed amount and trimmed description" in {
      val f = form(ImportRuleActionKind.SetMerchant).copy(amount = "-9.99", description = "  Lunch ")
      ImportRuleList.checkInputs(f) must be(
        Right(ImportRuleList.RuleInputs(Some(BigDecimal("-9.99")), Some("Lunch")))
      )
    }

    "treat a blank description as none" in {
      val f = form(ImportRuleActionKind.SetCategory).copy(description = "   ")
      ImportRuleList.checkInputs(f) must be(Right(ImportRuleList.RuleInputs(None, None)))
    }

    "drop the description of an ignore rule" in {
      val f = form(ImportRuleActionKind.Ignore).copy(description = "Lunch")
      ImportRuleList.checkInputs(f) must be(Right(ImportRuleList.RuleInputs(None, None)))
    }

    "reject an amount that is not a number" in {
      ImportRuleList.checkInputs(form(ImportRuleActionKind.Ignore).copy(amount = "x")).isLeft must be(true)
    }

    "reject an amount the shared validator rejects" in {
      ImportRuleList.checkInputs(form(ImportRuleActionKind.Ignore).copy(amount = "0")).isLeft must be(true)
      ImportRuleList.checkInputs(form(ImportRuleActionKind.Ignore).copy(amount = "-1.234")).isLeft must be(true)
    }

    "reject a pattern that is too short" in {
      ImportRuleList.checkInputs(form(ImportRuleActionKind.Ignore, pattern = "ab")).isLeft must be(true)
    }

    "reject a description longer than the limit" in {
      val f = form(ImportRuleActionKind.SetMerchant)
        .copy(description = "x" * (ImportRuleMatcher.MaxDescriptionLength + 1))
      ImportRuleList.checkInputs(f).isLeft must be(true)
    }

    "require the merchant or category name of the action" in {
      ImportRuleList.checkInputs(form(ImportRuleActionKind.SetMerchant).copy(merchantInput = " ")) must be(
        Left("Enter a merchant name")
      )
      ImportRuleList.checkInputs(form(ImportRuleActionKind.SetCategory).copy(categoryInput = "")) must be(
        Left("Enter a category name")
      )
    }
  }

  "RuleForm.fromRule" should {
    "prefill amount, override category and description of a set_merchant rule" in {
      val mid = UUID.randomUUID()
      val cid = UUID.randomUUID()
      val r = rule("AMZN", Some("Shop")).copy(
        action = ImportRuleAction.SetMerchant(mid, categoryId = Some(cid), description = Some("Books")),
        amount = Some(BigDecimal("-12.50")),
        categoryName = Some("Food")
      )
      val f = ImportRuleList.RuleForm.fromRule(r)
      f.kind must be(ImportRuleActionKind.SetMerchant)
      f.amount must be("-12.50")
      f.merchantInput must be("Shop")
      f.merchantId must be(Some(mid))
      f.overrideCategoryInput must be("Food")
      f.overrideCategoryId must be(Some(cid))
      f.categoryInput must be("")
      f.categoryId must be(None)
      f.description must be("Books")
    }

    "prefill the target category of a set_category rule" in {
      val cid = UUID.randomUUID()
      val r = categoryRule("STARBUCKS", "Coffee").copy(action = ImportRuleAction.SetCategory(cid, description = None))
      val f = ImportRuleList.RuleForm.fromRule(r)
      f.categoryInput must be("Coffee")
      f.categoryId must be(Some(cid))
      f.overrideCategoryInput must be("")
      f.amount must be("")
      f.description must be("")
    }
  }

  "actionLabel" should {
    "show the merchant and override category" in {
      val r = rule("AMZN", Some("Shop")).copy(
        action = ImportRuleAction.SetMerchant(UUID.randomUUID(), categoryId = Some(UUID.randomUUID()), None),
        categoryName = Some("Food")
      )
      ImportRuleList.actionLabel(r) must be("→ Shop (Food)")
    }

    "show only the merchant without an override" in {
      ImportRuleList.actionLabel(rule("AMZN", Some("Shop"))) must be("→ Shop")
    }

    "show the category of a set_category rule and Ignore for ignore rules" in {
      ImportRuleList.actionLabel(categoryRule("STARBUCKS", "Coffee")) must be("→ Coffee")
      ImportRuleList.actionLabel(rule("FEE")) must be("Ignore")
    }
  }

  "amountLabel" should {
    "keep the stored scale without exponent notation" in {
      ImportRuleList.amountLabel(BigDecimal("-12.50")) must be("-12.50")
      ImportRuleList.amountLabel(BigDecimal("1E+3")) must be("1000")
    }
  }

  "filterRules" should {
    "return all rules when query is empty" in {
      ImportRuleList.filterRules(allRules, "") must be(allRules)
    }

    "return only matching rules when query matches pattern" in {
      val result = ImportRuleList.filterRules(allRules, "AMAZON")
      result must be(Seq(ruleA))
    }

    "return only matching rules when query matches merchant name" in {
      val result = ImportRuleList.filterRules(allRules, "Netflix")
      result must be(Seq(ruleB))
    }

    "match case-insensitively" in {
      ImportRuleList.filterRules(allRules, "amazon") must be(Seq(ruleA))
      ImportRuleList.filterRules(allRules, "NETFLIX") must be(Seq(ruleB))
      ImportRuleList.filterRules(allRules, "uber") must be(Seq(ruleC))
    }

    "return empty when query matches neither pattern nor merchant name" in {
      val result = ImportRuleList.filterRules(allRules, "zzznomatch")
      result must be(empty)
    }

    "treat whitespace-only query as empty and return all rules" in {
      ImportRuleList.filterRules(allRules, "   ") must be(allRules)
    }

    "return only matching rules when query matches category name" in {
      val ruleD = categoryRule("STARBUCKS", "Coffee Shops")
      ImportRuleList.filterRules(allRules :+ ruleD, "coffee") must be(Seq(ruleD))
    }

    "return only matching rules when query matches the description" in {
      val ruleD = rule("STARBUCKS", Some("Cafe")).copy(
        action = ImportRuleAction.SetMerchant(UUID.randomUUID(), categoryId = None, description = Some("Team lunch"))
      )
      ImportRuleList.filterRules(allRules :+ ruleD, "lunch") must be(Seq(ruleD))
    }
  }

  "ImportRuleList" should {
    "render without throwing (guards against withKey element-reuse crashes)" in {
      // Guards against withKey element-reuse crashes: each rule card uses .withKey(r.id.toString)
      noException should be thrownBy render(
        ImportRuleList(
          testCtx(),
          sampleRulesResponse,
          sampleMerchantsResponse,
          sampleCategoriesResponse,
          () => ()
        ): ReactElement
      )
    }

    "render the category name for a set_category rule" in {
      render(
        BrowserRouter(basename = "")(
          ImportRuleList(
            testCtx(),
            sampleRulesResponse,
            sampleMerchantsResponse,
            sampleCategoriesResponse,
            () => ()
          )
        ): ReactElement
      )
      container.textContent must include("→ Coffee")
    }

    "render the amount chip, override category and description of a rule" in {
      val enriched = rule("AMZN MKTP", Some("Shop")).copy(
        action = ImportRuleAction.SetMerchant(
          UUID.randomUUID(),
          categoryId = Some(UUID.randomUUID()),
          description = Some("Team lunch")
        ),
        amount = Some(BigDecimal("-12.50")),
        categoryName = Some("Food")
      )
      render(
        BrowserRouter(basename = "")(
          ImportRuleList(
            testCtx(),
            GetImportRules.Response(data = List(enriched)),
            sampleMerchantsResponse,
            sampleCategoriesResponse,
            () => ()
          )
        ): ReactElement
      )
      val text = container.textContent
      text must include("-12.50")
      text must include("Team lunch")
      text must include("→ Shop (Food)")
    }

    "render the amount, override category and description fields of the create form" in {
      render(
        BrowserRouter(basename = "")(
          ImportRuleList(
            testCtx(),
            sampleRulesResponse,
            sampleMerchantsResponse,
            sampleCategoriesResponse,
            () => ()
          )
        ): ReactElement
      )
      val text = container.textContent
      text must include("Amount (signed, e.g. -12.50)")
      text must include("Description (optional)")
      text must include("Category (optional)")
    }
  }
}
