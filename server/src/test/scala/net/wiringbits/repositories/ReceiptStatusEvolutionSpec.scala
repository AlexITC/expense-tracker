package net.wiringbits.repositories

import anorm.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForEach
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.utility.DockerImageName
import play.api.db.Databases
import play.api.db.evolutions.{ClassLoaderEvolutionsReader, Evolutions, SimpleEvolutionsReader}

import java.sql.DriverManager
import java.util.UUID

class ReceiptStatusEvolutionSpec extends AnyWordSpec with TestContainerForEach {

  // A migration test applies evolutions incrementally, so it needs its own container rather than the
  // shared one (which has every evolution pre-applied).
  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18"))

  "evolution 7" should {
    "backfill receipt.status from the receipt's document and drop the document status columns" in withContainers {
      postgres =>
        val initConn =
          DriverManager.getConnection(postgres.jdbcUrl, postgres.container.getUsername, postgres.container.getPassword)
        try initConn.createStatement().execute("CREATE EXTENSION IF NOT EXISTS CITEXT;")
        finally initConn.close()
        val database = Databases(
          driver = "org.postgresql.Driver",
          url = postgres.jdbcUrl,
          name = "default",
          config = Map(
            "username" -> postgres.container.getUsername,
            "password" -> postgres.container.getPassword
          )
        )
        val all = ClassLoaderEvolutionsReader().evolutions("default").toList
        Evolutions.applyEvolutions(database, SimpleEvolutionsReader.forDefault(all.filter(_.revision <= 6)*))

        // Seeded with raw SQL because the DAOs target the latest schema, which doesn't match revision 6
        val userId = UUID.randomUUID()
        val documentId = UUID.randomUUID()
        val receiptId = UUID.randomUUID()
        database.withConnection { implicit conn =>
          SQL"""
          INSERT INTO users (user_id, name, email, password)
          VALUES (${userId.toString}::UUID, 'name', 'evolution@test.com', 'password')
          """.executeUpdate()
          SQL"""
          INSERT INTO document (id, user_id, sha256, storage_key, kind, filename, status, created_at, updated_at)
          VALUES (${documentId.toString}::UUID, ${userId.toString}::UUID, 'sha', 'key', 'Receipt', 'r.pdf', 'Parsed', NOW(), NOW())
          """.executeUpdate()
          SQL"""
          INSERT INTO receipt (id, user_id, document_id, created_at, updated_at)
          VALUES (${receiptId.toString}::UUID, ${userId.toString}::UUID, ${documentId.toString}::UUID, NOW(), NOW())
          """.executeUpdate()
        }

        Evolutions.applyEvolutions(database, SimpleEvolutionsReader.forDefault(all*))

        database.withConnection { implicit conn =>
          val status = SQL"""SELECT status FROM receipt WHERE id = ${receiptId.toString}::UUID"""
            .as(SqlParser.scalar[String].single)
          status must be("Parsed")

          val nullable = SQL"""
          SELECT is_nullable FROM information_schema.columns
          WHERE table_name = 'receipt' AND column_name = 'status'
          """.as(SqlParser.scalar[String].single)
          nullable must be("NO")

          val documentColumns = SQL"""
          SELECT column_name FROM information_schema.columns
          WHERE table_name = 'document' AND column_name IN ('status', 'parser_version')
          """.as(SqlParser.scalar[String].*)
          documentColumns must be(empty)
        }
        database.shutdown()
    }
  }
}
