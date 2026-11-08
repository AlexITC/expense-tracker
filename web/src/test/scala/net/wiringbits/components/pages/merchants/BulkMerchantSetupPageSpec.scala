package net.wiringbits.components.pages.merchants

import monix.execution.Scheduler
import monix.reactive.subjects.Var
import net.wiringbits.api.models.bankstatements.GetAllRows
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.{GetMerchants, ImportRuleAction}
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.common.models.Email
import net.wiringbits.core.{I18nLang, ReactTestDom}
import net.wiringbits.models.AuthState
import net.wiringbits.{API, AppContext}
import org.scalajs.dom
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import slinky.core.facade.ReactElement

import java.time.{Instant, LocalDate}
import java.util.UUID

class BulkMerchantSetupPageSpec extends AnyWordSpec with ReactTestDom {

  // DescriptionRow calls ctx.api only on user interaction (Assign/Ignore buttons),
  // so null is safe for render-only tests.
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

  private val sampleMerchants = List(
    GetMerchants.Response.Merchant(
      id = UUID.randomUUID(),
      name = "Carniceria El Riscu",
      notes = None,
      categoryId = None,
      taxId = None,
      reviewed = false,
      createdAt = Instant.now()
    )
  )

  private val sampleCategories = List(
    GetCategories.Response.Category(
      id = UUID.randomUUID(),
      name = "Food",
      createdAt = Instant.now()
    )
  )

  private val sampleRows = List(
    GetAllRows.RowData(
      id = UUID.randomUUID(),
      bankStatementId = UUID.randomUUID(),
      date = LocalDate.of(2024, 1, 15),
      amount = BigDecimal("-45.50"),
      description = "CARNICERIA EL RISCU (GIJON)",
      expenseId = None,
      ignoredAt = None
    ),
    GetAllRows.RowData(
      id = UUID.randomUUID(),
      bankStatementId = UUID.randomUUID(),
      date = LocalDate.of(2024, 2, 3),
      amount = BigDecimal("-23.00"),
      description = "CARNICERIA EL RISCU (GIJON)",
      expenseId = None,
      ignoredAt = None
    )
  )

  private def renderRow(
      description: String,
      rows: List[GetAllRows.RowData],
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category]
  ): Unit =
    render(
      DescriptionRow(
        DescriptionRow.Props(
          description = description,
          rows = rows,
          merchants = merchants,
          categories = categories,
          ctx = testCtx(),
          onDone = (_, _, _) => ()
        )
      ): ReactElement
    )

  private def clickAssign(): Unit =
    act {
      container
        .querySelectorAll("button")
        .toList
        .find(_.textContent == "Assign")
        .getOrElse(fail("Assign button not found"))
        .asInstanceOf[dom.html.Element]
        .click()
    }

  "findUnmatchedGroups" should {
    val otherRow = sampleRows.head.copy(id = UUID.randomUUID(), description = "UNKNOWN SHOP 42")

    "list descriptions not covered by any rule or merchant name" in {
      val result = BulkMerchantSetupPage.findUnmatchedGroups(sampleRows :+ otherRow, Nil, Nil)
      result.map(_._1) mustBe List("CARNICERIA EL RISCU (GIJON)", "UNKNOWN SHOP 42")
    }

    "exclude descriptions that contain an existing merchant name" in {
      val result = BulkMerchantSetupPage.findUnmatchedGroups(sampleRows :+ otherRow, Nil, sampleMerchants)
      result.map(_._1) mustBe List("UNKNOWN SHOP 42")
    }

    "exclude descriptions matched by an ignore rule" in {
      val ignore = ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.Ignore, "unknown shop")
      val result = BulkMerchantSetupPage.findUnmatchedGroups(sampleRows :+ otherRow, List(ignore), Nil)
      result.map(_._1) mustBe List("CARNICERIA EL RISCU (GIJON)")
    }
  }

  "DescriptionRow" should {
    "render mui.Autocomplete children without errors" in {
      noException should be thrownBy renderRow(
        "CARNICERIA EL RISCU (GIJON)",
        sampleRows,
        sampleMerchants,
        sampleCategories
      )
    }

    "render with empty merchant and category lists" in {
      noException should be thrownBy renderRow("UNKNOWN MERCHANT", Nil, Nil, Nil)
    }

    "not throw when merchant autocomplete is clicked (opens dropdown)" in {
      renderRow("CARNICERIA EL RISCU (GIJON)", sampleRows, sampleMerchants, sampleCategories)
      noException should be thrownBy {
        val inputs = container.querySelectorAll("input")
        inputs.length must be > 0
        act(inputs.item(0).asInstanceOf[dom.HTMLInputElement].click())
      }
    }

    "not throw when category autocomplete is clicked (opens dropdown)" in {
      renderRow("CARNICERIA EL RISCU (GIJON)", sampleRows, sampleMerchants, sampleCategories)
      noException should be thrownBy {
        val inputs = container.querySelectorAll("input")
        inputs.length must be > 1
        act(inputs.item(1).asInstanceOf[dom.HTMLInputElement].click())
      }
    }

    // The validation error is rendered from SaveState.Failed via errorOpt; no API call is made on this path.
    "show 'Merchant name is required' when Assign is clicked with an empty merchant" in {
      renderRow("", Nil, Nil, Nil)
      clickAssign()
      container.textContent must include("Merchant name is required")
    }

    "show 'Category is required' when Assign is clicked with only the merchant filled" in {
      renderRow("CARNICERIA EL RISCU (GIJON)", sampleRows, Nil, Nil)
      clickAssign()
      container.textContent must include("Category is required")
    }
  }
}
