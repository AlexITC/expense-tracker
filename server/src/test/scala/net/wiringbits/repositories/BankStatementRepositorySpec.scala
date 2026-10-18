package net.wiringbits.repositories

import anorm.*
import net.wiringbits.api.models.bankstatements.ExtractionInfo
import net.wiringbits.api.models.bankstatementtemplates.ExtractionSource
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.models.documents.{DocumentKind, DocumentStatus}
import net.wiringbits.repositories.models.{BankStatement, BankStatementRow, Document}
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
