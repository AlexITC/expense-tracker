package net.wiringbits.actions.receipts

import io.scalaland.chimney.dsl.*
import net.wiringbits.api.models.documents.DocumentStatus as ApiDocumentStatus
import net.wiringbits.api.models.receipts.GetReceipts
import net.wiringbits.repositories.ReceiptRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetReceiptsAction @Inject() (receiptRepository: ReceiptRepository)(implicit ec: ExecutionContext) {

  def apply(userId: UUID): Future[GetReceipts.Response] =
    receiptRepository.all(userId).map { receipts =>
      val items = receipts.map { r =>
        GetReceipts.Response.Receipt(
          id = r.id,
          documentFilename = r.documentFilename,
          documentStatus = r.status.transformInto[ApiDocumentStatus],
          amount = r.amount,
          date = r.date,
          merchantName = r.merchantName,
          taxId = r.taxId,
          merchantId = r.merchantId,
          expenseId = r.expenseId,
          ignoredAt = r.ignoredAt,
          createdAt = r.createdAt
        )
      }
      GetReceipts.Response(items)
    }
}
