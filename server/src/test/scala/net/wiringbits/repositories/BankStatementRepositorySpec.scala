package net.wiringbits.repositories

import anorm.*
import net.wiringbits.api.models.bankstatements.{AiSuggestions, AiSuggestionsStatus, ExtractionInfo}
import net.wiringbits.api.models.bankstatementtemplates.ExtractionSource
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.models.documents.{DocumentKind, DocumentStatus}
import net.wiringbits.repositories.models.{BankStatement, BankStatementRow, Document, Expense, ExpenseCategorySplit}
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.{Instant, LocalDate}
import java.util.UUID

class BankStatementRepositorySpec extends RepositorySpec with RepositoryUtils {

  private def buildRow(
      stmtId: UUID,
      index: Int = 0,
      amount: BigDecimal = BigDecimal("100")
  ): BankStatementRow.Create = {
    val now = Instant.now()
    BankStatementRow.Create(
      id = UUID.randomUUID(),
      bankStatementId = stmtId,
      index = index,
      date = LocalDate.of(2024, 1, 15),
      amount = amount,
      description = "Test transaction",
      manuallyEdited = false,
      createdAt = now,
      updatedAt = now,
      txnType = "purchase"
    )
  }

  "createStatement" should {
    "work with no rows" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      createBankStatement(user.id).futureValue
    }

    "work with rows" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc = createDocument(user.id, kind = DocumentKind.BankStatementPdf).futureValue
      val now = Instant.now()
      val stmt = BankStatement.Create(UUID.randomUUID(), doc.id, user.id, now, now)
      val rows = List(buildRow(stmt.id, 0), buildRow(stmt.id, 1))
      createStatement(stmt, sampleStatementMetadata, rows).futureValue

      val found = repositories.bankStatements.findRows(stmt.id).futureValue
      found.length must be(2)
    }
  }

  "createConfirmedStatement" should {
    def confirm(userId: UUID, extractionInfo: Option[ExtractionInfo])(implicit
        repositories: RepositoryComponents
    ): UUID = {
      val now = Instant.now()
      val document = Document.Create(
        id = UUID.randomUUID(),
        userId = userId,
        sha256 = UUID.randomUUID().toString,
        storageKey = "key",
        kind = DocumentKind.BankStatementPdf,
        filename = "stmt.pdf",
        status = DocumentStatus.Parsed,
        createdAt = now,
        updatedAt = now
      )
      val stmt = BankStatement.Create(UUID.randomUUID(), document.id, userId, now, now)
      repositories.bankStatements
        .createConfirmedStatement(document, stmt, sampleStatementMetadata, List(buildRow(stmt.id)), extractionInfo)
        .futureValue
    }

    "persist the extraction info" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val info = ExtractionInfo(ExtractionSource.TextractTables, "model-x", reconciled = false, Some("off by 1"))
      val id = confirm(user.id, Some(info))

      repositories.bankStatements.find(id, user.id).futureValue.value.extractionInfo must be(Some(info))
      repositories.bankStatements.all(user.id).futureValue.map(_.extractionInfo) must be(List(Some(info)))
    }

    "store no extraction info when none is given" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val id = confirm(user.id, None)

      repositories.bankStatements.find(id, user.id).futureValue.value.extractionInfo must be(None)
    }

    "fail to read a stored extraction info with an unknown stage" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val id = confirm(user.id, None)
      // No repository API writes invalid JSON, so corrupt the stored value directly.
      val corrupt =
        """{
          |  "stage": "bogus",
          |  "model": "m",
          |  "reconciled": true
          |}""".stripMargin
      repositories.database.withConnection { implicit conn =>
        val _ = SQL"""
          UPDATE bank_statement
          SET extraction_info = $corrupt::JSONB
          WHERE id = ${id.toString}::UUID
          """.execute()
      }

      val ex = intercept[Exception](repositories.bankStatements.find(id, user.id).futureValue)
      ex.getMessage must include("ExtractionInfo")
    }
  }

  "find" should {
    "return the statement when it belongs to the user" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createBankStatement(user.id).futureValue

      val found = repositories.bankStatements.find(created.id, user.id).futureValue
      found.value.id must be(created.id)
    }

    "return None when the statement belongs to another user" in withRepositories() { implicit repositories =>
      val userA = createUser(email = net.wiringbits.common.models.Email.trusted("a@test.com")).futureValue
      val userB = createUser(email = net.wiringbits.common.models.Email.trusted("b@test.com")).futureValue
      val created = createBankStatement(userA.id).futureValue

      repositories.bankStatements.find(created.id, userB.id).futureValue must be(None)
    }
  }

  "all" should {
    "return only the requesting user's statements" in withRepositories() { implicit repositories =>
      val userA = createUser(email = net.wiringbits.common.models.Email.trusted("a@test.com")).futureValue
      val userB = createUser(email = net.wiringbits.common.models.Email.trusted("b@test.com")).futureValue
      createBankStatement(userA.id).futureValue
      createBankStatement(userA.id).futureValue
      createBankStatement(userB.id).futureValue

      val results = repositories.bankStatements.all(userA.id).futureValue
      results.length must be(2)
    }

    "return an empty list when the user has no statements" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      repositories.bankStatements.all(user.id).futureValue must be(empty)
    }
  }

  "setAiSuggestions" should {
    def sampleSuggestions(status: AiSuggestionsStatus): AiSuggestions =
      AiSuggestions(
        status = status,
        model = "model-x",
        rows = List(
          AiSuggestions.RowSuggestion(UUID.randomUUID(), Some(UUID.randomUUID()), Some(UUID.randomUUID())),
          AiSuggestions.RowSuggestion(UUID.randomUUID(), None, None)
        )
      )

    "leave the suggestions empty until they are set" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createBankStatement(user.id).futureValue

      repositories.bankStatements.find(created.id, user.id).futureValue.value.aiSuggestions must be(None)
      repositories.bankStatements.all(user.id).futureValue.map(_.aiSuggestions) must be(List(None))
    }

    AiSuggestionsStatus.values.foreach { status =>
      s"persist suggestions with status ${status.entryName}" in withRepositories() { implicit repositories =>
        val user = createUser().futureValue
        val created = createBankStatement(user.id).futureValue
        val suggestions = sampleSuggestions(status)

        repositories.bankStatements.setAiSuggestions(created.id, user.id, suggestions, Instant.now()).futureValue

        repositories.bankStatements.find(created.id, user.id).futureValue.value.aiSuggestions must be(
          Some(suggestions)
        )
        repositories.bankStatements.all(user.id).futureValue.map(_.aiSuggestions) must be(List(Some(suggestions)))
      }
    }

    "fail when the statement belongs to another user" in withRepositories() { implicit repositories =>
      val userA = createUser(email = net.wiringbits.common.models.Email.trusted("a@test.com")).futureValue
      val userB = createUser(email = net.wiringbits.common.models.Email.trusted("b@test.com")).futureValue
      val created = createBankStatement(userA.id).futureValue

      val ex = intercept[RuntimeException] {
        repositories.bankStatements
          .setAiSuggestions(created.id, userB.id, sampleSuggestions(AiSuggestionsStatus.Done), Instant.now())
          .futureValue
      }
      ex.getMessage must include("Bank statement not found")
      repositories.bankStatements.find(created.id, userA.id).futureValue.value.aiSuggestions must be(None)
    }

    "fail to read stored suggestions with an unknown status" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createBankStatement(user.id).futureValue
      // No repository API writes an unknown status, so corrupt the stored value directly.
      val corrupt =
        """{
          |  "status": "bogus",
          |  "model": "m",
          |  "rows": []
          |}""".stripMargin
      repositories.database.withConnection { implicit conn =>
        val _ = SQL"""
          UPDATE bank_statement
          SET ai_suggestions = $corrupt::JSONB
          WHERE id = ${created.id.toString}::UUID
          """.execute()
      }

      val ex = intercept[Exception](repositories.bankStatements.find(created.id, user.id).futureValue)
      ex.getMessage must include("AiSuggestions")
    }
  }

  "findInternal" should {
    "return the statement with its owner userId" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createBankStatement(user.id).futureValue

      val found = repositories.bankStatements.findInternal(created.id).futureValue.value
      found.id must be(created.id)
      found.userId must be(user.id)
    }

    "return None for an unknown id" in withRepositories() { implicit repositories =>
      repositories.bankStatements.findInternal(UUID.randomUUID()).futureValue must be(None)
    }
  }

  "categorizedRowExamples" should {
    // createExpense generates the expense id, so the split (which needs it) is built here.
    def createCategorizedExpense(userId: UUID, categoryId: UUID)(implicit
        repositories: RepositoryComponents
    ): UUID = {
      val now = Instant.now()
      val expense = Expense.Create(
        id = UUID.randomUUID(),
        userId = userId,
        amount = BigDecimal("50.00"),
        date = LocalDate.of(2024, 1, 15),
        time = None,
        merchantId = None,
        notes = None,
        createdAt = now,
        updatedAt = now
      )
      val split = ExpenseCategorySplit.Create(UUID.randomUUID(), userId, expense.id, categoryId, expense.amount)
      repositories.expenses.create(expense, List(split)).futureValue
      expense.id
    }

    // Creates a statement with one row per description (day i+1 of January) and returns the rows.
    def createRows(userId: UUID, descriptions: List[String])(implicit
        repositories: RepositoryComponents
    ): List[BankStatementRow.Create] = {
      val doc = createDocument(userId, kind = DocumentKind.BankStatementPdf).futureValue
      val now = Instant.now()
      val stmt = BankStatement.Create(UUID.randomUUID(), doc.id, userId, now, now)
      val rows = descriptions.zipWithIndex.map { case (description, i) =>
        buildRow(stmt.id, i).copy(description = description, date = LocalDate.of(2024, 1, i + 1))
      }
      createStatement(stmt, sampleStatementMetadata, rows).futureValue
      rows
    }

    def link(row: BankStatementRow.Create, userId: UUID, categoryId: UUID)(implicit
        repositories: RepositoryComponents
    ): Unit = {
      val expenseId = createCategorizedExpense(userId, categoryId)
      repositories.bankStatements.setRowExpenseIdForUser(row.id, userId, expenseId, Instant.now()).futureValue
    }

    "keep the most recent rows per category, up to the limit" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val groceries = createCategory(user.id, "Groceries").futureValue
      val transport = createCategory(user.id, "Transport").futureValue
      val rows = createRows(user.id, List("G1", "G2", "G3", "G4", "T1"))
      rows.take(4).foreach(link(_, user.id, groceries.id))
      link(rows(4), user.id, transport.id)

      val result = repositories.bankStatements.categorizedRowExamples(user.id, perCategory = 3).futureValue
      result.filter(_._1 == groceries.id).map(_._2) must be(List("G4", "G3", "G2"))
      result.filter(_._1 == transport.id).map(_._2) must be(List("T1"))
      result.length must be(4)
    }

    "exclude rows without an expense" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id).futureValue
      val rows = createRows(user.id, List("Linked", "Unlinked"))
      link(rows.head, user.id, category.id)

      val result = repositories.bankStatements.categorizedRowExamples(user.id, perCategory = 3).futureValue
      result must be(List(category.id -> "Linked"))
    }

    "never include another user's rows" in withRepositories() { implicit repositories =>
      val userA = createUser(email = net.wiringbits.common.models.Email.trusted("a@test.com")).futureValue
      val userB = createUser(email = net.wiringbits.common.models.Email.trusted("b@test.com")).futureValue
      val categoryA = createCategory(userA.id).futureValue
      val categoryB = createCategory(userB.id).futureValue
      link(createRows(userA.id, List("Mine")).head, userA.id, categoryA.id)
      link(createRows(userB.id, List("Theirs")).head, userB.id, categoryB.id)

      val result = repositories.bankStatements.categorizedRowExamples(userA.id, perCategory = 3).futureValue
      result must be(List(categoryA.id -> "Mine"))
    }
  }

  "findByDocumentId" should {
    "return the statement for the document" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createBankStatement(user.id).futureValue

      val found = repositories.bankStatements.findByDocumentId(created.documentId, user.id).futureValue
      found.value.id must be(created.id)
    }

    "return None when no statement exists for the document" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      repositories.bankStatements.findByDocumentId(UUID.randomUUID(), user.id).futureValue must be(None)
    }
  }

  "createRows" should {
    "add rows to an existing statement" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createBankStatement(user.id).futureValue

      repositories.bankStatements.createRows(List(buildRow(created.id))).futureValue

      val rows = repositories.bankStatements.findRows(created.id).futureValue
      rows.length must be(1)
    }

    "round-trip txn_type" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createBankStatement(user.id).futureValue

      repositories.bankStatements
        .createRows(List(buildRow(created.id).copy(txnType = "payment")))
        .futureValue

      repositories.bankStatements.findRows(created.id).futureValue.head.txnType must be("payment")
    }
  }

  "allRows" should {
    "return rows for all of the user's statements" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc = createDocument(user.id, kind = DocumentKind.BankStatementPdf).futureValue
      val now = Instant.now()
      val stmt = BankStatement.Create(UUID.randomUUID(), doc.id, user.id, now, now)
      createStatement(stmt, sampleStatementMetadata, List(buildRow(stmt.id, 0), buildRow(stmt.id, 1))).futureValue

      val rows = repositories.bankStatements.allRows(user.id).futureValue
      rows.length must be(2)
    }
  }

  "findRows / findRow" should {
    "return the rows for a statement" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc = createDocument(user.id, kind = DocumentKind.BankStatementPdf).futureValue
      val now = Instant.now()
      val stmt = BankStatement.Create(UUID.randomUUID(), doc.id, user.id, now, now)
      val row = buildRow(stmt.id)
      createStatement(stmt, sampleStatementMetadata, List(row)).futureValue

      val rows = repositories.bankStatements.findRows(stmt.id).futureValue
      rows.length must be(1)

      val found = repositories.bankStatements.findRow(row.id, stmt.id).futureValue
      found.value.id must be(row.id)
    }
  }

  "setRowExpenseId / clearRowExpenseId" should {
    "link and unlink an expense to a row" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val expense = createExpense(user.id).futureValue
      val doc = createDocument(user.id, kind = DocumentKind.BankStatementPdf).futureValue
      val now = Instant.now()
      val stmt = BankStatement.Create(UUID.randomUUID(), doc.id, user.id, now, now)
      val row = buildRow(stmt.id)
      createStatement(stmt, sampleStatementMetadata, List(row)).futureValue

      repositories.bankStatements.setRowExpenseId(stmt.id, row.id, user.id, expense.id, Instant.now()).futureValue
      val linked = repositories.bankStatements.findRow(row.id, stmt.id).futureValue
      linked.value.expenseId must be(Some(expense.id))

      repositories.bankStatements.clearRowExpenseId(stmt.id, row.id, user.id, Instant.now()).futureValue
      val cleared = repositories.bankStatements.findRow(row.id, stmt.id).futureValue
      cleared.value.expenseId must be(None)
    }

    "fail when the statement does not belong to the user" in withRepositories() { implicit repositories =>
      val userA = createUser(email = net.wiringbits.common.models.Email.trusted("a@test.com")).futureValue
      val userB = createUser(email = net.wiringbits.common.models.Email.trusted("b@test.com")).futureValue
      val expense = createExpense(userB.id).futureValue
      val created = createBankStatement(userA.id).futureValue

      val ex = intercept[RuntimeException] {
        repositories.bankStatements
          .setRowExpenseId(created.id, UUID.randomUUID(), userB.id, expense.id, Instant.now())
          .futureValue
      }
      ex.getMessage must include("Bank statement not found")
    }
  }

  "ignoreRow / unignoreRow" should {
    "set and clear ignoredAt" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc = createDocument(user.id, kind = DocumentKind.BankStatementPdf).futureValue
      val now = Instant.now()
      val stmt = BankStatement.Create(UUID.randomUUID(), doc.id, user.id, now, now)
      val row = buildRow(stmt.id)
      createStatement(stmt, sampleStatementMetadata, List(row)).futureValue

      repositories.bankStatements.ignoreRow(stmt.id, row.id, user.id, Instant.now(), Instant.now()).futureValue
      val ignored = repositories.bankStatements.findRow(row.id, stmt.id).futureValue
      ignored.value.ignoredAt must be(defined)

      repositories.bankStatements.unignoreRow(stmt.id, row.id, user.id, Instant.now()).futureValue
      val unignored = repositories.bankStatements.findRow(row.id, stmt.id).futureValue
      unignored.value.ignoredAt must be(None)
    }
  }
}
