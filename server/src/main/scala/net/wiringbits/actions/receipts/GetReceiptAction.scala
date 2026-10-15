package net.wiringbits.actions.receipts

import net.wiringbits.api.models.documents.{DocumentStatus => ApiStatus}
import net.wiringbits.api.models.receipts.GetReceipt
import net.wiringbits.repositories.ReceiptRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetReceiptAction @Inject() (receiptRepository: ReceiptRepository)(implicit ec: ExecutionContext) {

  def apply(id: UUID, userId: UUID): Future[GetReceipt.Response] =
    receiptRepository.find(id, userId).map { receiptOpt =>
      val r = receiptOpt.getOrElse(throw new RuntimeException("Receipt not found"))
      GetReceipt.Response(
        id = r.id,
        documentId = r.documentId,
        documentFilename = r.documentFilename,
        documentStatus = ApiStatus.fromEntryName(r.documentStatus.entryName),
        amount = r.amount,
        date = r.date,
        time = r.time,
        merchantName = r.merchantName,
        taxId = r.taxId,
        expenseId = r.expenseId,
        ignoredAt = r.ignoredAt,
        manuallyEdited = r.manuallyEdited,
        parserVersion = r.parserVersion,
        createdAt = r.createdAt
      )
    }
}
