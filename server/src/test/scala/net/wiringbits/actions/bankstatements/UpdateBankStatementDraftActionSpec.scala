package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.BankStatementDraft
import net.wiringbits.api.utils.BankStatementDraftLimits
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers
import play.api.libs.json.Json
import utils.RepositoryUtils

import java.time.{Clock, Instant, LocalDate}
import java.util.UUID

class UpdateBankStatementDraftActionSpec extends RepositorySpec with RepositoryUtils with Matchers {

  private val emptyMetadata = BankStatementDraft.Metadata(None, None, None, None, None, None, None, None)

  private def txn(amount: String, runningBalance: Option[String]) =
    BankStatementDraft.Transaction(
      LocalDate.of(2026, 1, 5),
      BigDecimal(amount),
      s"TEST MERCHANT ${UUID.randomUUID()}",
      None,
      runningBalance.map(BigDecimal(_))
    )

  private def payload(metadata: BankStatementDraft.Metadata, transactions: List[BankStatementDraft.Transaction]) =
    BankStatementDraft.Payload(
      metadata = metadata,
      transactions = transactions,
      reconciled = false,
      discrepancy = None,
      model = "test",
      stage = None,
      unverifiable = false
    )

  private def rows(n: Int) = List.fill(n)(txn("-12.34", None))

  // A ready draft owned by a fresh user, seeded with `initial`.
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

  private def action(using repositories: RepositoryComponents) =
    new UpdateBankStatementDraftAction(repositories.bankStatementDrafts)(using executionContext, Clock.systemUTC())

  private def storedPayload(draftId: UUID)(using repositories: RepositoryComponents) =
    Json
      .parse(repositories.bankStatementDrafts.findById(draftId).futureValue.value.payload.value)
      .as[BankStatementDraft.Payload]

  "apply" should {
    "accept a payload with exactly the maximum number of rows" in withRepositories() { implicit repositories =>
      val (userId, draftId) = readyDraft(payload(emptyMetadata, rows(1)))
      val edited = payload(emptyMetadata, rows(BankStatementDraftLimits.MaxTransactions))

      action(draftId, userId, BankStatementDraft.Update.Request(edited)).futureValue

      storedPayload(draftId).transactions must be(edited.transactions)
    }

    "reject a payload over the maximum number of rows and leave the stored payload unchanged" in withRepositories() {
      implicit repositories =>
        val initial = payload(emptyMetadata, rows(1))
        val (userId, draftId) = readyDraft(initial)
        val edited = payload(emptyMetadata, rows(BankStatementDraftLimits.MaxTransactions + 1))

        val error = action(draftId, userId, BankStatementDraft.Update.Request(edited)).failed.futureValue

        error.getMessage must be("A draft can have at most 250 rows")
        storedPayload(draftId) must be(initial)
    }

    "accept edits to a draft already over the maximum without growing it" in withRepositories() {
      implicit repositories =>
        val oversized = rows(BankStatementDraftLimits.MaxTransactions + 5)
        val (userId, draftId) = readyDraft(payload(emptyMetadata, oversized))
        val metadata = emptyMetadata.copy(bankName = Some("Test Bank"))
        val edited = payload(metadata, oversized.dropRight(1))

        action(draftId, userId, BankStatementDraft.Update.Request(edited)).futureValue

        val stored = storedPayload(draftId)
        stored.metadata.bankName must be(Some("Test Bank"))
        stored.transactions must be(edited.transactions)
    }

    "reject growing a draft already over the maximum" in withRepositories() { implicit repositories =>
      val initial = payload(emptyMetadata, rows(BankStatementDraftLimits.MaxTransactions + 1))
      val (userId, draftId) = readyDraft(initial)
      val edited = payload(emptyMetadata, initial.transactions :+ txn("-1.00", None))

      val error = action(draftId, userId, BankStatementDraft.Update.Request(edited)).failed.futureValue

      error.getMessage must be("A draft can have at most 250 rows")
      storedPayload(draftId) must be(initial)
    }

    "not mark a payload with no rows as unverifiable" in withRepositories() { implicit repositories =>
      val (userId, draftId) = readyDraft(payload(emptyMetadata, rows(1)))
      val edited = payload(emptyMetadata, Nil).copy(unverifiable = true)

      val result = action(draftId, userId, BankStatementDraft.Update.Request(edited)).futureValue.payload.value

      result.unverifiable must be(false)
      result.reconciled must be(false)
      storedPayload(draftId) must be(result)
    }

    "mark a payload with no balances as unverifiable" in withRepositories() { implicit repositories =>
      val (userId, draftId) = readyDraft(payload(emptyMetadata, rows(1)))
      val edited = payload(emptyMetadata, List(txn("-12.34", None))).copy(reconciled = true)

      val result = action(draftId, userId, BankStatementDraft.Update.Request(edited)).futureValue.payload.value

      result.reconciled must be(false)
      result.unverifiable must be(true)
      result.discrepancy must not be empty
      storedPayload(draftId) must be(result)
    }

    "reconcile a payload whose running balances chain from the opening balance" in withRepositories() {
      implicit repositories =>
        val (userId, draftId) = readyDraft(payload(emptyMetadata, rows(1)))
        val metadata = emptyMetadata.copy(openingBalance = Some(BigDecimal("100.00")))
        val edited = payload(metadata, List(txn("-10.00", Some("90.00")), txn("-5.00", Some("85.00"))))
          .copy(unverifiable = true, discrepancy = Some("stale"))

        val result = action(draftId, userId, BankStatementDraft.Update.Request(edited)).futureValue.payload.value

        result.reconciled must be(true)
        result.unverifiable must be(false)
        result.discrepancy must be(None)
        storedPayload(draftId) must be(result)
    }
  }
}
