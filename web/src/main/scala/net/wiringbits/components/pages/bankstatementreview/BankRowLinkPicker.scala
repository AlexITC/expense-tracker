package net.wiringbits.components.pages.bankstatementreview

import com.olvind.mui.csstype.mod.Property.FlexDirection
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.components.pages.bankstatementreview.BankStatementReviewLogic.{
  LinkedCategory,
  expenseCategory,
  linkPickerEntries
}
import net.wiringbits.core.SafeHooks
import net.wiringbits.utils.ReceiptFileViewer
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.components.core.widgets.Container
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}

import java.time.LocalDate
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object BankRowLinkPicker {
  def apply(props: Props): KeyAddingStage = component(props)

  case class Props(
      stmtId: UUID,
      rowId: UUID,
      ctx: AppContext,
      /** The expense this row uniquely matches; shown first, above the manual list. */
      matchedExpenseId: Option[UUID],
      /** This row's amount and date, used to rank the manual list by closeness. */
      rowAmount: BigDecimal,
      rowDate: LocalDate,
      categories: List[GetCategories.Response.Category],
      onSuccess: UUID => Unit,
      onCancel: () => Unit
  )

  private val secondaryStyling = new CSSPropertiesUtils { fontSize = "0.85em"; color = "var(--lc-text-2)" }

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    val (expenses, setExpenses) = Hooks.useState[List[GetExpenses.Response.Expense]](Nil)
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
          bankStatementId = props.stmtId,
          rowId = props.rowId,
          net.wiringbits.api.models.bankstatements.LinkRowToExpense.Request(expenseId)
        )
        .onComplete {
          case Success(_) => props.onSuccess(expenseId)
          case Failure(ex) => setError(ex.getMessage)
        }

    val (matched, listed) = linkPickerEntries(expenses, filter, props.matchedExpenseId, props.rowAmount, props.rowDate)

    def matchedBox(e: GetExpenses.Response.Expense): ReactElement = {
      val categoryLabel = expenseCategory(e, props.categories) match {
        case LinkedCategory.Named(name) => name
        case LinkedCategory.Multiple => "Multiple"
        case LinkedCategory.Unknown => "—"
      }
      mui.Box.sx(new CSSPropertiesUtils {
        backgroundColor = "var(--lc-tint)"; padding = "12px"; borderRadius = "8px"; marginBottom = "12px"
      })(
        mui.Typography("Matched expense").sx(secondaryStyling),
        mui
          .Typography(s"${e.merchantName.getOrElse("-")} · $categoryLabel")
          .sx(new CSSPropertiesUtils {
            fontWeight = "600"
          }),
        e.notes.fold[ReactElement](Fragment())(n => mui.Typography(n).sx(secondaryStyling)),
        mui.Typography(s"${e.amount} · ${e.date}").sx(secondaryStyling),
        e.receiptIds.headOption.fold[ReactElement](Fragment())(receiptId =>
          (mui.Button
            .normal()("🧾 Open receipt")
            .size("small")
            .onClick(_ => ReceiptFileViewer.openInNewTab(props.ctx.api.client, receiptId, setError))
            .build: ReactElement)
        ),
        (mui
          .Tooltip("Links this row to this expense, keeping its merchant, category and notes")(
            (mui.Button
              .normal()("Link this expense")
              .variant("contained")
              .color("primary")
              .size("small")
              .onClick(_ => handleLink(e.id))
              .build: ReactElement)
          ): ReactElement)
      )
    }

    Container(
      flexDirection = FlexDirection.column,
      child = Fragment(
        mui.Typography("Link to existing expense").sx(new CSSPropertiesUtils { fontWeight = "bold" }),
        if (error.nonEmpty) mui.Typography(error).sx(new CSSPropertiesUtils { color = "var(--lc-error)" })
        else Fragment(),
        matched.fold[ReactElement](Fragment())(matchedBox),
        mui.TextField
          .outlined()
          .label("Filter")
          .value(filter)
          .size("small")
          .onChange(e => setFilter(e.target.asInstanceOf[dom.HTMLInputElement].value)),
        Fragment(
          listed.map { e =>
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
