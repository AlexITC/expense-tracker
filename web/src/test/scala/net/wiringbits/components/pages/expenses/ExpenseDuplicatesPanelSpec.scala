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
      receiptId = Some(UUID.randomUUID()),
      tier = Tier.Strong,
      ambiguous = false
    )

  private def render(
      onMerge: (Candidate, MergeDetailsSource) => Unit,
      candidate: Candidate = candidate,
      candidates: Option[List[Candidate]] = None,
      selected: Set[String] = Set.empty,
      onToggleSelected: (Candidate, Boolean) => Unit = (_, _) => (),
      onToggleAll: Boolean => Unit = _ => (),
      onBulkMerge: () => Unit = () => (),
      bulkMerging: Boolean = false
  ): Unit =
    ReactDOM.render(
      BrowserRouter(basename = "")(
        ExpenseDuplicatesPanel(
          ctx = testCtx(),
          candidates = candidates.getOrElse(List(candidate)),
          categories = GetCategories.Response(data = Nil),
          receiptFilenames = Map.empty,
          onMerge = onMerge,
          onDismiss = _ => (),
          onError = _ => (),
          selected = selected,
          onToggleSelected = onToggleSelected,
          onToggleAll = onToggleAll,
          onBulkMerge = onBulkMerge,
          bulkMerging = bulkMerging
        )
      ): ReactElement,
      container
    )

  private def buttonLabels: List[String] = {
    val buttons = container.querySelectorAll("button")
    (0 until buttons.length).map(buttons(_).textContent).toList
  }

  private def checkboxes: List[dom.html.Input] = {
    val inputs = container.querySelectorAll("input[type=checkbox]")
    (0 until inputs.length).map(inputs(_).asInstanceOf[dom.html.Input]).toList
  }

  private def bulkButton: dom.html.Button = {
    val buttons = container.querySelectorAll("button")
    val matching =
      (0 until buttons.length).map(buttons(_)).filter(_.textContent.startsWith("Merge selected"))
    matching.length mustBe 1
    matching.head.asInstanceOf[dom.html.Button]
  }

  private def clickButton(label: String): Unit = {
    val buttons = container.querySelectorAll("button")
    val matching = (0 until buttons.length).map(buttons(_)).filter(_.textContent == label)
    matching.length mustBe 1
    matching.head.asInstanceOf[dom.html.Element].click()
  }

  private def lastLineOf(columnTitle: String): String = {
    val titles = container.querySelectorAll("p, span, h6")
    val title = (0 until titles.length).map(titles(_)).filter(_.textContent == columnTitle)
    title.length mustBe 1
    title.head.parentNode.lastChild.textContent
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

    "offer only Delete duplicate and Dismiss for an orphan candidate, sending the row source" in {
      val orphanCandidate = candidate.copy(receiptId = None)
      val merges = ListBuffer.empty[(Candidate, MergeDetailsSource)]
      render((c, source) => merges += (c -> source), candidate = orphanCandidate)
      buttonLabels must be(List("Merge selected (0)", "Delete duplicate", "Dismiss"))
      clickButton("Delete duplicate")
      merges.toList must be(List(orphanCandidate -> MergeDetailsSource.Row))
    }

    "show a card checkbox only for bulk-eligible candidates" in {
      val weak = candidate.copy(survivor = expense(), loser = expense(), tier = Tier.Weak)
      val ambiguous = candidate.copy(survivor = expense(), loser = expense(), ambiguous = true)
      render((_, _) => (), candidates = Some(List(candidate, weak, ambiguous)))
      // The select-all checkbox plus the single eligible card.
      checkboxes.size must be(2)
      container.textContent must include("Several expenses match this one")
    }

    "report onToggleSelected when a card checkbox is clicked" in {
      val toggled = ListBuffer.empty[(Candidate, Boolean)]
      render((_, _) => (), onToggleSelected = (c, checked) => toggled += (c -> checked))
      checkboxes(1).click()
      toggled.toList must be(List(candidate -> true))
    }

    "report onToggleAll when the select-all checkbox is clicked" in {
      val toggled = ListBuffer.empty[Boolean]
      render((_, _) => (), onToggleAll = checked => toggled += checked)
      checkboxes.head.click()
      toggled.toList must be(List(true))
    }

    "mark select-all checked when every eligible candidate is selected" in {
      render((_, _) => (), selected = Set(ExpenseList.dismissKey(candidate)))
      checkboxes.map(_.checked) must be(List(true, true))
    }

    "mark select-all unchecked and indeterminate on a partial selection" in {
      val other = candidate.copy(survivor = expense(), loser = expense())
      render((_, _) => (), candidates = Some(List(candidate, other)), selected = Set(ExpenseList.dismissKey(candidate)))
      checkboxes.head.checked must be(false)
      checkboxes.head.getAttribute("data-indeterminate") must be("true")
      checkboxes.tail.count(_.checked) must be(1)
    }

    "disable select-all when no candidate is bulk-eligible" in {
      render((_, _) => (), candidate = candidate.copy(tier = Tier.Weak))
      checkboxes.map(_.disabled) must be(List(true))
      bulkButton.textContent must be("Merge selected (0)")
    }

    "disable the merge controls while a bulk merge runs" in {
      render((_, _) => (), selected = Set(ExpenseList.dismissKey(candidate)), bulkMerging = true)
      checkboxes.map(_.disabled) must be(List(true, true))
      bulkButton.disabled must be(true)
      val buttons = container.querySelectorAll("button")
      val merge = (0 until buttons.length).map(buttons(_)).filter(_.textContent.startsWith("Merge, keep"))
      merge.map(_.asInstanceOf[dom.html.Button].disabled) must be(List(true, true))
    }

    "disable the bulk merge button without a selection" in {
      render((_, _) => ())
      bulkButton.textContent must be("Merge selected (0)")
      bulkButton.disabled must be(true)
    }

    "call onBulkMerge from the enabled bulk merge button" in {
      var calls = 0
      render((_, _) => (), selected = Set(ExpenseList.dismissKey(candidate)), onBulkMerge = () => calls += 1)
      bulkButton.textContent must be("Merge selected (1)")
      bulkButton.click()
      calls must be(1)
    }
  }

  "column" should {
    "show notes, with a dash for empty notes" in {
      val withNotes = candidate.copy(
        survivor = candidate.survivor.copy(notes = Some("bank note")),
        loser = candidate.loser.copy(notes = Some(""))
      )
      render(onMerge = (_, _) => (), candidate = withNotes)
      lastLineOf("Bank row") must be("bank note")
      lastLineOf("Receipt") must be("—")
    }

    "title the loser column for an orphan candidate" in {
      val orphanCandidate = candidate.copy(
        receiptId = None,
        loser = candidate.loser.copy(notes = Some("hand entered"))
      )
      render(onMerge = (_, _) => (), candidate = orphanCandidate)
      lastLineOf("Duplicate (no receipt)") must be("hand entered")
    }
  }
}
