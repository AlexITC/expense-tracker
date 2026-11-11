package net.wiringbits.components.pages.bankstatementreview

import monix.execution.Scheduler
import net.wiringbits.api.ApiClient
import net.wiringbits.api.models.bankstatements.{AiSuggestions, AiSuggestionsStatus, GetBankStatement}
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.documents.DocumentStatus
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.api.models.merchants.{GetImportRules, GetMerchants, ImportRuleAction}
import net.wiringbits.api.models.receipts.GetReceipts
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.components.pages.bankstatementreview.BankStatementReviewLogic.{Loadable, ReviewSupport}
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import sttp.client3.testing.SttpBackendStub

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}

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
      aiSuggestions = suggestions,
      accountAlias = None
    )

  private val emptySupport = ReviewSupport(Nil, Nil, Nil, Nil, Nil)

  private def receipt(amount: Option[BigDecimal], expenseId: Option[UUID]): GetReceipts.Response.Receipt =
    GetReceipts.Response.Receipt(
      id = UUID.randomUUID(),
      documentFilename = "receipt.jpg",
      documentStatus = DocumentStatus.Parsed,
      amount = amount,
      date = Some(LocalDate.of(2024, 3, 7)),
      merchantName = None,
      expenseId = expenseId,
      ignoredAt = None,
      createdAt = Instant.EPOCH
    )

  /** Answers the load calls from the given values; a missing statement or a set `failMerchants`/`failReceipts` fails
    * that call.
    */
  private final class StubClient(
      stmt: Option[GetBankStatement.Response],
      failMerchants: Boolean = false,
      failReceipts: Boolean = false,
      receipts: List[GetReceipts.Response.Receipt] = Nil,
      merchants: List[GetMerchants.Response.Merchant] = Nil,
      rules: List[GetImportRules.Rule] = Nil,
      expenses: List[GetExpenses.Response.Expense] = Nil
  ) extends ApiClient(ApiClient.Config("http://localhost"))(using
        ExecutionContext.parasitic,
        SttpBackendStub.asynchronousFuture
      ) {
    val calls: ListBuffer[String] = ListBuffer.empty

    override def getBankStatement(bankStatementId: UUID): Future[GetBankStatement.Response] =
      stmt.fold(Future.failed(new RuntimeException("no statement")))(Future.successful)

    override def getMerchants: Future[GetMerchants.Response] =
      if (failMerchants) Future.failed(new RuntimeException("no merchants"))
      else Future.successful(GetMerchants.Response(merchants))

    override def getReceipts: Future[GetReceipts.Response] =
      if (failReceipts) Future.failed(new RuntimeException("no receipts"))
      else Future.successful(GetReceipts.Response(receipts))

    override def getCategories: Future[GetCategories.Response] = Future.successful(GetCategories.Response(Nil))

    override def getImportRules: Future[GetImportRules.Response] = Future.successful(GetImportRules.Response(rules))

    override def getExpensesFiltered(
        from: Option[LocalDate],
        to: Option[LocalDate],
        unlinkedOnly: Boolean
    ): Future[GetExpenses.Response] = {
      calls += s"expenses:$from:$to:$unlinkedOnly"
      Future.successful(GetExpenses.Response(expenses))
    }
  }

  "load" should {
    // Stubbed futures are already completed, so the parasitic context runs both stages synchronously.
    given ExecutionContext = ExecutionContext.parasitic

    "seed the statement, then the support lists with rules mapped and expenses from the candidate window" in {
      val resp = statement(List(sampleRow("A")))
      val apiRule = GetImportRules.Rule(
        UUID.randomUUID(),
        ImportRuleAction.SetCategory(UUID.randomUUID()),
        "SUPERMARKET",
        None,
        None,
        autoCreated = false,
        Instant.EPOCH,
        Instant.EPOCH
      )
      val merchant =
        GetMerchants.Response.Merchant(UUID.randomUUID(), "Shop", None, None, None, reviewed = true, Instant.EPOCH)
      val linked = receipt(Some(BigDecimal("12.00")), Some(UUID.randomUUID()))
      val noAmount = receipt(None, None)
      val kept = receipt(Some(BigDecimal("12.00")), None)
      val client = new StubClient(
        Some(resp),
        merchants = List(merchant),
        rules = List(apiRule),
        receipts = List(linked, noAmount, kept)
      )
      val store = new BankStatementReviewStore
      store.load(client, resp.id)
      store.$statement() mustBe Loadable.Loaded(resp)
      store.$rows() mustBe resp.statementRows
      store.$support() mustBe Loadable.Loaded(
        ReviewSupport(List(merchant), Nil, List(ImportRuleMatcher.Rule.fromApi(apiRule)), Nil, List(kept))
      )
      val (from, to) = BankStatementReviewLogic.candidateExpenseWindow(resp)
      client.calls.toList mustBe List(s"expenses:${Some(from)}:${Some(to)}:true")
    }

    "keep the statement loaded and mark only the support lists failed when one of them fails" in {
      val resp = statement(List(sampleRow("A")))
      val store = new BankStatementReviewStore
      store.load(new StubClient(Some(resp), failMerchants = true), resp.id)
      store.$statement() mustBe Loadable.Loaded(resp)
      store.$support() mustBe Loadable.Failed("no merchants")
    }

    "load the support lists without receipts when the receipts fail" in {
      val resp = statement(List(sampleRow("A")))
      val store = new BankStatementReviewStore
      store.load(new StubClient(Some(resp), failReceipts = true), resp.id)
      store.$statement() mustBe Loadable.Loaded(resp)
      store.$support() mustBe Loadable.Loaded(emptySupport)
    }

    "mark the statement failed and not load the support lists when the statement fails" in {
      val client = new StubClient(None)
      val store = new BankStatementReviewStore
      store.load(client, UUID.randomUUID())
      store.$statement() mustBe Loadable.Failed("no statement")
      store.$support() mustBe Loadable.Loading
      client.calls mustBe empty
    }
  }

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

  "setAccountAlias" should {
    "be ignored while the statement is loading" in {
      val store = new BankStatementReviewStore
      store.setAccountAlias("Travel card")
      store.$statement() mustBe Loadable.Loading
    }

    "be ignored once the statement failed" in {
      val store = new BankStatementReviewStore
      store.statementFailed("boom")
      store.setAccountAlias("Travel card")
      store.$statement() mustBe Loadable.Failed("boom")
    }

    "set the nickname on the loaded statement and keep its rows" in {
      val store = new BankStatementReviewStore
      val row = sampleRow("A")
      val stmt = statement(List(row))
      store.statementLoaded(stmt)
      store.setAccountAlias("Travel card")
      store.$statement() mustBe Loadable.Loaded(stmt.copy(accountAlias = Some("Travel card")))
      store.$rows() mustBe List(row)
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

  "refreshReceipts" should {
    given ExecutionContext = ExecutionContext.parasitic

    "replace the receipts with the refetched unlinked ones that have an amount" in {
      val stale = receipt(Some(BigDecimal("12.00")), None)
      val kept = receipt(Some(BigDecimal("30.00")), None)
      val nowLinked = stale.copy(expenseId = Some(UUID.randomUUID()))
      val noAmount = receipt(None, None)
      val store = new BankStatementReviewStore
      store.supportLoaded(emptySupport.copy(receipts = List(stale)))
      val errors = ListBuffer.empty[String]
      store.refreshReceipts(new StubClient(None, receipts = List(nowLinked, kept, noAmount)), errors += _)
      store.$support() mustBe Loadable.Loaded(emptySupport.copy(receipts = List(kept)))
      errors mustBe empty
    }

    "keep the current receipts and report the error when the fetch fails" in {
      val current = receipt(Some(BigDecimal("12.00")), None)
      val store = new BankStatementReviewStore
      store.supportLoaded(emptySupport.copy(receipts = List(current)))
      val errors = ListBuffer.empty[String]
      store.refreshReceipts(new StubClient(None, failReceipts = true), errors += _)
      store.$support() mustBe Loadable.Loaded(emptySupport.copy(receipts = List(current)))
      errors.toList mustBe List("no receipts")
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
