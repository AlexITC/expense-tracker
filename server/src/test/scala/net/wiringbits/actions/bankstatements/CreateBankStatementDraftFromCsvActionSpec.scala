package net.wiringbits.actions.bankstatements

import anorm.*
import net.wiringbits.api.models.bankstatements.{BankStatementDraft, BankStatementDraftSource}
import net.wiringbits.api.utils.BankStatementDraftLimits
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.models.documents.DocumentKind
import net.wiringbits.models.jobs.BackgroundJobType
import net.wiringbits.services.{BankStatementDraftUploads, BlobStorage, StatementCsvParser}
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers
import play.api.libs.json.Json
import utils.RepositoryUtils

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.{Clock, LocalDate}
import java.util.{HexFormat, UUID}
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

class CreateBankStatementDraftFromCsvActionSpec extends RepositorySpec with RepositoryUtils with Matchers {

  private given Clock = Clock.systemUTC()

  private class InMemoryBlobStorage extends BlobStorage {
    val blobs: TrieMap[String, Array[Byte]] = TrieMap.empty
    override def put(storageKey: String, bytes: Array[Byte]): Future[Unit] =
      Future.successful(blobs.update(storageKey, bytes))
    override def exists(storageKey: String): Future[Boolean] = Future.successful(blobs.contains(storageKey))
    override def get(storageKey: String): Future[Option[Array[Byte]]] = Future.successful(blobs.get(storageKey))
    override def delete(storageKey: String): Future[Unit] = Future.successful(blobs.remove(storageKey)).map(_ => ())
  }

  // The random marker keeps every file's sha256 unique across parallel specs.
  private def csvBytes(): Array[Byte] = {
    val marker = UUID.randomUUID()
    s"""date,description,amount,txn_type
       |2026-01-05,TEST MERCHANT $marker,-12.34,purchase
       |2026-01-07,TEST REFUND $marker,5.00,refund
       |2026-01-09,TEST FEE $marker,-1.50,
       |""".stripMargin.getBytes(StandardCharsets.UTF_8)
  }

  private def sha256(bytes: Array[Byte]): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

  private def filename(): String = s"${UUID.randomUUID()}.csv"

  private def uploads(blobs: BlobStorage)(using repositories: RepositoryComponents) =
    new BankStatementDraftUploads(
      repositories.bankStatementDrafts,
      repositories.documents,
      repositories.bankStatements,
      blobs
    )(using executionContext, summon[Clock])

  private def action(blobs: BlobStorage)(using repositories: RepositoryComponents) =
    new CreateBankStatementDraftFromCsvAction(
      repositories.bankStatementDrafts,
      uploads(blobs),
      new StatementCsvParser.FixedSchema
    )(using executionContext)

  private def pdfAction(blobs: BlobStorage)(using repositories: RepositoryComponents) =
    new CreateBankStatementDraftAction(repositories.bankStatementDrafts, uploads(blobs))(using executionContext)

  private def jobsReferencing(id: UUID)(using repositories: RepositoryComponents): List[String] =
    repositories.database.withConnection { implicit conn =>
      SQL"SELECT type FROM background_jobs WHERE payload::TEXT LIKE ${s"%$id%"}"
        .as(SqlParser.str("type").*)
    }

  private val metadata = BankStatementDraft.Metadata(
    bankName = Some("Test Bank"),
    productType = Some("debit"),
    currency = Some("USD"),
    accountLast4 = Some("0000"),
    periodStart = Some(LocalDate.of(2026, 1, 1)),
    periodEnd = Some(LocalDate.of(2026, 1, 31)),
    openingBalance = None,
    closingBalance = None
  )

  private def confirm(draftId: UUID, userId: UUID)(using repositories: RepositoryComponents) = {
    val stored = repositories.bankStatementDrafts.findByIdForUser(draftId, userId).futureValue.value
    val payload = Json.parse(stored.payload.value).as[BankStatementDraft.Payload]
    new UpdateBankStatementDraftAction(repositories.bankStatementDrafts)(using executionContext, summon[Clock])(
      draftId,
      userId,
      BankStatementDraft.Update.Request(payload.copy(metadata = metadata))
    ).futureValue
    new ConfirmBankStatementDraftAction(
      repositories.bankStatementDrafts,
      repositories.bankStatements,
      repositories.documents
    )(using executionContext, summon[Clock])(
      draftId,
      userId,
      acceptWarnings = true,
      acceptUnverified = true
    ).futureValue
  }

  "apply" should {
    "create a ready draft with the parsed rows, empty metadata and a Csv source" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val name = filename()
        val result = action(new InMemoryBlobStorage)(user.id, csvBytes(), name).futureValue

        result.status must be("ready")
        result.source must be(BankStatementDraftSource.Csv)
        result.filename must be(name)
        val payload = result.payload.value
        payload.model must be("csv")
        payload.stage must be(None)
        payload.metadata must be(BankStatementDraft.Metadata(None, None, None, None, None, None, None, None))
        payload.transactions.map(t => (t.date, t.amount, t.txnType)) must be(
          List(
            (LocalDate.of(2026, 1, 5), BigDecimal("-12.34"), Some("purchase")),
            (LocalDate.of(2026, 1, 7), BigDecimal("5.00"), Some("refund")),
            (LocalDate.of(2026, 1, 9), BigDecimal("-1.50"), None)
          )
        )
        payload.reconciled must be(false)
        payload.unverifiable must be(true)

        val stored = repositories.bankStatementDrafts.findByIdForUser(result.id, user.id).futureValue.value
        stored.status must be("ready")
        stored.documentKind must be(DocumentKind.BankStatementCsv)
        Json.parse(stored.payload.value).as[BankStatementDraft.Payload] must be(payload)
    }

    "not enqueue any background job for the draft" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val result = action(new InMemoryBlobStorage)(user.id, csvBytes(), filename()).futureValue
      jobsReferencing(result.id) must be(Nil)
    }

    "store the file and link a BankStatementCsv document" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val blobs = new InMemoryBlobStorage
      val bytes = csvBytes()
      val result = action(blobs)(user.id, bytes, filename()).futureValue

      val stored = repositories.bankStatementDrafts.findByIdForUser(result.id, user.id).futureValue.value
      val document = repositories.documents.find(stored.documentId, user.id).futureValue.value
      document.kind must be(DocumentKind.BankStatementCsv)
      document.sha256 must be(sha256(bytes))
      stored.storageKey must be(s"data/${user.id}/${LocalDate.now(summon[Clock])}/${sha256(bytes)}")
      blobs.blobs.get(stored.storageKey).value must be(bytes)
    }

    "reject the same file while it is already in the queue" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val bytes = csvBytes()
      val blobs = new InMemoryBlobStorage
      action(blobs)(user.id, bytes, filename()).futureValue

      val error = action(blobs)(user.id, bytes, filename()).failed.futureValue
      error.getMessage must be("This file is already in your import queue")
    }

    "reject the same file after its statement was confirmed" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val bytes = csvBytes()
      val blobs = new InMemoryBlobStorage
      val draft = action(blobs)(user.id, bytes, filename()).futureValue
      confirm(draft.id, user.id).bankStatementId must be(defined)

      val error = action(blobs)(user.id, bytes, filename()).failed.futureValue
      error.getMessage must be("This statement was already imported")
    }

    "reuse the document when the file is uploaded again after a discard" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val bytes = csvBytes()
        val blobs = new InMemoryBlobStorage
        val first = action(blobs)(user.id, bytes, filename()).futureValue
        val documentId =
          repositories.bankStatementDrafts.findByIdForUser(first.id, user.id).futureValue.value.documentId
        new DiscardBankStatementDraftAction(repositories.bankStatementDrafts)(using executionContext)(
          first.id,
          user.id
        ).futureValue.discarded must be(true)

        val second = action(blobs)(user.id, bytes, filename()).futureValue
        repositories.bankStatementDrafts
          .findByIdForUser(second.id, user.id)
          .futureValue
          .value
          .documentId must be(documentId)
        repositories.documents.all(user.id).futureValue.count(_.sha256 == sha256(bytes)) must be(1)
    }

    "reject a file already stored as a BankStatementPdf document" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val bytes = csvBytes()
      createDocument(user.id, sha256 = sha256(bytes), kind = DocumentKind.BankStatementPdf).futureValue

      val blobs = new InMemoryBlobStorage
      val error = action(blobs)(user.id, bytes, filename()).failed.futureValue
      error.getMessage must be(
        s"This file was already uploaded as a ${DocumentKind.BankStatementPdf.entryName} document"
      )
      blobs.blobs must be(empty)
      repositories.bankStatementDrafts.listByUser(user.id).futureValue must be(Nil)
    }

    "reject a file one byte over MaxCsvBytes without storing anything" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val blobs = new InMemoryBlobStorage
      val base = csvBytes()
      val bytes = base ++ Array.fill(BankStatementDraftLimits.MaxCsvBytes - base.length + 1)('\n'.toByte)

      val error = action(blobs)(user.id, bytes, filename()).failed.futureValue
      error.getMessage must be(s"The CSV file is larger than ${BankStatementDraftLimits.MaxCsvBytes} bytes")
      blobs.blobs must be(empty)
      repositories.documents.all(user.id).futureValue must be(Nil)
      repositories.bankStatementDrafts.listByUser(user.id).futureValue must be(Nil)
    }

    "accept a file of exactly MaxCsvBytes" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val base = csvBytes()
      val bytes = base ++ Array.fill(BankStatementDraftLimits.MaxCsvBytes - base.length)('\n'.toByte)
      bytes.length must be(BankStatementDraftLimits.MaxCsvBytes)

      val result = action(new InMemoryBlobStorage)(user.id, bytes, filename()).futureValue
      result.payload.value.transactions.size must be(3)
    }

    "reject an invalid CSV without storing anything" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val blobs = new InMemoryBlobStorage
      val bytes = s"date,description,amount\nnot-a-date,TEST MERCHANT ${UUID.randomUUID()},-12.34\n"
        .getBytes(StandardCharsets.UTF_8)

      val error = action(blobs)(user.id, bytes, filename()).failed.futureValue
      error.getMessage must be("Row 2, column date: \"not-a-date\" is not a date in YYYY-MM-DD format")
      blobs.blobs must be(empty)
      repositories.documents.all(user.id).futureValue must be(Nil)
      repositories.bankStatementDrafts.listByUser(user.id).futureValue must be(Nil)
    }

    "let another user upload the same bytes without leaking drafts" in withRepositories() { implicit repositories =>
      val alice = createUser().futureValue
      val bob = createUser().futureValue
      val bytes = csvBytes()
      val blobs = new InMemoryBlobStorage
      val aliceDraft = action(blobs)(alice.id, bytes, filename()).futureValue
      val bobDraft = action(blobs)(bob.id, bytes, filename()).futureValue

      repositories.bankStatementDrafts.listByUser(alice.id).futureValue.map(_.id) must be(List(aliceDraft.id))
      repositories.bankStatementDrafts.listByUser(bob.id).futureValue.map(_.id) must be(List(bobDraft.id))
      repositories.bankStatementDrafts.findByIdForUser(aliceDraft.id, bob.id).futureValue must be(None)
      repositories.bankStatementDrafts.findByIdForUser(bobDraft.id, alice.id).futureValue must be(None)
    }

    "confirm into a statement whose rows match the CSV and enqueue the suggestions job" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val draft = action(new InMemoryBlobStorage)(user.id, csvBytes(), filename()).futureValue

        val statementId = confirm(draft.id, user.id).bankStatementId.value

        val rows = repositories.bankStatements.findRows(statementId).futureValue.sortBy(_.index)
        rows.map(r => (r.index, r.date, r.amount, r.description, r.txnType)) must be(
          draft.payload.value.transactions.zipWithIndex.map { case (t, i) =>
            (i, t.date, t.amount, t.description, t.txnType.getOrElse("purchase"))
          }
        )
        jobsReferencing(statementId) must be(List(BackgroundJobType.ResolveStatementSuggestions.entryName))
        repositories.bankStatementDrafts.findByIdForUser(draft.id, user.id).futureValue must be(None)
    }
  }

  "PDF apply" should {
    "still create a processing draft through the shared upload steps" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val bytes = s"%PDF-1.4 ${UUID.randomUUID()}".getBytes(StandardCharsets.UTF_8)
      val result = pdfAction(new InMemoryBlobStorage)(user.id, bytes, "statement.pdf").futureValue

      result.status must be("processing")
      result.source must be(BankStatementDraftSource.Pdf)
      jobsReferencing(result.id) must be(List(BackgroundJobType.ExtractBankStatementDraft.entryName))
    }
  }
}
