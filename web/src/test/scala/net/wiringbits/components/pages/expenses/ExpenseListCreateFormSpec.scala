package net.wiringbits.components.pages.expenses

import monix.execution.Scheduler
import monix.reactive.subjects.Var
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.common.models.Email
import net.wiringbits.core.{I18nLang, ReactTestDom}
import net.wiringbits.models.AuthState
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.BrowserRouter
import net.wiringbits.{API, AppContext}
import org.scalajs.dom
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import slinky.core.facade.ReactElement

/** RULE-TE-010 justification: the create form is laid out with one input per line and its buttons on a shared line;
  * that structure only exists in the rendered DOM.
  */
class ExpenseListCreateFormSpec extends AnyWordSpec with ReactTestDom {

  // Opening the create form never calls ctx.api, so null is safe here.
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

  private def buttons: List[dom.HTMLElement] =
    container.querySelectorAll("button").toList.collect { case b: dom.HTMLElement => b }

  private def button(label: String): dom.HTMLElement =
    buttons.filter(_.textContent == label) match {
      case single :: Nil => single
      case other => fail(s"Expected exactly one '$label' button, found ${other.size}")
    }

  private def openCreateForm(): Unit = {
    render(
      BrowserRouter(basename = "")(
        ExpenseList(
          testCtx(),
          GetExpenses.Response(Nil),
          GetMerchants.Response(Nil),
          GetCategories.Response(Nil),
          Map.empty
        )
      ): ReactElement
    )
    act(button("+ Add expense").click())
  }

  "create form" should {
    "put Add and Cancel on the same line, apart from the inputs" in {
      openCreateForm()

      val add = button("Add")
      val cancel = button("Cancel")

      add.parentElement must be theSameInstanceAs cancel.parentElement
      add.parentElement.querySelectorAll("input").length must be(0)
    }

    "render the amount and date inputs" in {
      openCreateForm()

      container.querySelectorAll("input[type='text'], input:not([type])").length must be >= 2
      container.textContent must include("Amount")
      container.textContent must include("Date")
    }
  }
}
