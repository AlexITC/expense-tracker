package net.wiringbits.components.pages.expenses

import monix.execution.Scheduler
import monix.reactive.subjects.Var
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.expenses.{GetExpenses, MergeDetailsSource}
import net.wiringbits.common.models.Email
import net.wiringbits.core.I18nLang
import net.wiringbits.models.AuthState
import net.wiringbits.utils.ExpenseDuplicateDetector.{Candidate, Tier}
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.BrowserRouter
import net.wiringbits.{API, AppContext}
import org.scalajs.dom
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import slinky.core.facade.ReactElement
import slinky.web.ReactDOM

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.collection.mutable.ListBuffer
import scala.compiletime.uninitialized

class ExpenseDuplicatesPanelSpec extends AnyWordSpec with BeforeAndAfterEach {

  private var container: dom.Element = uninitialized

  override def beforeEach(): Unit = {
    container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
  }

  override def afterEach(): Unit = {
    ReactDOM.unmountComponentAtNode(container)
    dom.document.body.removeChild(container)
  }

  // ExpenseDuplicatesPanel only calls ctx.api from View receipt, which is not rendered without receipt filenames.
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

  private def expense(): GetExpenses.Response.Expense =
    GetExpenses.Response.Expense(
      id = UUID.randomUUID(),
      amount = BigDecimal("10"),
      date = LocalDate.of(2024, 1, 1),
      time = None,
      merchantId = None,
      merchantName = None,
      notes = None,
      splits = Nil,
      createdAt = Instant.EPOCH
    )

  private val candidate =
    Candidate(
      survivor = expense(),
      loser = expense(),
      receiptId = UUID.randomUUID(),
      tier = Tier.Strong,
      ambiguous = false
    )

  private def render(onMerge: (Candidate, MergeDetailsSource) => Unit): Unit =
    ReactDOM.render(
      BrowserRouter(basename = "")(
        ExpenseDuplicatesPanel(
          ctx = testCtx(),
          candidates = List(candidate),
          categories = GetCategories.Response(data = Nil),
          receiptFilenames = Map.empty,
          onMerge = onMerge,
          onDismiss = _ => (),
          onError = _ => ()
        )
      ): ReactElement,
      container
    )

  private def clickButton(label: String): Unit = {
    val buttons = container.querySelectorAll("button")
    val matching = (0 until buttons.length).map(buttons(_)).filter(_.textContent == label)
    matching.length mustBe 1
    matching.head.asInstanceOf[dom.html.Element].click()
  }

  "ExpenseDuplicatesPanel" should {
    "send the row source from the keep row details button" in {
      val merges = ListBuffer.empty[(Candidate, MergeDetailsSource)]
      render((c, source) => merges += (c -> source))
      clickButton("Merge, keep row details")
      merges.toList must be(List(candidate -> MergeDetailsSource.Row))
    }

    "send the receipt source from the keep receipt details button" in {
      val merges = ListBuffer.empty[(Candidate, MergeDetailsSource)]
      render((c, source) => merges += (c -> source))
      clickButton("Merge, keep receipt details")
      merges.toList must be(List(candidate -> MergeDetailsSource.Receipt))
    }
  }
}
