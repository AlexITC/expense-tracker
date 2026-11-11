package net.wiringbits.components.pages.bankstatementreview

import net.wiringbits.api.ApiClient
import net.wiringbits.api.models.bankstatements.{GetBankStatement, UnignoreRow, UnlinkRow}
import net.wiringbits.api.models.documents.DocumentStatus
import net.wiringbits.api.models.expenses.GetExpenses
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import slinky.core.facade.SetStateHookCallback
import sttp.client3.testing.SttpBackendStub

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js

class BankStatementRowActionsSpec extends AnyWordSpec {
  // Stubbed futures are already completed, so the parasitic context runs each action synchronously.
  private implicit val ec: ExecutionContext = ExecutionContext.parasitic

  private val stmtId = UUID.randomUUID()
  private val window = (LocalDate.of(2024, 2, 1), LocalDate.of(2024, 4, 30))

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

  private def expense(): GetExpenses.Response.Expense =
    GetExpenses.Response.Expense(
      id = UUID.randomUUID(),
      amount = BigDecimal("120.00"),
      date = LocalDate.of(2024, 3, 7),
      time = None,
      merchantId = None,
      merchantName = None,
      notes = None,
      splits = Nil,
      createdAt = Instant.EPOCH
    )

  private def statement(rows: List[GetBankStatement.StatementRowData]): GetBankStatement.Response =
    GetBankStatement.Response(
      id = stmtId,
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
      aiSuggestions = None,
      accountAlias = None
    )

  /** Records the latest value a setter or updater produces, applying updater functions like React does. */
  private final class Recorder[T](initial: T) {
    var value: T = initial
    val callback: SetStateHookCallback[T] = new SetStateHookCallback[T]((arg: js.Any) => {
      value =
        if (js.typeOf(arg) == "function") arg.asInstanceOf[js.Function1[T, T]](value)
        else arg.asInstanceOf[T]
    })
    val update: (T => T) => Unit = f => value = f(value)
  }

  /** Answers the single-row calls, failing those named in `fail`, and records the calls in order. */
  private final class StubClient(
      fail: Set[String] = Set.empty,
      statementRows: List[GetBankStatement.StatementRowData] = Nil,
      expenses: List[GetExpenses.Response.Expense] = Nil
  ) extends ApiClient(ApiClient.Config("http://localhost"))(using
        ExecutionContext.parasitic,
        SttpBackendStub.asynchronousFuture
      ) {
    val calls: ListBuffer[String] = ListBuffer.empty

    private def answer[T](call: String, value: T): Future[T] = {
      calls += call
      if (fail.contains(call)) Future.failed(new RuntimeException(s"$call boom"))
      else Future.successful(value)
    }

    override def unlinkRow(bankStatementId: UUID, rowId: UUID): Future[UnlinkRow.Response] =
      answer("unlink", UnlinkRow.Response())

    override def unignoreRow(bankStatementId: UUID, rowId: UUID): Future[UnignoreRow.Response] =
      answer("unignore", UnignoreRow.Response())

    override def getBankStatement(bankStatementId: UUID): Future[GetBankStatement.Response] =
      answer("statement", statement(statementRows))

    override def getExpensesFiltered(
        from: Option[LocalDate],
        to: Option[LocalDate],
        unlinkedOnly: Boolean
    ): Future[GetExpenses.Response] =
      answer(s"expenses:$from:$to:$unlinkedOnly", GetExpenses.Response(expenses))
  }

  private final class Fixture(client: StubClient, initialRows: List[GetBankStatement.StatementRowData]) {
    val rows = new Recorder(initialRows)
    val expenses = new Recorder[Option[List[GetExpenses.Response.Expense]]](None)
    val error = new Recorder("")
    val actions: BankStatementRowActions = BankStatementRowActions(
      client = client,
      stmtId = stmtId,
      expenseWindow = window,
      updateRows = rows.update,
      setSelectedRowIds = new Recorder[Set[UUID]](Set.empty).callback,
      setExpenses = es => expenses.value = Some(es),
      setError = error.callback,
      showToast = _ => ()
    )
  }

  private val expensesCall = s"expenses:${Some(window._1)}:${Some(window._2)}:true"

  "unlink" should {
    "clear the row's expense link and refresh the expenses" in {
      val linked = sampleRow("LINKED").copy(
        expenseId = Some(UUID.randomUUID()),
        expenseMerchantId = Some(UUID.randomUUID()),
        expenseCategoryId = Some(UUID.randomUUID())
      )
      val other = sampleRow("OTHER").copy(expenseId = Some(UUID.randomUUID()))
      val fresh = List(expense())
      val client = new StubClient(expenses = fresh)
      val f = new Fixture(client, List(linked, other))

      f.actions.unlink(linked.id)

      client.calls.toList mustBe List("unlink", expensesCall)
      f.rows.value mustBe List(
        linked.copy(expenseId = None, expenseMerchantId = None, expenseCategoryId = None),
        other
      )
      f.expenses.value mustBe Some(fresh)
      f.error.value mustBe ""
    }

    "report a failure and leave the rows and expenses untouched" in {
      val linked = sampleRow("LINKED").copy(expenseId = Some(UUID.randomUUID()))
      val client = new StubClient(fail = Set("unlink"))
      val f = new Fixture(client, List(linked))

      f.actions.unlink(linked.id)

      client.calls.toList mustBe List("unlink")
      f.rows.value mustBe List(linked)
      f.expenses.value mustBe None
      f.error.value mustBe "unlink boom"
    }
  }

  "unignore" should {
    "clear the row's ignored mark" in {
      val ignored = sampleRow("IGNORED").copy(ignoredAt = Some(Instant.EPOCH))
      val f = new Fixture(new StubClient(), List(ignored))

      f.actions.unignore(ignored.id)

      f.rows.value mustBe List(ignored.copy(ignoredAt = None))
    }
  }

  "refreshRow" should {
    "replace only the target row with its fresh copy and refresh the expenses" in {
      val target = sampleRow("TARGET")
      val other = sampleRow("OTHER")
      val freshTarget =
        target.copy(expenseId = Some(UUID.randomUUID()), expenseMerchantId = Some(UUID.randomUUID()))
      val freshOther = other.copy(description = "CHANGED ON SERVER")
      val fresh = List(expense())
      val client = new StubClient(statementRows = List(freshTarget, freshOther), expenses = fresh)
      val f = new Fixture(client, List(target, other))

      f.actions.refreshRow(target.id)

      client.calls.toList mustBe List("statement", expensesCall)
      f.rows.value mustBe List(freshTarget, other)
      f.expenses.value mustBe Some(fresh)
    }

    "report a failed statement read and leave the rows untouched" in {
      val target = sampleRow("TARGET")
      val f = new Fixture(new StubClient(fail = Set("statement")), List(target))

      f.actions.refreshRow(target.id)

      f.rows.value mustBe List(target)
      f.error.value mustBe "statement boom"
    }
  }
}
