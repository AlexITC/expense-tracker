package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.api.models.bankstatements.GetBankStatement
import net.wiringbits.api.utils.RowRuleVerdict
import net.wiringbits.components.pages.BankStatementReviewPage.{LinkedCategory, ResolvedImport, RowInfo}
import net.wiringbits.components.pages.BankStatementReviewRow.RowAction
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.BrowserRouter
import org.scalajs.dom
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import slinky.core.facade.{Fragment, ReactElement}
import slinky.web.ReactDOM

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.compiletime.uninitialized

class BankStatementReviewRowSpec extends AnyWordSpec with BeforeAndAfterEach {

  private var container: dom.Element = uninitialized

  override def beforeEach(): Unit = {
    container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
  }

  override def afterEach(): Unit = {
    ReactDOM.unmountComponentAtNode(container)
    dom.document.body.removeChild(container)
  }

  private def row(description: String): GetBankStatement.StatementRowData =
    GetBankStatement.StatementRowData(
      id = UUID.randomUUID(),
      index = 0,
      date = LocalDate.of(2024, 3, 7),
      amount = BigDecimal("-120.00"),
      description = description,
      manuallyEdited = false,
      confirmedAt = None,
      expenseId = None,
      refundId = None,
      ignoredAt = None,
      txnType = "purchase",
      expenseMerchantId = None,
      expenseCategoryId = None
    )

  private val readyInfo = RowInfo(
    verdict = RowRuleVerdict.NoRule,
    resolved = Some(ResolvedImport(None, UUID.randomUUID(), categoryFromAi = true)),
    isReady = true,
    merchantName = None,
    categoryName = Some("Groceries"),
    linkedMerchantName = None,
    linkedCategory = LinkedCategory.Unknown
  )
  private val ignoreInfo =
    RowInfo(RowRuleVerdict.Ignore, None, isReady = true, None, None, None, LinkedCategory.Unknown)

  private def props(
      r: GetBankStatement.StatementRowData,
      info: RowInfo,
      onAction: (UUID, RowAction) => Unit,
      onToggleSelected: (UUID, Boolean) => Unit = (_, _) => ()
  ): BankStatementReviewRow.Props =
    BankStatementReviewRow.Props(
      row = r,
      info = info,
      selected = false,
      showCheckbox = true,
      pollingSuggestions = false,
      onToggleSelected = onToggleSelected,
      onAction = onAction
    )

  private def renderProps(rowProps: List[BankStatementReviewRow.Props]) =
    ReactDOM.render(
      BrowserRouter(basename = "")(
        mui.Table(
          mui.TableBody(
            Fragment(rowProps.map(p => (BankStatementReviewRow(p).withKey(p.row.id.toString): ReactElement))*)
          )
        )
      ): ReactElement,
      container
    )

  private def render(rows: List[(GetBankStatement.StatementRowData, RowInfo)], onAction: (UUID, RowAction) => Unit) =
    renderProps(rows.map { case (r, info) => props(r, info, onAction) })

  private def clickButton(label: String): Unit = {
    val buttons = container.querySelectorAll("button")
    val matching = (0 until buttons.length).map(buttons(_)).filter(_.textContent == label)
    matching.length mustBe 1
    matching.head.asInstanceOf[dom.html.Element].click()
  }

  private def amountText: String = container.querySelectorAll("td")(2).textContent

  "BankStatementReviewRow" should {
    // Guards against element-reuse crashes in the extracted, React.memo-wrapped row: its cells share chip/dash
    // helpers that must be defs, and each mapped row needs .withKey.
    "render several rows without throwing" in {
      noException should be thrownBy render(
        List(row("SUPERMARKET") -> readyInfo, row("SPAM") -> ignoreInfo, row("") -> readyInfo),
        (_, _) => ()
      )
      container.querySelectorAll("tr").length mustBe 3
    }

    // Guards against the MUI Menu throwing at render time when anchored to the "…" button's click target.
    "open the overflow menu and report the chosen action for its own row" in {
      val r = row("SUPERMARKET")
      var received = List.empty[(UUID, RowAction)]
      render(List(r -> readyInfo), (id, action) => received = received :+ (id -> action))

      val buttons = container.querySelectorAll("button")
      buttons(buttons.length - 1).asInstanceOf[dom.html.Element].click()
      val items = dom.document.body.querySelectorAll("[role=menuitem]")
      items.length mustBe 3
      items(2).asInstanceOf[dom.html.Element].click()

      received mustBe List(r.id -> RowAction.Ignore)
    }

    // A ready negative row's Confirm must open the prefilled drawer, not import straight away.
    "report OpenExpenseForm when Confirm is clicked on a ready negative row" in {
      val r = row("SUPERMARKET")
      var received = List.empty[(UUID, RowAction)]
      render(List(r -> readyInfo), (id, action) => received = received :+ (id -> action))
      clickButton("Confirm")
      received mustBe List(r.id -> RowAction.OpenExpenseForm)
    }

    "report OpenRefundForm when Import refund is clicked on a ready positive row" in {
      val r = row("REFUND").copy(amount = BigDecimal("45.00"))
      var received = List.empty[(UUID, RowAction)]
      render(List(r -> readyInfo), (id, action) => received = received :+ (id -> action))
      clickButton("Import refund")
      received mustBe List(r.id -> RowAction.OpenRefundForm)
    }

    "report the menu's Link and Create rule items for its own row" in {
      val r = row("SUPERMARKET")
      var received = List.empty[(UUID, RowAction)]
      render(List(r -> readyInfo), (id, action) => received = received :+ (id -> action))
      def clickMenuItem(index: Int): Unit = {
        val buttons = container.querySelectorAll("button")
        buttons(buttons.length - 1).asInstanceOf[dom.html.Element].click()
        dom.document.body.querySelectorAll("[role=menuitem]")(index).asInstanceOf[dom.html.Element].click()
      }
      clickMenuItem(0)
      clickMenuItem(1)
      received mustBe List(r.id -> RowAction.OpenLinkPicker, r.id -> RowAction.OpenRuleForm)
    }

    // A linked or ignored row's menu must only offer the undo action, and Unlink must reach the right handler.
    "offer only the undo action in the menu of a linked or ignored row" in {
      val linkedInfo = readyInfo.copy(isReady = false, resolved = None)
      val expenseRow = row("SUPERMARKET").copy(expenseId = Some(UUID.randomUUID()))
      val refundRow = row("REFUND").copy(amount = BigDecimal("45.00"), refundId = Some(UUID.randomUUID()))
      val ignoredRow = row("SPAM").copy(ignoredAt = Some(Instant.now()))
      val cases = List(
        (expenseRow, "Unlink", RowAction.Unlink),
        (refundRow, "Unlink", RowAction.UnlinkRefund),
        (ignoredRow, "Un-ignore", RowAction.Unignore)
      )
      cases.foreach { case (r, label, expected) =>
        var received = List.empty[(UUID, RowAction)]
        render(List(r -> linkedInfo), (id, action) => received = received :+ (id -> action))
        val buttons = container.querySelectorAll("button")
        buttons(buttons.length - 1).asInstanceOf[dom.html.Element].click()
        val items = dom.document.body.querySelectorAll("[role=menuitem]")
        items.length mustBe 1
        items(0).textContent mustBe label
        items(0).asInstanceOf[dom.html.Element].click()
        received mustBe List(r.id -> expected)
        ReactDOM.unmountComponentAtNode(container)
      }
    }

    "show the linked expense's category and merchant names" in {
      val r = row("SUPERMARKET").copy(expenseId = Some(UUID.randomUUID()))
      val info = readyInfo.copy(
        isReady = false,
        resolved = None,
        linkedMerchantName = Some("Walmart"),
        linkedCategory = LinkedCategory.Named("Groceries")
      )
      render(List(r -> info), (_, _) => ())
      val cells = container.querySelectorAll("td")
      cells(4).textContent mustBe "Groceries"
      cells(5).textContent mustBe "Walmart"
    }

    "show Multiple and Linked when the linked expense has no single category or known merchant" in {
      val r = row("SUPERMARKET").copy(expenseId = Some(UUID.randomUUID()))
      val info = readyInfo.copy(isReady = false, resolved = None, linkedCategory = LinkedCategory.Multiple)
      render(List(r -> info), (_, _) => ())
      val cells = container.querySelectorAll("td")
      cells(4).textContent mustBe "Multiple"
      cells(5).textContent mustBe "Linked"
    }

    "report onToggleSelected when the checkbox is clicked" in {
      val r = row("SUPERMARKET")
      var toggled = List.empty[(UUID, Boolean)]
      renderProps(List(props(r, readyInfo, (_, _) => (), (id, checked) => toggled = toggled :+ (id -> checked))))
      container.querySelector("input[type=checkbox]").asInstanceOf[dom.html.Element].click()
      toggled mustBe List(r.id -> true)
    }

    // The memo is what keeps a menu toggle or one checkbox from re-rendering every other row. BigDecimal("-120.0")
    // equals BigDecimal("-120.00") but prints differently, so the rendered amount shows whether the body re-ran.
    "skip re-rendering when the props are equal and the callbacks are the same references" in {
      val r = row("SUPERMARKET")
      val onAction: (UUID, RowAction) => Unit = (_, _) => ()
      val onToggle: (UUID, Boolean) => Unit = (_, _) => ()
      renderProps(List(props(r, readyInfo, onAction, onToggle)))
      amountText mustBe "-120.00"
      renderProps(List(props(r.copy(amount = BigDecimal("-120.0")), readyInfo, onAction, onToggle)))
      amountText mustBe "-120.00"
    }

    "re-render when a callback is a new reference" in {
      val r = row("SUPERMARKET")
      val onToggle: (UUID, Boolean) => Unit = (_, _) => ()
      renderProps(List(props(r, readyInfo, (_, _) => (), onToggle)))
      renderProps(List(props(r.copy(amount = BigDecimal("-120.0")), readyInfo, (_, _) => (), onToggle)))
      amountText mustBe "-120.0"
    }
  }
}
