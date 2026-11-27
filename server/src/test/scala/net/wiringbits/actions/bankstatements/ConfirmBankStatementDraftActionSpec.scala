package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.BankStatementDraft
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers
import play.api.libs.json.Json
import utils.RepositoryUtils

import java.time.{Clock, Instant, LocalDate}
import java.util.UUID

class ConfirmBankStatementDraftActionSpec extends RepositorySpec with RepositoryUtils with Matchers {

  private given Clock = Clock.systemUTC()

  private def action(using repositories: RepositoryComponents) =
    new ConfirmBankStatementDraftAction(
      repositories.bankStatementDrafts,
      repositories.bankStatements,
      repositories.documents
    )(using executionContext, summon[Clock])

  private val metadata = BankStatementDraft.Metadata(
    bankName = Some("Test Bank"),
    productType = Some("debit"),
    currency = Some("EUR"),
    accountLast4 = Some("0000"),
    periodStart = Some(LocalDate.of(2026, 1, 1)),
    periodEnd = Some(LocalDate.of(2026, 1, 31)),
    openingBalance = Some(BigDecimal("100.00")),
    closingBalance = Some(BigDecimal("100.00"))
  )

  "apply" should {
    "reject a draft with no transactions and keep the draft" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val draft = createBankStatementDraft(user.id, sha256 = UUID.randomUUID().toString).futureValue
      val payload = BankStatementDraft.Payload(
        metadata = metadata,
        transactions = Nil,
        reconciled = true,
        discrepancy = None,
        model = "test-model",
        stage = None,
        unverifiable = false
      )
      repositories.bankStatementDrafts.markReady(draft.id, Json.toJson(payload).toString, Instant.now()).futureValue

      val error = action(draft.id, user.id, acceptWarnings = true, acceptUnverified = true).failed.futureValue
      error.getMessage must include("no transactions")

      repositories.bankStatementDrafts.findByIdForUser(draft.id, user.id).futureValue.isDefined must be(true)
    }

    "reject a debit draft whose rows have the opposite sign and keep the draft" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val draft = createBankStatementDraft(user.id, sha256 = UUID.randomUUID().toString).futureValue
        val payload = BankStatementDraft.Payload(
          metadata = metadata.copy(productType = Some(" debit "), closingBalance = Some(BigDecimal("60.00"))),
          transactions = List(
            BankStatementDraft.Transaction(
              LocalDate.of(2026, 1, 5),
              BigDecimal("40.00"),
              "TEST MERCHANT",
              Some("purchase"),
              Some(BigDecimal("60.00"))
            )
          ),
          reconciled = true,
          discrepancy = None,
          model = "test-model",
          stage = None,
          unverifiable = false
        )
        repositories.bankStatementDrafts.markReady(draft.id, Json.toJson(payload).toString, Instant.now()).futureValue

        val error = action(draft.id, user.id, acceptWarnings = true, acceptUnverified = true).failed.futureValue
        error.getMessage must include("opposite sign")

        repositories.bankStatementDrafts.findByIdForUser(draft.id, user.id).futureValue.isDefined must be(true)
    }
  }
}
