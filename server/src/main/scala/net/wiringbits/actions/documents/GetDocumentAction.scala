package net.wiringbits.actions.documents

import net.wiringbits.api.models.documents.GetDocument
import net.wiringbits.models.documents.DocumentKind
import net.wiringbits.repositories.{BankStatementRepository, DocumentsRepository}
import net.wiringbits.repositories.models.BankStatementRow

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetDocumentAction @Inject() (
    documentsRepository: DocumentsRepository,
    bankStatementRepository: BankStatementRepository
)(implicit ec: ExecutionContext) {

  def apply(id: UUID, userId: UUID): Future[GetDocument.Response] =
    for {
      docOpt <- documentsRepository.find(id, userId)
      doc = docOpt.getOrElse(throw new RuntimeException("Document not found"))
      statementRows <- resolveStatementRows(doc, userId)
    } yield GetDocument.Response(
      id = doc.id,
      sha256 = doc.sha256,
      kind = doc.kind.entryName,
      filename = doc.filename,
      statementRows = statementRows,
      createdAt = doc.createdAt
    )

  private def resolveStatementRows(
      doc: net.wiringbits.repositories.models.Document,
      userId: UUID
  ): Future[Option[List[GetDocument.StatementRowData]]] =
    if (doc.kind != DocumentKind.BankStatementPdf) Future.successful(None)
    else
      bankStatementRepository.findByDocumentId(doc.id, userId).flatMap {
        case None => Future.successful(Some(Nil))
        case Some(stmt) =>
          bankStatementRepository.findRows(stmt.id).map { rows =>
            Some(rows.map(rowToData))
          }
      }

  private def rowToData(r: BankStatementRow): GetDocument.StatementRowData =
    GetDocument.StatementRowData(r.id, r.index, r.date, r.amount, r.description, r.manuallyEdited, r.confirmedAt)
}
