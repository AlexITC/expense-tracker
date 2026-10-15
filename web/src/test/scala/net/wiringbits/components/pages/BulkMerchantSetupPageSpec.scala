package net.wiringbits.components.pages

import monix.execution.Scheduler
import monix.reactive.subjects.Var
import net.wiringbits.api.models.bankstatements.GetAllRows
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.common.models.Email
import net.wiringbits.core.I18nLang
import net.wiringbits.models.AuthState
import net.wiringbits.{API, AppContext}
import org.scalajs.dom
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import scala.compiletime.uninitialized
import scala.scalajs.js
import slinky.core.facade.ReactElement
import slinky.web.ReactDOM

import java.time.{Instant, LocalDate}
import java.util.UUID

class BulkMerchantSetupPageSpec extends AnyWordSpec with BeforeAndAfterEach {

  private var container: dom.Element = uninitialized

  override def beforeEach(): Unit = {
    container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
  }

  override def afterEach(): Unit = {
    ReactDOM.unmountComponentAtNode(container)
    dom.document.body.removeChild(container)
  }

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
    ReactDOM.render(
      DescriptionRow(
        DescriptionRow.Props(
          description = description,
          rows = rows,
          merchants = merchants,
          categories = categories,
          ctx = testCtx(),
          onDone = (_, _, _) => ()
        )
      ): ReactElement,
      container
    )

  "DescriptionRow.merchantOptionLabel" should {
    "return name from a Merchant instance" in {
      val m = sampleMerchants.head
      DescriptionRow.merchantOptionLabel(m.asInstanceOf[js.Any]) mustBe "Carniceria El Riscu"
    }
    "return the string as-is for freeSolo text input" in {
      DescriptionRow.merchantOptionLabel("typed text".asInstanceOf[js.Any]) mustBe "typed text"
    }
    "return empty string for undefined (MUI internal value on first render)" in {
      val undef: js.Any = js.undefined.asInstanceOf[js.Any]
      DescriptionRow.merchantOptionLabel(undef) mustBe ""
    }
    "return empty string for null" in {
      DescriptionRow.merchantOptionLabel(null.asInstanceOf[js.Any]) mustBe ""
    }
    "return empty string for an unrelated object (e.g. a Category)" in {
      val c = sampleCategories.head
      DescriptionRow.merchantOptionLabel(c.asInstanceOf[js.Any]) mustBe ""
    }
  }

  "DescriptionRow.categoryOptionLabel" should {
    "return name from a Category instance" in {
      val c = sampleCategories.head
      DescriptionRow.categoryOptionLabel(c.asInstanceOf[js.Any]) mustBe "Food"
    }
    "return the string as-is for freeSolo text input" in {
      DescriptionRow.categoryOptionLabel("Groceries".asInstanceOf[js.Any]) mustBe "Groceries"
    }
    "return empty string for undefined" in {
      val undef: js.Any = js.undefined.asInstanceOf[js.Any]
      DescriptionRow.categoryOptionLabel(undef) mustBe ""
    }
    "return empty string for null" in {
      DescriptionRow.categoryOptionLabel(null.asInstanceOf[js.Any]) mustBe ""
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
        inputs.item(0).asInstanceOf[dom.HTMLInputElement].click()
      }
    }

    "not throw when category autocomplete is clicked (opens dropdown)" in {
      renderRow("CARNICERIA EL RISCU (GIJON)", sampleRows, sampleMerchants, sampleCategories)
      noException should be thrownBy {
        val inputs = container.querySelectorAll("input")
        inputs.length must be > 1
        inputs.item(1).asInstanceOf[dom.HTMLInputElement].click()
      }
    }
  }
}
