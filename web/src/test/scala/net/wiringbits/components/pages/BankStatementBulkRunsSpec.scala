package net.wiringbits.components.pages

import net.wiringbits.api.ApiClient
import net.wiringbits.api.models.bankstatements.{CreateExpenseFromRow, GetBankStatement, IgnoreRow, LinkRowToExpense}
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.api.utils.RowRuleVerdict
import net.wiringbits.components.pages.BankStatementReviewLogic.*
import net.wiringbits.utils.ReconciliationMatcher
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import slinky.core.facade.SetStateHookCallback
import sttp.client3.testing.SttpBackendStub

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js

class BankStatementBulkRunsSpec extends AnyWordSpec {
  // Stubbed futures are already completed, so the parasitic context runs the whole loop synchronously.
  private implicit val ec: ExecutionContext = ExecutionContext.parasitic

  private val stmtId = UUID.randomUUID()

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

  private def info(verdict: RowRuleVerdict.Verdict, resolved: Option[ResolvedImport] = None): RowInfo =
    RowInfo(
      verdict = verdict,
      resolved = resolved,
      isReady = true,
      merchantName = None,
      categoryName = None,
      linkedMerchantName = None,
      linkedCategory = LinkedCategory.Unknown
    )

  /** Records every state a setter produces, applying updater functions to the current value like React does. */
  private final class Recorder[T](initial: T) {
    var value: T = initial
    val states: ListBuffer[T] = ListBuffer.empty
    val callback: SetStateHookCallback[T] = new SetStateHookCallback[T]((arg: js.Any) => {
      value =
        if (js.typeOf(arg) == "function") arg.asInstanceOf[js.Function1[T, T]](value)
        else arg.asInstanceOf[T]
      states += value
    })
  }

  /** Answers the bulk calls from the given outcomes and records the calls in order. */
  private final class StubClient(failIgnoreFor: Set[UUID] = Set.empty, createdExpenseId: UUID = UUID.randomUUID())
      extends ApiClient(ApiClient.Config("http://localhost"))(using
        ExecutionContext.parasitic,
        SttpBackendStub.asynchronousFuture
      ) {
    val calls: ListBuffer[String] = ListBuffer.empty

    override def ignoreRow(bankStatementId: UUID, rowId: UUID): Future[IgnoreRow.Response] = {
      calls += s"ignore:$rowId"
      if (failIgnoreFor.contains(rowId)) Future.failed(new RuntimeException("boom"))
      else Future.successful(IgnoreRow.Response())
    }

    override def linkRowToExpense(
        bankStatementId: UUID,
        rowId: UUID,
        request: LinkRowToExpense.Request
    ): Future[LinkRowToExpense.Response] = {
      calls += s"link:$rowId:${request.expenseId}"
      Future.successful(LinkRowToExpense.Response())
    }

    override def createExpenseFromRow(
        bankStatementId: UUID,
        rowId: UUID,
        request: CreateExpenseFromRow.Request
    ): Future[CreateExpenseFromRow.Response] = {
      calls += s"create:$rowId"
      Future.successful(CreateExpenseFromRow.Response(createdExpenseId))
    }
  }

  "handleBulkIgnore" should {
    "ignore rows in order, record each outcome and keep going after a failure" in {
      val ok = sampleRow("OK")
      val bad = sampleRow("BAD")
      val client = new StubClient(failIgnoreFor = Set(bad.id))
      val rows = new Recorder(List(ok, bad))
      val progress = new Recorder[BulkProgress](None)
      val results = new Recorder[BulkResults](Nil)
      val selected = new Recorder[Set[UUID]](Set(ok.id, bad.id))

      BankStatementBulkRuns.handleBulkIgnore(
        client = client,
        stmtId = stmtId,
        ids = List(ok.id, bad.id),
        setStatementRows = rows.callback,
        setBulkIgnoreProgress = progress.callback,
        setBulkIgnoreResults = results.callback,
        setSelectedRowIds = selected.callback
      )

      client.calls.toList mustBe List(s"ignore:${ok.id}", s"ignore:${bad.id}")
      results.value mustBe List(ok.id -> Right(()), bad.id -> Left("boom"))
      progress.states.toList mustBe List(Some((0, 2, 0)), Some((1, 2, 0)), Some((2, 2, 1)), None)
      rows.value.find(_.id == ok.id).flatMap(_.ignoredAt) must not be empty
      rows.value.find(_.id == bad.id).flatMap(_.ignoredAt) mustBe None
      selected.value mustBe Set.empty
    }
  }

  "handleBulkImport" should {
    def expense(categoryId: UUID, merchantId: UUID): GetExpenses.Response.Expense =
      GetExpenses.Response.Expense(
        id = UUID.randomUUID(),
        amount = BigDecimal("120.00"),
        date = LocalDate.of(2024, 3, 7),
        time = None,
        merchantId = Some(merchantId),
        merchantName = None,
        notes = None,
        splits = List(GetExpenses.Response.Split(UUID.randomUUID(), categoryId, BigDecimal("120.00"))),
        createdAt = Instant.now()
      )

    "link a matched expense only once per run" in {
      val categoryId = UUID.randomUUID()
      val merchantId = UUID.randomUUID()
      val exp = expense(categoryId, merchantId)
      val first = sampleRow("FIRST")
      val second = sampleRow("SECOND")
      val client = new StubClient()
      val rows = new Recorder(List(first, second))
      val progress = new Recorder[BulkProgress](None)
      val results = new Recorder[BulkResults](Nil)

      BankStatementBulkRuns.handleBulkImport(
        client = client,
        stmtId = stmtId,
        ids = List(first.id, second.id),
        statementRows = List(first, second),
        rowInfoById = Map(first.id -> info(RowRuleVerdict.NoRule), second.id -> info(RowRuleVerdict.NoRule)),
        expenseMatchById = Map(
          first.id -> ReconciliationMatcher.AutoLink(exp.id),
          second.id -> ReconciliationMatcher.AutoLink(exp.id)
        ),
        expenseList = List(exp),
        setStatementRows = rows.callback,
        setBulkImportProgress = progress.callback,
        setBulkImportResults = results.callback,
        setSelectedRowIds = new Recorder[Set[UUID]](Set.empty).callback,
        showToast = _ => (),
        onRefresh = () => ()
      )

      client.calls.toList mustBe List(s"link:${first.id}:${exp.id}")
      results.value.map(_._1) mustBe List(first.id, second.id)
      results.value.head._2 mustBe Right(())
      results.value(1)._2.isLeft mustBe true
      progress.states.toList mustBe List(Some((0, 2, 0)), Some((1, 2, 0)), Some((2, 2, 1)), None)
      val linked = rows.value.find(_.id == first.id).get
      linked.expenseId mustBe Some(exp.id)
      linked.expenseMerchantId mustBe Some(merchantId)
      linked.expenseCategoryId mustBe Some(categoryId)
      rows.value.find(_.id == second.id).get.expenseId mustBe None
    }

    "create, ignore, then clear progress and selection, toast the summary and refresh once" in {
      val categoryId = UUID.randomUUID()
      val merchantId = UUID.randomUUID()
      val createdExpenseId = UUID.randomUUID()
      val toCreate = sampleRow("CREATE")
      val toIgnore = sampleRow("IGNORE")
      val client = new StubClient(createdExpenseId = createdExpenseId)
      val rows = new Recorder(List(toCreate, toIgnore))
      val progress = new Recorder[BulkProgress](None)
      val selected = new Recorder[Set[UUID]](Set(toCreate.id, toIgnore.id))
      val events = ListBuffer.empty[String]
      val resolved = ResolvedImport(merchantId = Some(merchantId), categoryId = categoryId, categoryFromAi = false)

      BankStatementBulkRuns.handleBulkImport(
        client = client,
        stmtId = stmtId,
        ids = List(toCreate.id, toIgnore.id),
        statementRows = List(toCreate, toIgnore),
        rowInfoById = Map(
          toCreate.id -> info(RowRuleVerdict.NoRule, Some(resolved)),
          toIgnore.id -> info(RowRuleVerdict.Ignore)
        ),
        expenseMatchById = Map.empty,
        expenseList = Nil,
        setStatementRows = rows.callback,
        setBulkImportProgress = progress.callback,
        setBulkImportResults = new Recorder[BulkResults](Nil).callback,
        setSelectedRowIds = selected.callback,
        showToast = msg => events += s"toast:$msg:${progress.value}:${selected.value}",
        onRefresh = () => events += "refresh"
      )

      client.calls.toList mustBe List(s"create:${toCreate.id}", s"ignore:${toIgnore.id}")
      val created = rows.value.find(_.id == toCreate.id).get
      created.expenseId mustBe Some(createdExpenseId)
      created.expenseMerchantId mustBe Some(merchantId)
      created.expenseCategoryId mustBe Some(categoryId)
      rows.value.find(_.id == toIgnore.id).flatMap(_.ignoredAt) must not be empty
      events.toList mustBe List(s"toast:${bulkImportSummary(0, 1, 1, 0)}:None:Set()", "refresh")
    }
  }
}
