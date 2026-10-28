package net.wiringbits.repositories

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

  def findByName(userId: UUID, name: String): Future[Option[Category]] = Future {
    database.withConnection { implicit conn =>
      CategoryDAO.findByName(userId, name)
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
