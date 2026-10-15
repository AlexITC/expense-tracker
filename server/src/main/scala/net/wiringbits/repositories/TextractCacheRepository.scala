package net.wiringbits.repositories

import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.TextractCacheDAO
import net.wiringbits.repositories.models.TextractCache
import play.api.db.Database

import javax.inject.Inject
import scala.concurrent.Future

class TextractCacheRepository @Inject() (database: Database)(implicit ec: DatabaseExecutionContext) {

  def create(request: TextractCache.Create): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      TextractCacheDAO.create(request)
    }
  }

  def find(sha256: String, apiType: String): Future[Option[TextractCache]] = Future {
    database.withConnection { implicit conn =>
      TextractCacheDAO.find(sha256, apiType)
    }
  }
}
