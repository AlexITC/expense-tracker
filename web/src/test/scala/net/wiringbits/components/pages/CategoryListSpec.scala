package net.wiringbits.components.pages

import monix.execution.Scheduler
import monix.reactive.subjects.Var
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.common.models.Email
import net.wiringbits.core.{I18nLang, ReactTestDom}
import net.wiringbits.models.AuthState
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.BrowserRouter
import net.wiringbits.{API, AppContext}
import org.scalajs.dom
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import slinky.core.facade.ReactElement

import java.time.Instant
import java.util.UUID

/** RULE-TE-010 justification: CategoryList renders the same validated inputs and error label in both the create form
  * and a per-card edit form; this guards against element-reuse / withKey crashes in that form tree at render time,
  * which a pure test cannot catch.
  */
class CategoryListSpec extends AnyWordSpec with ReactTestDom {

  // CategoryList only calls ctx.api on submit or delete, never at render time or when opening the edit form, so null
  // is safe here.
  private def testCtx(): AppContext = {
    given Scheduler = Scheduler.trampoline()
    AppContext(
      api = null.asInstanceOf[API],
      $auth = Var[AuthState](AuthState.Unauthenticated),
      $lang = Var[I18nLang](I18nLang.English),
      $darkMode = Var[Boolean](false),
      contactEmail = Email.trusted("test@test.com"),
      contactPhone = "+1 555 555 5555",
      executionContext = scala.concurrent.ExecutionContext.global
    )
  }

  private val response = GetCategories.Response(
    data = List(
      GetCategories.Response.Category(
        id = UUID.randomUUID(),
        name = "Groceries",
        createdAt = Instant.EPOCH,
        description = Some("Food and household items")
      ),
      GetCategories.Response
        .Category(id = UUID.randomUUID(), name = "Travel", createdAt = Instant.EPOCH, description = None)
    )
  )

  private def renderList(): Unit =
    render(BrowserRouter(basename = "")(CategoryList(testCtx(), response, () => ())): ReactElement)

  "CategoryList" should {
    "render the description under the category name" in {
      renderList()
      container.textContent must include("Food and household items")
    }

    "render the edit form alongside the create form without throwing" in {
      renderList()
      val editButton = container
        .querySelectorAll("button")
        .toList
        .collectFirst { case b: dom.HTMLElement if b.textContent == "Edit" => b }
        .getOrElse(fail("Edit button not found"))
      noException should be thrownBy act(editButton.click())
      container.querySelectorAll("input[name='ExperimentalTextInput-description']").length must be(2)
    }
  }
}
