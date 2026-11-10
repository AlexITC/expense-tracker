package net.wiringbits.components.pages.bankstatementreview

import monix.execution.Scheduler
import monix.reactive.subjects.Var
import net.wiringbits.api.ApiClient
import net.wiringbits.api.models.bankstatements.LinkRowToExpense
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.common.models.Email
import net.wiringbits.core.{I18nLang, ReactTestDom}
import net.wiringbits.models.AuthState
import net.wiringbits.services.StorageService
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.BrowserRouter
import net.wiringbits.{API, AppContext}
import org.scalajs.dom
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import slinky.core.facade.ReactElement
import sttp.client3.testing.SttpBackendStub

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}

/** Mounts the picker in jsdom to guard against runtime failures pure logic tests cannot see: React elements reused or
  * left unkeyed when the matched expense renders alongside the manual list, and the async getExpenses load path that
  * fills the picker after mount.
  */
class BankRowLinkPickerSpec extends AnyWordSpec with ReactTestDom {

  private val stmtId = UUID.randomUUID()
  private val rowId = UUID.randomUUID()
  private val category =
    GetCategories.Response.Category(id = UUID.randomUUID(), name = "Groceries", createdAt = Instant.EPOCH)

  private def expense(
      merchant: String,
      notes: Option[String] = None,
      categoryId: Option[UUID] = None,
      receiptIds: List[UUID] = Nil
  ) =
    GetExpenses.Response.Expense(
      id = UUID.randomUUID(),
      amount = BigDecimal("12.34"),
      date = LocalDate.of(2024, 3, 7),
      time = None,
      merchantId = None,
      merchantName = Some(merchant),
      notes = notes,
      splits = categoryId.map(id => GetExpenses.Response.Split(UUID.randomUUID(), id, BigDecimal("12.34"))).toList,
      createdAt = Instant.EPOCH,
      receiptIds = receiptIds
    )

  // Stubbed futures are already completed, so the parasitic context resolves them synchronously inside act.
  private final class StubClient(expenses: List[GetExpenses.Response.Expense])
      extends ApiClient(ApiClient.Config("http://localhost"))(using
        ExecutionContext.parasitic,
        SttpBackendStub.asynchronousFuture
      ) {
    val linked: ListBuffer[UUID] = ListBuffer.empty

    override def getExpenses: Future[GetExpenses.Response] = Future.successful(GetExpenses.Response(expenses))

    override def linkRowToExpense(
        bankStatementId: UUID,
        rowId: UUID,
        request: LinkRowToExpense.Request
    ): Future[LinkRowToExpense.Response] = {
      linked += request.expenseId
      Future.successful(LinkRowToExpense.Response())
    }
  }

  private def testCtx(client: ApiClient): AppContext = {
    given Scheduler = Scheduler.trampoline()
    AppContext(
      api = API(client, null.asInstanceOf[StorageService], ""),
      $auth = Var[AuthState](AuthState.Unauthenticated),
      $lang = Var[I18nLang](I18nLang.English),
      $darkMode = Var[Boolean](false),
      contactEmail = Email.trusted("test@test.com"),
      contactPhone = "+1 555 555 5555",
      executionContext = ExecutionContext.parasitic
    )
  }

  private def renderPicker(
      client: StubClient,
      matchedExpenseId: Option[UUID],
      onSuccess: UUID => Unit = _ => ()
  ): Unit =
    render(
      BrowserRouter(basename = "")(
        BankRowLinkPicker(
          BankRowLinkPicker.Props(
            stmtId = stmtId,
            rowId = rowId,
            ctx = testCtx(client),
            matchedExpenseId = matchedExpenseId,
            categories = List(category),
            onSuccess = onSuccess,
            onCancel = () => ()
          )
        )
      ): ReactElement
    )

  private def buttons(label: String): Seq[dom.html.Element] = {
    val all = container.querySelectorAll("button")
    (0 until all.length).map(all(_).asInstanceOf[dom.html.Element]).filter(_.textContent == label)
  }

  private val others = (1 to 25).toList.map(i => expense(s"Shop $i"))

  "component" should {
    "show the matched expense first even when it is past the first 20" in {
      val matched = expense(
        "Matched Shop",
        notes = Some("weekly run"),
        categoryId = Some(category.id),
        receiptIds = List(UUID.randomUUID())
      )
      renderPicker(new StubClient(others :+ matched), Some(matched.id))

      val text = container.textContent
      text must include("Matched expense")
      text must include("Matched Shop · Groceries")
      text must include("weekly run")
      text must include("🧾 Receipt")
      buttons("Link this expense").length mustBe 1
      text.indexOf("Matched Shop") must be < text.indexOf("Shop 1")
      buttons("Link").length mustBe 20
    }

    "link the matched expense and report it" in {
      val matched = expense("Matched Shop")
      val client = new StubClient(others :+ matched)
      val reported = ListBuffer.empty[UUID]
      renderPicker(client, Some(matched.id), id => reported += id)

      container.textContent must not include "🧾 Receipt"
      act(buttons("Link this expense").head.click())

      client.linked.toList mustBe List(matched.id)
      reported.toList mustBe List(matched.id)
    }

    "render only the list without a match" in {
      renderPicker(new StubClient(others), None)

      container.textContent must not include "Matched expense"
      buttons("Link this expense") mustBe empty
      buttons("Link").length mustBe 20
    }
  }
}
