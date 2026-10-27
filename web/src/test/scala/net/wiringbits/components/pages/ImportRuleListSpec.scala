package net.wiringbits.components.pages

import monix.execution.Scheduler
import monix.reactive.subjects.Var
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.{GetImportRules, GetMerchants, ImportRuleAction}
import net.wiringbits.common.models.Email
import net.wiringbits.core.I18nLang
import net.wiringbits.models.AuthState
import net.wiringbits.{API, AppContext}
import org.scalajs.dom
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import slinky.core.facade.ReactElement
import slinky.web.ReactDOM
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.BrowserRouter

import java.time.Instant
import java.util.UUID
import scala.compiletime.uninitialized

class ImportRuleListSpec extends AnyWordSpec with BeforeAndAfterEach {

  private var container: dom.Element = uninitialized

  override def beforeEach(): Unit = {
    container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
  }

  override def afterEach(): Unit = {
    ReactDOM.unmountComponentAtNode(container)
    dom.document.body.removeChild(container)
  }

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
      action = if (merchantName.isDefined) ImportRuleAction.SetMerchant else ImportRuleAction.Ignore,
      pattern = pattern,
      merchantId = merchantName.map(_ => UUID.randomUUID()),
      merchantName = merchantName,
      categoryId = None,
      categoryName = None,
      autoCreated = autoCreated,
      createdAt = Instant.EPOCH,
      updatedAt = Instant.EPOCH
    )

  private def categoryRule(pattern: String, categoryName: String): GetImportRules.Rule =
    rule(pattern).copy(
      action = ImportRuleAction.SetCategory,
      categoryId = Some(UUID.randomUUID()),
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
  }

  "ImportRuleList" should {
    "render without throwing (guards against withKey element-reuse crashes)" in {
      // Guards against withKey element-reuse crashes: each rule card uses .withKey(r.id.toString)
      noException should be thrownBy ReactDOM.render(
        ImportRuleList(
          testCtx(),
          sampleRulesResponse,
          sampleMerchantsResponse,
          sampleCategoriesResponse,
          () => ()
        ): ReactElement,
        container
      )
    }

    "render the category name for a set_category rule" in {
      ReactDOM.render(
        BrowserRouter(basename = "")(
          ImportRuleList(
            testCtx(),
            sampleRulesResponse,
            sampleMerchantsResponse,
            sampleCategoriesResponse,
            () => ()
          )
        ): ReactElement,
        container
      )
      container.textContent must include("→ Coffee")
    }
  }
}
