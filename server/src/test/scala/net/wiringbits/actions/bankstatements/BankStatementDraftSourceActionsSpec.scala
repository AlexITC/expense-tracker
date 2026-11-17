package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.{BankStatementDraft, BankStatementDraftSource}
import net.wiringbits.core.RepositorySpec
import net.wiringbits.models.documents.DocumentKind
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import play.api.libs.json.Json
import utils.RepositoryUtils

import java.time.{Clock, Instant, LocalDate}
import java.util.UUID

class BankStatementDraftSourceActionsSpec extends RepositorySpec with RepositoryUtils {

  private val payload = BankStatementDraft.Payload(
    metadata = BankStatementDraft.Metadata(None, None, None, None, None, None, None, None),
    transactions = List(
      BankStatementDraft.Transaction(LocalDate.of(2026, 1, 5), BigDecimal("-12.34"), "TEST MERCHANT", None, None)
    ),
    reconciled = false,
    discrepancy = None,
    model = "test",
    stage = None,
    unverifiable = true
  )

  "apply" should {
    "report a draft linked to a CSV document as Csv in list, get and update" in withRepositories() {
      implicit repositories =>
        given Clock = Clock.systemUTC()
        val user = createUser().futureValue
        val draft = createBankStatementDraft(
          user.id,
          sha256 = UUID.randomUUID().toString,
          filename = s"${UUID.randomUUID()}.csv",
          kind = DocumentKind.BankStatementCsv
        ).futureValue
        repositories.bankStatementDrafts
          .markReady(draft.id, Json.toJson(payload).toString, Instant.now())
          .futureValue

        val listed = new ListBankStatementDraftsAction(repositories.bankStatementDrafts)(using executionContext)(
          user.id
        ).futureValue.drafts
        listed.find(_.id == draft.id).value.source must be(BankStatementDraftSource.Csv)

        new GetBankStatementDraftAction(repositories.bankStatementDrafts)(using executionContext)(
          draft.id,
          user.id
        ).futureValue.source must be(BankStatementDraftSource.Csv)

        new UpdateBankStatementDraftAction(repositories.bankStatementDrafts)(using executionContext, summon[Clock])(
          draft.id,
          user.id,
          BankStatementDraft.Update.Request(payload)
        ).futureValue.source must be(BankStatementDraftSource.Csv)
    }
  }
}
