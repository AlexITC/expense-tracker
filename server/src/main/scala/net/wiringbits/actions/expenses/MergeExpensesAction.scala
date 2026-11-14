package net.wiringbits.actions.expenses

import net.wiringbits.api.models.expenses.{MergeDetailsSource, MergeExpenses, MergeShape}
import net.wiringbits.repositories.ExpensesRepository
import net.wiringbits.services.BlobStorage
import org.slf4j.LoggerFactory

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class MergeExpensesAction @Inject() (expensesRepository: ExpensesRepository, blobStorage: BlobStorage)(implicit
    ec: ExecutionContext
) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  // The discarded document's blob is deleted only after the merge commits, so a rolled-back merge never loses the file.
  // A failed blob delete only orphans storage, so it is logged instead of failing a merge that already committed.
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
      _ <- discardedStorageKey.filter(_.nonEmpty).fold(Future.unit) { key =>
        blobStorage.delete(key).recover { case NonFatal(e) =>
          logger.error(s"Failed to delete blob $key after merge userId=$userId", e)
        }
      }
    } yield MergeExpenses.Response()
}
