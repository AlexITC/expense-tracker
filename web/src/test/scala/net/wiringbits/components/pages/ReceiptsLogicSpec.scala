package net.wiringbits.components.pages

import net.wiringbits.api.models.documents.DocumentStatus
import net.wiringbits.api.models.merchants.{GetMerchants, ImportRuleAction}
import net.wiringbits.api.models.receipts.GetReceipts
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.components.pages.ReceiptsLogic.{BatchState, ConfirmForm, ReceiptMode}
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate}
import java.util.UUID

class ReceiptsLogicSpec extends AnyWordSpec {

  "BatchState" should {
    val ok = "a.pdf" -> Right(())
    val bad = "b.pdf" -> Left("boom")

    "have no results when idle" in {
      BatchState.Idle.results mustBe Nil
      BatchState.Idle.isRunning mustBe false
    }

    "count outcomes and failures while running" in {
      BatchState.Running(0, 3, 0, Nil).recorded(ok._1, ok._2).recorded(bad._1, bad._2) mustBe
        BatchState.Running(2, 3, 1, List(ok, bad))
    }

    "be running only while running" in {
      BatchState.Running(0, 1, 0, Nil).isRunning mustBe true
    }

    "expose results while running and when done" in {
      BatchState.Running(1, 2, 0, List(ok)).results mustBe List(ok)
      BatchState.Done(List(ok, bad)).results mustBe List(ok, bad)
    }

    "ignore outcomes when not running" in {
      BatchState.Idle.recorded(ok._1, ok._2) mustBe BatchState.Idle
      BatchState.Done(List(ok)).recorded(bad._1, bad._2) mustBe BatchState.Done(List(ok))
    }

    "keep the results when finished" in {
      val done = BatchState.Running(2, 2, 1, List(ok, bad)).finished
      done mustBe BatchState.Done(List(ok, bad))
      done.isRunning mustBe false
      BatchState.Idle.finished mustBe BatchState.Idle
    }

    "go idle when cleared after finishing" in {
      BatchState.Done(List(ok)).cleared mustBe BatchState.Idle
      BatchState.Idle.cleared mustBe BatchState.Idle
    }

    "keep counters when cleared mid-run" in {
      val cleared = BatchState.Running(2, 3, 1, List(ok, bad)).cleared
      cleared mustBe BatchState.Running(2, 3, 1, Nil)
      cleared.recorded(ok._1, ok._2) mustBe BatchState.Running(3, 3, 1, List(ok))
    }
  }

  "ReceiptMode" should {
    val id = UUID.randomUUID()
    val form = ConfirmForm("1", "2026-01-01", "", "", "", "", Nil, None, None)

    "select the viewed receipt, if any" in {
      ReceiptMode.Viewing(None).selectedId mustBe None
      ReceiptMode.Viewing(Some(id)).selectedId mustBe Some(id)
    }

    "select the receipt being confirmed" in {
      ReceiptMode.Confirming(id, form).selectedId mustBe Some(id)
    }

    "select the receipt being linked" in {
      ReceiptMode.Linking(id).selectedId mustBe Some(id)
    }

    "edit the form only while confirming" in {
      ReceiptMode.Confirming(id, form).withForm(_.copy(notes = "x")) mustBe
        ReceiptMode.Confirming(id, form.copy(notes = "x"))
      ReceiptMode.Viewing(Some(id)).withForm(_.copy(notes = "x")) mustBe ReceiptMode.Viewing(Some(id))
      ReceiptMode.Linking(id).withForm(_.copy(notes = "x")) mustBe ReceiptMode.Linking(id)
    }

    "return to viewing when the confirmed receipt is still being confirmed" in {
      ReceiptMode.Confirming(id, form).confirmed(id) mustBe ReceiptMode.Viewing(Some(id))
    }

    "stay put when the user moved on before the confirmation arrived" in {
      val otherId = UUID.randomUUID()
      ReceiptMode.Confirming(otherId, form).confirmed(id) mustBe ReceiptMode.Confirming(otherId, form)
      ReceiptMode.Linking(id).confirmed(id) mustBe ReceiptMode.Linking(id)
      ReceiptMode.Viewing(Some(otherId)).confirmed(id) mustBe ReceiptMode.Viewing(Some(otherId))
    }

    "keep the mode while its receipt is visible" in {
      val otherId = UUID.randomUUID()
      ReceiptMode.Confirming(id, form).keptVisible(List(otherId, id)) mustBe ReceiptMode.Confirming(id, form)
      ReceiptMode.Linking(id).keptVisible(List(id)) mustBe ReceiptMode.Linking(id)
    }

    "view the first visible receipt when the selected one is hidden" in {
      val otherId = UUID.randomUUID()
      ReceiptMode.Confirming(id, form).keptVisible(List(otherId)) mustBe ReceiptMode.Viewing(Some(otherId))
      ReceiptMode.Viewing(None).keptVisible(List(otherId)) mustBe ReceiptMode.Viewing(Some(otherId))
      ReceiptMode.Linking(id).keptVisible(Nil) mustBe ReceiptMode.Viewing(None)
    }
  }

  "ConfirmForm.fromReceipt" should {
    val acme = GetMerchants.Response.Merchant(
      UUID.randomUUID(),
      "Acme",
      None,
      None,
      taxId = Some("TAX1"),
      reviewed = true,
      Instant.EPOCH
    )
    val other =
      GetMerchants.Response.Merchant(UUID.randomUUID(), "Other", None, None, None, reviewed = true, Instant.EPOCH)
    val merchants = List(acme, other)
    val ruleToOther = ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.SetMerchant(other.id), "ACME")
    def receipt(
        amount: Option[BigDecimal] = Some(BigDecimal("12.50")),
        date: Option[LocalDate] = Some(LocalDate.parse("2026-03-04")),
        merchantName: Option[String] = Some("ACME STORE"),
        taxId: Option[String] = None
    ) = GetReceipts.Response.Receipt(
      id = UUID.randomUUID(),
      documentFilename = "r.pdf",
      documentStatus = DocumentStatus.Parsed,
      amount = amount,
      date = date,
      merchantName = merchantName,
      taxId = taxId,
      expenseId = None,
      ignoredAt = None,
      createdAt = Instant.EPOCH
    )

    "prefill amount, date and parsed merchant name with an otherwise empty form" in {
      ConfirmForm.fromReceipt(receipt(merchantName = Some("Unknown")), merchants, Nil) mustBe
        ConfirmForm("12.50", "2026-03-04", "", "", "", "", Nil, Some("Unknown"), None)
    }

    "leave amount and date empty when the receipt has none" in {
      val form = ConfirmForm.fromReceipt(receipt(amount = None, date = None, merchantName = None), merchants, Nil)
      form.amount mustBe ""
      form.date mustBe ""
      form.parsedMerchantName mustBe None
    }

    "prefer the merchant matching the tax id over rules" in {
      val form = ConfirmForm.fromReceipt(receipt(taxId = Some("TAX1")), merchants, List(ruleToOther))
      form.merchantId mustBe acme.id.toString
      form.merchantInput mustBe "Acme"
    }

    "fall back to the winning rule for the parsed merchant name" in {
      val form = ConfirmForm.fromReceipt(receipt(taxId = Some("NOPE")), merchants, List(ruleToOther))
      form.merchantId mustBe other.id.toString
      form.merchantInput mustBe "Other"
    }

    "leave the merchant input empty when the rule's merchant is unknown" in {
      val unknown = UUID.randomUUID()
      val rule = ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.SetMerchant(unknown), "ACME")
      val form = ConfirmForm.fromReceipt(receipt(), merchants, List(rule))
      form.merchantId mustBe unknown.toString
      form.merchantInput mustBe ""
    }
  }
}
