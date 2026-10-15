package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.DeleteBankStatement
import net.wiringbits.repositories.{BankStatementRepository, DocumentsRepository}
import net.wiringbits.services.BlobStorage

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class DeleteBankStatementAction @Inject() (
    bankStatementRepository: BankStatementRepository,
    documentsRepository: DocumentsRepository,
    blobStorage: BlobStorage
)(implicit ec: ExecutionContext) {

  def apply(bankStatementId: UUID, userId: UUID): Future[DeleteBankStatement.Response] =
    for {
      viewOpt <- bankStatementRepository.find(bankStatementId, userId)
      view = viewOpt.getOrElse(throw new RuntimeException("Bank statement not found"))
      docOpt <- documentsRepository.find(view.documentId, userId)
      doc = docOpt.getOrElse(throw new RuntimeException("Document not found"))
      _ <- documentsRepository.delete(doc.id, userId)
      _ <- blobStorage.delete(doc.storageKey)
    } yield DeleteBankStatement.Response(bankStatementId)
}
