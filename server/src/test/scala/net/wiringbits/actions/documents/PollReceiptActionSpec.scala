package net.wiringbits.actions.documents

import net.wiringbits.apis.TextractClient
import net.wiringbits.models.documents.{DocumentKind, DocumentStatus}
import net.wiringbits.repositories.{
  BackgroundJobsRepository,
  DocumentsRepository,
  ReceiptRepository,
  TextractCacheRepository,
  TextractJobRepository
}
import net.wiringbits.repositories.models.Document
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar

import java.time.{Clock, Instant}
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class PollReceiptActionSpec extends AnyWordSpec with Matchers with ScalaFutures with MockitoSugar {

  private def stubDocument(docId: UUID, kind: DocumentKind): Document =
    Document(
      docId,
      UUID.randomUUID(),
      "deadbeef",
      "key",
      kind,
      "file.pdf",
      DocumentStatus.Ingested,
      None,
      Instant.now(),
      Instant.now()
    )

  "apply" should {
    "fail when the document has no receipt" in {
      val docId = UUID.randomUUID()
      val docRepo = mock[DocumentsRepository]
      val receiptRepo = mock[ReceiptRepository]
      when(docRepo.findById(docId)).thenReturn(Future.successful(Some(stubDocument(docId, DocumentKind.Receipt))))
      when(receiptRepo.findByDocumentId(docId)).thenReturn(Future.successful(None))

      val error = new PollReceiptAction(
        docRepo,
        receiptRepo,
        mock[TextractCacheRepository],
        mock[TextractJobRepository],
        mock[BackgroundJobsRepository],
        mock[TextractClient]
      )(using global, Clock.systemUTC())(docId).failed.futureValue

      error.getMessage must be(s"Receipt not found for document $docId")
      verify(receiptRepo, never()).updateStatus(any(), any(), any())
    }
  }
}
