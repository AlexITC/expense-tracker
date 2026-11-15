package net.wiringbits.components.pages

import net.wiringbits.api.ApiClient
import net.wiringbits.api.models.merchants.{CreateMerchant, GetMerchants}
import net.wiringbits.common.models.MerchantName
import net.wiringbits.ui.forms.ExistingOrNewValue
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import sttp.client3.testing.SttpBackendStub

import java.time.Instant
import java.util.UUID
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}

class MerchantResolverSpec extends AnyWordSpec {
  private given ExecutionContext = ExecutionContext.parasitic

  private class StubClient(newId: UUID)
      extends ApiClient(ApiClient.Config("http://localhost"))(using
        ExecutionContext.parasitic,
        SttpBackendStub.asynchronousFuture
      ) {
    val created: ListBuffer[CreateMerchant.Request] = ListBuffer.empty

    override def createMerchant(request: CreateMerchant.Request): Future[CreateMerchant.Response] = {
      created += request
      Future.successful(CreateMerchant.Response(newId, request.name.string))
    }
  }

  private val store =
    GetMerchants.Response.Merchant(UUID.randomUUID(), "Store", None, None, None, reviewed = true, Instant.EPOCH)

  private def resolve(
      client: StubClient,
      selection: Option[ExistingOrNewValue[MerchantName, UUID]],
      categoryId: Option[UUID]
  ) =
    MerchantResolver
      .findOrCreateMerchantOpt(client, List(store), selection, categoryId)
      .value
      .getOrElse(fail("future not completed"))
      .get

  "findOrCreateMerchantOpt" should {
    "return None without creating when there is no selection" in {
      val client = StubClient(UUID.randomUUID())
      resolve(client, None, Some(UUID.randomUUID())) must be(None)
      client.created must be(empty)
    }

    "use the picked id without creating" in {
      val client = StubClient(UUID.randomUUID())
      val pickedId = UUID.randomUUID()
      resolve(client, Some(ExistingOrNewValue(MerchantName.trusted("Other"), Some(pickedId))), None) must be(
        Some((pickedId, None))
      )
      client.created must be(empty)
    }

    "create an unknown merchant with the given category and return it" in {
      val newId = UUID.randomUUID()
      val categoryId = UUID.randomUUID()
      val client = StubClient(newId)
      val result = resolve(client, Some(ExistingOrNewValue(MerchantName.trusted("Shop"), None)), Some(categoryId))
      result.map(_._1) must be(Some(newId))
      result.flatMap(_._2).map(m => (m.id, m.name, m.categoryId)) must be(Some((newId, "Shop", Some(categoryId))))
      client.created.map(r => (r.name.string, r.categoryId)).toList must be(List(("Shop", Some(categoryId))))
    }
  }
}
