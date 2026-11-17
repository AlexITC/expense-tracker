package net.wiringbits.actions.documents

import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.models.documents.DocumentKind
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.LocalDate
import java.util.UUID

class GetDocumentActionSpec extends RepositorySpec with RepositoryUtils {

  private def newAction(using repositories: RepositoryComponents): GetDocumentAction =
    new GetDocumentAction(repositories.documents, repositories.bankStatements)(using executionContext)

  "apply" should {
    "return the statement rows of a CSV statement document" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val (stmt, rows) = createBankStatementWithRows(
        user.id,
        List(LocalDate.of(2026, 1, 5) -> BigDecimal("-12.34"), LocalDate.of(2026, 1, 6) -> BigDecimal("50.00")),
        kind = DocumentKind.BankStatementCsv
      ).futureValue

      val response = newAction(stmt.documentId, user.id).futureValue

      response.kind must be(DocumentKind.BankStatementCsv.entryName)
      response.statementRows.value.map(_.id) must be(rows.map(_.id))
    }

    "return no statement rows for a receipt document" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc = createDocument(user.id, sha256 = UUID.randomUUID().toString, kind = DocumentKind.Receipt).futureValue

      newAction(doc.id, user.id).futureValue.statementRows must be(None)
    }
  }
}
