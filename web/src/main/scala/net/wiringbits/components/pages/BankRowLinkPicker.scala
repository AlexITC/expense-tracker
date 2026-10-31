package net.wiringbits.components.pages

import com.olvind.mui.csstype.mod.Property.FlexDirection
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.core.SafeHooks
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.components.core.widgets.Container
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object BankRowLinkPicker {
  def apply(props: Props): KeyAddingStage = component(props)

  case class Props(
      stmtId: UUID,
      rowId: UUID,
      ctx: AppContext,
      onSuccess: UUID => Unit,
      onCancel: () => Unit
  )

  private val secondaryStyling = new CSSPropertiesUtils { fontSize = "0.85em"; color = "var(--lc-text-2)" }

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    val (expenses, setExpenses) = Hooks.useState[List[net.wiringbits.api.models.expenses.GetExpenses.Response.Expense]](
      Nil
    )
    val (filter, setFilter) = Hooks.useState("")
    val (error, setError) = Hooks.useState("")

    SafeHooks.useEffect()(() => {
      props.ctx.api.client.getExpenses.onComplete {
        case Success(resp) => setExpenses(resp.data)
        case Failure(ex) => setError(ex.getMessage)
      }
      ()
    })

    def handleLink(expenseId: UUID): Unit =
      props.ctx.api.client
        .linkRowToExpense(
          props.stmtId,
          props.rowId,
          net.wiringbits.api.models.bankstatements.LinkRowToExpense.Request(expenseId)
        )
        .onComplete {
          case Success(_) => props.onSuccess(expenseId)
          case Failure(ex) => setError(ex.getMessage)
        }

    val filtered = expenses.filter { e =>
      val f = filter.toLowerCase
      f.isEmpty || e.date.toString.contains(f) || e.amount.toString.contains(f) ||
      e.merchantName.exists(_.toLowerCase.contains(f)) || e.notes.exists(_.toLowerCase.contains(f))
    }

    Container(
      flexDirection = FlexDirection.column,
      child = Fragment(
        mui.Typography("Link to existing expense").sx(new CSSPropertiesUtils { fontWeight = "bold" }),
        if (error.nonEmpty) mui.Typography(error).sx(new CSSPropertiesUtils { color = "var(--lc-error)" })
        else Fragment(),
        mui.TextField
          .outlined()
          .label("Filter")
          .value(filter)
          .size("small")
          .onChange(e => setFilter(e.target.asInstanceOf[dom.HTMLInputElement].value)),
        Fragment(
          filtered.take(20).map { e =>
            val label = s"${e.date} | ${e.amount} | ${e.merchantName.getOrElse("-")} | ${e.notes.getOrElse("")}"
            Container(
              flexDirection = FlexDirection.row,
              alignItems = Container.Alignment.center,
              child = Fragment(
                mui.Typography(label).sx(secondaryStyling),
                mui.Button.normal()("Link").size("small").onClick(_ => handleLink(e.id))
              )
            ).withKey(e.id.toString)
          }*
        ),
        mui.Button.normal()("Cancel").size("small").onClick(_ => props.onCancel())
      )
    )
  }
}
