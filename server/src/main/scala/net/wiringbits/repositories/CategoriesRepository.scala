package net.wiringbits.repositories

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.CategoryDAO
import net.wiringbits.repositories.models.Category
import play.api.db.Database

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.Future

class CategoriesRepository @Inject() (database: Database)(implicit
    ec: DatabaseExecutionContext,
    clock: Clock
) {

  def create(request: Category.Create): Future[Unit] =
    Future {
      ConstraintViolations.translating("category_user_id_name_unique" -> "A category with that name already exists") {
        database.withTransaction { implicit conn =>
          CategoryDAO.create(request)
        }
      }
    }

  def all(userId: UUID): Future[List[Category]] = Future {
    database.withConnection { implicit conn =>
      CategoryDAO.all(userId)
    }
  }

  def find(id: UUID, userId: UUID): Future[Option[Category]] = Future {
    database.withConnection { implicit conn =>
      CategoryDAO.find(id, userId)
    }
  }

  // The conflict is absorbed: a concurrent or case-variant insert resolves to the stored row.
  def getOrCreate(userId: UUID, name: String): Future[Category] = Future {
    database.withTransaction { implicit conn =>
      val now = clock.instant()
      val request = Category.Create(UuidCreator.getTimeOrderedEpoch(), userId, name, now, now)
      CategoryDAO
        .createIfAbsent(request)
        .flatMap(CategoryDAO.find(_, userId))
        .orElse(CategoryDAO.findByName(userId, name))
        .getOrElse(throw new RuntimeException("Category not found"))
    }
  }

  def update(id: UUID, userId: UUID, name: String): Future[Unit] =
    Future {
      ConstraintViolations.translating("category_user_id_name_unique" -> "A category with that name already exists") {
        database.withTransaction { implicit conn =>
          CategoryDAO.update(id, userId, name, clock.instant())
        }
      }
    }

  def delete(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      CategoryDAO.delete(id, userId)
    }
  }
}
