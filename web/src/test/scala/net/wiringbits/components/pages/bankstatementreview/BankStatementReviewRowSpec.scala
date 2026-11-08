package net.wiringbits.components.pages.bankstatementreview

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.api.models.bankstatements.GetBankStatement
import net.wiringbits.api.models.documents.DocumentStatus
import net.wiringbits.api.models.receipts.GetReceipts
import net.wiringbits.api.utils.RowRuleVerdict
import net.wiringbits.components.pages.bankstatementreview.BankStatementReviewLogic.{
  LinkedCategory,
  RefundPrefill,
  ResolvedImport,
  RowInfo
}
import net.wiringbits.components.pages.bankstatementreview.BankStatementReviewRow.{ReceiptAction, RowAction}
import net.wiringbits.core.ReactTestDom
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.BrowserRouter
import org.scalajs.dom
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import slinky.core.facade.{Fragment, ReactElement}

import java.time.{Instant, LocalDate}
import java.util.UUID

class BankStatementReviewRowSpec extends AnyWordSpec with ReactTestDom {

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
    refund = None,
    prefillMerchantId = None,
    isReady = true,
    merchantName = None,
    categoryName = Some("Groceries"),
    linkedMerchantName = None,
    linkedCategory = LinkedCategory.Unknown
  )
  private val ignoreInfo =
    RowInfo(RowRuleVerdict.Ignore, None, None, None, isReady = true, None, None, None, LinkedCategory.Unknown)

  private def receipt: GetReceipts.Response.Receipt =
    GetReceipts.Response.Receipt(
      id = UUID.randomUUID(),
      documentFilename = "receipt-1.pdf",
      documentStatus = DocumentStatus.Parsed,
      amount = Some(BigDecimal("120.00")),
      date = Some(LocalDate.of(2024, 3, 6)),
      merchantName = None,
      taxId = None,
      merchantId = None,
      expenseId = None,
      ignoredAt = None,
      createdAt = Instant.parse("2024-03-06T10:00:00Z")
    )

  // One reference shared by every render, so the memo tests only see the callbacks they vary.
  private val noReceiptAction: (UUID, ReceiptAction) => Unit = (_, _) => ()

  private def props(
      r: GetBankStatement.StatementRowData,
      info: RowInfo,
      onAction: (UUID, RowAction) => Unit,
      onToggleSelected: (UUID, Boolean) => Unit = (_, _) => (),
      linksToExpense: Boolean = false,
      matchedReceipt: Option[GetReceipts.Response.Receipt] = None,
      receiptDetached: Boolean = false,
      onReceiptAction: (UUID, ReceiptAction) => Unit = noReceiptAction
  ): BankStatementReviewRow.Props =
    BankStatementReviewRow.Props(
      row = r,
      info = info,
      selected = false,
      showCheckbox = true,
      pollingSuggestions = false,
      linksToExpense = linksToExpense,
      matchedReceipt = matchedReceipt,
      receiptDetached = receiptDetached,
      onToggleSelected = onToggleSelected,
      onAction = onAction,
      onReceiptAction = onReceiptAction
    )

  private def renderProps(rowProps: List[BankStatementReviewRow.Props]): Unit =
    render(
      BrowserRouter(basename = "")(
        mui.Table(
          mui.TableBody(
            Fragment(rowProps.map(p => (BankStatementReviewRow(p).withKey(p.row.id.toString): ReactElement))*)
          )
        )
      ): ReactElement
    )

  private def render(rows: List[(GetBankStatement.StatementRowData, RowInfo)], onAction: (UUID, RowAction) => Unit) =
    renderProps(rows.map { case (r, info) => props(r, info, onAction) })

  private def clickButton(label: String): Unit = {
    val buttons = container.querySelectorAll("button")
    val matching = (0 until buttons.length).map(buttons(_)).filter(_.textContent == label)
    matching.length mustBe 1
    act(matching.head.asInstanceOf[dom.html.Element].click())
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
      act(buttons(buttons.length - 1).asInstanceOf[dom.html.Element].click())
      val items = dom.document.body.querySelectorAll("[role=menuitem]")
      items.length mustBe 3
      act(items(2).asInstanceOf[dom.html.Element].click())

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
        act(buttons(buttons.length - 1).asInstanceOf[dom.html.Element].click())
        act(dom.document.body.querySelectorAll("[role=menuitem]")(index).asInstanceOf[dom.html.Element].click())
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
        act(buttons(buttons.length - 1).asInstanceOf[dom.html.Element].click())
        val items = dom.document.body.querySelectorAll("[role=menuitem]")
        items.length mustBe 1
        items(0).textContent mustBe label
        act(items(0).asInstanceOf[dom.html.Element].click())
        received mustBe List(r.id -> expected)
      // Each case renders a row with a distinct key, so the next render unmounts this row (and its menu);
      // no explicit unmount needed between iterations.
      }
    }

    "show a rule-matched credit's refund category" in {
      val r = row("REFUND").copy(amount = BigDecimal("45.00"))
      val info = readyInfo.copy(
        verdict = RowRuleVerdict.SetCategory(UUID.randomUUID()),
        resolved = None,
        refund = Some(RefundPrefill(None, Some(UUID.randomUUID()))),
        categoryName = Some("Groceries")
      )
      render(List(r -> info), (_, _) => ())
      container.querySelectorAll("td")(4).textContent mustBe "Groceries"
    }

    "show a dash for a credit whose refund has no category" in {
      val r = row("REFUND").copy(amount = BigDecimal("45.00"))
      val info = readyInfo.copy(
        verdict = RowRuleVerdict.SetMerchant(UUID.randomUUID()),
        resolved = None,
        refund = Some(RefundPrefill(Some(UUID.randomUUID()), None)),
        categoryName = None
      )
      render(List(r -> info), (_, _) => ())
      container.querySelectorAll("td")(4).textContent mustBe "—"
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

    "show the Link existing chip only when the row would link to an existing expense" in {
      val r = row("SUPERMARKET")
      renderProps(List(props(r, readyInfo, (_, _) => (), linksToExpense = true)))
      container.textContent must include("↔ Link existing")
      renderProps(List(props(r, readyInfo, (_, _) => (), linksToExpense = false)))
      container.textContent must not include ("↔ Link existing")
    }

    "show the receipt chip only when the row has a matched receipt" in {
      val r = row("SUPERMARKET")
      renderProps(List(props(r, readyInfo, (_, _) => (), matchedReceipt = Some(receipt))))
      container.textContent must include("🧾 Receipt")
      renderProps(List(props(r, readyInfo, (_, _) => ())))
      container.textContent must not include ("Receipt")
    }

    "report View with the receipt's id and filename for its own row" in {
      val r = row("SUPERMARKET")
      val rec = receipt
      var received = List.empty[(UUID, ReceiptAction)]
      renderProps(
        List(
          props(
            r,
            readyInfo,
            (_, _) => (),
            matchedReceipt = Some(rec),
            onReceiptAction = (id, action) => received = received :+ (id -> action)
          )
        )
      )
      clickButton("View")
      received mustBe List(r.id -> ReceiptAction.View(rec.id, "receipt-1.pdf"))
    }

    "report Detach, and Reattach once the receipt is detached" in {
      val r = row("SUPERMARKET")
      var received = List.empty[(UUID, ReceiptAction)]
      val onReceiptAction: (UUID, ReceiptAction) => Unit = (id, action) => received = received :+ (id -> action)
      renderProps(
        List(props(r, readyInfo, (_, _) => (), matchedReceipt = Some(receipt), onReceiptAction = onReceiptAction))
      )
      clickButton("Detach")
      renderProps(
        List(
          props(
            r,
            readyInfo,
            (_, _) => (),
            matchedReceipt = Some(receipt),
            receiptDetached = true,
            onReceiptAction = onReceiptAction
          )
        )
      )
      container.textContent must include("Receipt detached")
      clickButton("Re-attach")
      received mustBe List(r.id -> ReceiptAction.Detach, r.id -> ReceiptAction.Reattach)
    }

    "report onToggleSelected when the checkbox is clicked" in {
      val r = row("SUPERMARKET")
      var toggled = List.empty[(UUID, Boolean)]
      renderProps(List(props(r, readyInfo, (_, _) => (), (id, checked) => toggled = toggled :+ (id -> checked))))
      act(container.querySelector("input[type=checkbox]").asInstanceOf[dom.html.Element].click())
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
