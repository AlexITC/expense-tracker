package net.wiringbits.actions.expenses

import net.wiringbits.api.models.expenses.{MergeDetailsSource, MergeExpenses, MergeShape}
import net.wiringbits.repositories.ExpensesRepository
import net.wiringbits.services.BlobStorage

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class MergeExpensesAction @Inject() (expensesRepository: ExpensesRepository, blobStorage: BlobStorage)(implicit
    ec: ExecutionContext
) {

  // The discarded document's blob is deleted only after the merge commits, so a rolled-back merge never loses the file
  def apply(
      survivorId: UUID,
      loserId: UUID,
      userId: UUID,
      keepDetailsFrom: MergeDetailsSource,
      shape: MergeShape
  ): Future[MergeExpenses.Response] =
    for {
      discardedStorageKey <- expensesRepository.merge(
        survivorId = survivorId,
        loserId = loserId,
        userId = userId,
        keepDetailsFrom = keepDetailsFrom,
        shape = shape
      )
      _ <- discardedStorageKey.filter(_.nonEmpty).fold(Future.unit)(blobStorage.delete)
    } yield MergeExpenses.Response()
}
