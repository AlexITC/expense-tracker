package net.wiringbits.components.pages

import net.wiringbits.api.ApiClient
import net.wiringbits.api.models.categories.{CreateCategory, GetCategories}
import net.wiringbits.common.models.CategoryName
import net.wiringbits.ui.forms.ExistingOrNewValue
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import sttp.client3.testing.SttpBackendStub

import java.time.Instant
import java.util.UUID
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}

class CategoryResolverSpec extends AnyWordSpec {
  private given ExecutionContext = ExecutionContext.parasitic

  private class StubClient(newId: UUID)
      extends ApiClient(ApiClient.Config("http://localhost"))(using
        ExecutionContext.parasitic,
        SttpBackendStub.asynchronousFuture
      ) {
    val created: ListBuffer[CreateCategory.Request] = ListBuffer.empty

    override def createCategory(request: CreateCategory.Request): Future[CreateCategory.Response] = {
      created += request
      Future.successful(CreateCategory.Response(newId, request.name.string))
    }
  }

  private val food =
    GetCategories.Response.Category(
      id = UUID.randomUUID(),
      name = "Food",
      createdAt = Instant.now(),
      description = None
    )

  private def resolve(client: StubClient, selection: ExistingOrNewValue[CategoryName, UUID]) =
    CategoryResolver
      .findOrCreateCategoryReturningCreated(client, List(food), selection)
      .value
      .getOrElse(fail("future not completed"))
      .get

  "findOrCreateCategoryReturningCreated" should {
    "use the picked id without creating" in {
      val client = StubClient(UUID.randomUUID())
      val pickedId = UUID.randomUUID()
      resolve(client, ExistingOrNewValue(CategoryName.trusted("Other"), Some(pickedId))) must be((pickedId, None))
      client.created must be(empty)
    }

    "reuse an existing category matched case-insensitively without creating" in {
      val client = StubClient(UUID.randomUUID())
      resolve(client, ExistingOrNewValue(CategoryName.trusted("fOOd"), None)) must be((food.id, None))
      client.created must be(empty)
    }

    "create an unknown category and return it" in {
      val newId = UUID.randomUUID()
      val client = StubClient(newId)
      val (id, created) = resolve(client, ExistingOrNewValue(CategoryName.trusted("Travel"), None))
      id must be(newId)
      created.map(c => (c.id, c.name)) must be(Some((newId, "Travel")))
      client.created.map(_.name.string).toList must be(List("Travel"))
    }
  }
}
