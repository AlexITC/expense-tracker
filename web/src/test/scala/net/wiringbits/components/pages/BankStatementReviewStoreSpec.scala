package net.wiringbits.components.pages

import monix.execution.Scheduler
import net.wiringbits.api.models.bankstatements.{AiSuggestions, AiSuggestionsStatus, GetBankStatement}
import net.wiringbits.api.models.documents.DocumentStatus
import net.wiringbits.api.models.merchants.ImportRuleAction
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.components.pages.BankStatementReviewLogic.{Loadable, ReviewSupport}
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate}
import java.util.UUID

class BankStatementReviewStoreSpec extends AnyWordSpec {

  private given Scheduler = Scheduler.trampoline()

  private def sampleRow(description: String): GetBankStatement.StatementRowData =
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

  private val suggestions = Some(AiSuggestions(AiSuggestionsStatus.Done, "eu.amazon.nova-lite-v1:0", Nil))

  private def statement(rows: List[GetBankStatement.StatementRowData]): GetBankStatement.Response =
    GetBankStatement.Response(
      id = UUID.randomUUID(),
      documentId = UUID.randomUUID(),
      documentFilename = "statement.pdf",
      documentStatus = DocumentStatus.Parsed,
      bankName = "Bank",
      productType = "credit",
      currency = "USD",
      accountLast4 = "1234",
      periodStart = LocalDate.of(2024, 3, 1),
      periodEnd = LocalDate.of(2024, 3, 31),
      statementRows = rows,
      createdAt = Instant.EPOCH,
      extractionInfo = None,
      aiSuggestions = suggestions
    )

  private val emptySupport = ReviewSupport(Nil, Nil, Nil, Nil)

  "statementLoaded" should {
    "seed the statement, rows and suggestions together" in {
      val store = new BankStatementReviewStore
      val rows = List(sampleRow("A"), sampleRow("B"))
      val resp = statement(rows)
      store.statementLoaded(resp)
      store.$statement() mustBe Loadable.Loaded(resp)
      store.$rows() mustBe rows
      store.$suggestions() mustBe suggestions
    }
  }

  "statementFailed" should {
    "mark the statement as failed" in {
      val store = new BankStatementReviewStore
      store.statementFailed("boom")
      store.$statement() mustBe Loadable.Failed("boom")
    }
  }

  "patchRow" should {
    "change only the target row" in {
      val store = new BankStatementReviewStore
      val a = sampleRow("A")
      val b = sampleRow("B")
      store.statementLoaded(statement(List(a, b)))
      val expenseId = UUID.randomUUID()
      store.patchRow(a.id)(_.copy(expenseId = Some(expenseId)))
      store.$rows() mustBe List(a.copy(expenseId = Some(expenseId)), b)
    }
  }

  "updateSupport" should {
    "be ignored while the support lists are loading" in {
      val store = new BankStatementReviewStore
      store.updateSupport(_.copy(expenses = Nil))
      store.$support() mustBe Loadable.Loading
    }

    "be ignored once the support lists failed" in {
      val store = new BankStatementReviewStore
      store.supportFailed("boom")
      store.updateSupport(_ => emptySupport)
      store.$support() mustBe Loadable.Failed("boom")
    }

    "update the loaded support lists" in {
      val store = new BankStatementReviewStore
      store.supportLoaded(emptySupport)
      val rule = ImportRuleMatcher.Rule(
        UUID.randomUUID(),
        ImportRuleAction.SetCategory(UUID.randomUUID()),
        "SUPERMARKET"
      )
      store.updateSupport(_.withRule(rule))
      store.$support() mustBe Loadable.Loaded(emptySupport.withRule(rule))
    }
  }

  "setSuggestions" should {
    "notify only the suggestions subscribers" in {
      val store = new BankStatementReviewStore
      store.statementLoaded(statement(List(sampleRow("A"))))
      var rowEmissions = 0
      var statementEmissions = 0
      val subs = List(
        store.$rows.foreach(_ => rowEmissions += 1),
        store.$statement.foreach(_ => statementEmissions += 1)
      )
      // Subscribing replays the current value once.
      store.setSuggestions(None)
      subs.foreach(_.cancel())
      store.$suggestions() mustBe None
      rowEmissions mustBe 1
      statementEmissions mustBe 1
    }
  }
}
