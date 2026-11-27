package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.BankStatementDraft
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers
import play.api.libs.json.Json
import utils.RepositoryUtils

import java.time.{Clock, Instant, LocalDate}
import java.util.UUID

class ConfirmBankStatementDraftActionSpec extends RepositorySpec with RepositoryUtils with Matchers {

  private val metadata = BankStatementDraft.Metadata(
    bankName = Some("Test Bank"),
    productType = Some("debit"),
    currency = Some("EUR"),
    accountLast4 = Some("0000"),
    periodStart = Some(LocalDate.of(2026, 1, 1)),
    periodEnd = Some(LocalDate.of(2026, 1, 31)),
    openingBalance = Some(BigDecimal("100.00")),
    closingBalance = Some(BigDecimal("60.00"))
  )

  private def txn(amount: String, runningBalance: String, txnType: Option[String] = Some("purchase")) =
    BankStatementDraft.Transaction(
      LocalDate.of(2026, 1, 5),
      BigDecimal(amount),
      "TEST MERCHANT",
      txnType,
      Some(BigDecimal(runningBalance))
    )

  private def payload(
      metadata: BankStatementDraft.Metadata = metadata,
      transactions: List[BankStatementDraft.Transaction] = List(txn("-40.00", "60.00"))
  ) =
    BankStatementDraft.Payload(
      metadata = metadata,
      transactions = transactions,
      reconciled = true,
      discrepancy = None,
      model = "test",
      stage = None,
      unverifiable = false
    )

  // A ready draft owned by a fresh user, seeded directly in the DB with `initial`.
  private def readyDraft(initial: BankStatementDraft.Payload)(using repositories: RepositoryComponents) = {
    val user = createUser().futureValue
    val draft = createBankStatementDraft(
      user.id,
      sha256 = UUID.randomUUID().toString,
      filename = s"${UUID.randomUUID()}.pdf"
    ).futureValue
    repositories.bankStatementDrafts.markReady(draft.id, Json.toJson(initial).toString, Instant.now()).futureValue
    (user.id, draft.id)
  }

  private def confirm(userId: UUID, draftId: UUID, acceptUnverified: Boolean = false)(using
      repositories: RepositoryComponents
  ) =
    new ConfirmBankStatementDraftAction(
      repositories.bankStatementDrafts,
      repositories.bankStatements,
      repositories.documents
    )(using executionContext, Clock.systemUTC())(
      draftId,
      userId,
      acceptWarnings = true,
      acceptUnverified = acceptUnverified
    )

  "apply" should {
    "reject a draft with no transactions and keep the draft" in withRepositories() { implicit repositories =>
      val (userId, draftId) = readyDraft(
        payload(metadata = metadata.copy(closingBalance = Some(BigDecimal("100.00"))), transactions = Nil)
      )

      val error = confirm(userId, draftId, acceptUnverified = true).failed.futureValue

      error.getMessage must include("no transactions")
      repositories.bankStatementDrafts.findById(draftId).futureValue must not be empty
    }

    "reject a debit draft whose rows have the opposite sign and keep the draft" in withRepositories() {
      implicit repositories =>
        val (userId, draftId) = readyDraft(
          payload(metadata = metadata.copy(productType = Some(" debit ")), transactions = List(txn("40.00", "60.00")))
        )

        val error = confirm(userId, draftId, acceptUnverified = true).failed.futureValue

        error.getMessage must include("opposite sign")
        repositories.bankStatementDrafts.findById(draftId).futureValue must not be empty
    }

    "create the statement from a valid reconciling draft" in withRepositories() { implicit repositories =>
      val (userId, draftId) = readyDraft(payload())

      val statementId = confirm(userId, draftId).futureValue.bankStatementId.value

      val rows = repositories.bankStatements.findRows(statementId).futureValue
      rows.map(r => (r.amount, r.description, r.txnType)) must be(
        List((BigDecimal("-40.00"), "TEST MERCHANT", "purchase"))
      )
      repositories.bankStatementDrafts.findById(draftId).futureValue must be(None)
    }

    "reject a reconciling draft with a row that has no type and keep the draft" in withRepositories() {
      implicit repositories =>
        val (userId, draftId) = readyDraft(payload(transactions = List(txn("-40.00", "60.00", txnType = None))))

        val error = confirm(userId, draftId).failed.futureValue

        error.getMessage must be("Row 1: type is missing")
        repositories.bankStatementDrafts.findById(draftId).futureValue must not be empty
    }

    "reject a draft whose currency is not an ISO 4217 code" in withRepositories() { implicit repositories =>
      val (userId, draftId) = readyDraft(payload(metadata = metadata.copy(currency = Some("ZZZ"))))

      val error = confirm(userId, draftId).failed.futureValue

      error.getMessage must be("Currency ZZZ is not an ISO 4217 code")
      repositories.bankStatementDrafts.findById(draftId).futureValue must not be empty
    }

    "reject a draft with a row amount that has more than 2 decimal places" in withRepositories() {
      implicit repositories =>
        val (userId, draftId) = readyDraft(payload(transactions = List(txn("-40.005", "60.00"))))

        val error = confirm(userId, draftId).failed.futureValue

        error.getMessage must include("Row 1: amount")
        error.getMessage must include("decimal places")
        repositories.bankStatementDrafts.findById(draftId).futureValue must not be empty
    }

    "reject a draft with a closing balance that has more than 2 decimal places" in withRepositories() {
      implicit repositories =>
        val (userId, draftId) =
          readyDraft(payload(metadata = metadata.copy(closingBalance = Some(BigDecimal("60.005")))))

        val error = confirm(userId, draftId).failed.futureValue

        error.getMessage must include("Closing balance")
        error.getMessage must include("decimal places")
        repositories.bankStatementDrafts.findById(draftId).futureValue must not be empty
    }

    "report a blank currency as missing metadata" in withRepositories() { implicit repositories =>
      val (userId, draftId) = readyDraft(payload(metadata = metadata.copy(currency = Some("   "))))

      val error = confirm(userId, draftId).failed.futureValue

      error.getMessage must be("Missing required metadata: currency")
      repositories.bankStatementDrafts.findById(draftId).futureValue must not be empty
    }
  }
}
