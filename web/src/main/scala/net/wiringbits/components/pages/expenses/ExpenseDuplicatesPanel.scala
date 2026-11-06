package net.wiringbits.components.pages.expenses

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.api.models.receipts.GetReceipts
import net.wiringbits.components.widgets.Loader
import net.wiringbits.utils.ExpenseDuplicateDetector.{Candidate, Tier}
import net.wiringbits.utils.ReceiptFileViewer
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.components.core.AsyncComponent
import slinky.core.facade.{Fragment, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}

import java.util.UUID
import scala.concurrent.ExecutionContext

/** Lists likely duplicate pairs (bank-row expense vs receipt-only expense) with merge and dismiss actions. */
object ExpenseDuplicatesPanel {
  def apply(
      ctx: AppContext,
      candidates: List[Candidate],
      categories: GetCategories.Response,
      onMerge: Candidate => Unit,
      onDismiss: Candidate => Unit,
      onError: String => Unit
  ): KeyAddingStage =
    component(Props(ctx, candidates, categories, onMerge, onDismiss, onError))

  case class Props(
      ctx: AppContext,
      candidates: List[Candidate],
      categories: GetCategories.Response,
      onMerge: Candidate => Unit,
      onDismiss: Candidate => Unit,
      onError: String => Unit
  )

  private val panelStyling = new CSSPropertiesUtils {
    border = "1px solid var(--lc-border)"
    borderRadius = "8px"
    padding = "16px 24px"
    marginBottom = "16px"
    backgroundColor = "var(--lc-paper)"
  }
  private val groupTitleStyling = new CSSPropertiesUtils { fontWeight = "600"; marginTop = "8px" }
  private val hintStyling = new CSSPropertiesUtils { fontSize = "12.5px"; color = "var(--lc-text-2)" }
  private val cardStyling = new CSSPropertiesUtils {
    border = "1px solid var(--lc-border)"
    borderRadius = "6px"
    padding = "12px"
    marginTop = "10px"
  }
  private val columnsStyling = new CSSPropertiesUtils { display = "flex"; gap = "24px" }
  private val columnStyling = new CSSPropertiesUtils { flex = "1" }
  private val columnTitleStyling = new CSSPropertiesUtils { fontSize = "12px"; color = "var(--lc-text-2)" }
  private val actionsStyling = new CSSPropertiesUtils { display = "flex"; gap = "8px"; marginTop = "10px" }

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext

    def categoryName(e: GetExpenses.Response.Expense): String =
      e.splits.headOption
        .flatMap(s => props.categories.data.find(_.id == s.categoryId))
        .fold("—")(_.name)

    def column(title: String, e: GetExpenses.Response.Expense): ReactElement =
      mui.Box.sx(columnStyling)(
        mui.Typography(title).sx(columnTitleStyling),
        mui.Typography(s"€${ExpenseList.formatAmount(e.amount)}"),
        mui.Typography(e.date.toString),
        mui.Typography(e.merchantName.getOrElse("—")),
        mui.Typography(categoryName(e))
      )

    def card(c: Candidate, filenames: Map[UUID, String]): ReactElement = {
      val receipt = c.loser.receiptIds.headOption.flatMap(id => filenames.get(id).map(id -> _))
      mui.Box
        .sx(cardStyling)(
          mui.Box.sx(columnsStyling)(column("Bank row", c.survivor), column("Receipt", c.loser)),
          if (c.ambiguous)
            mui.Typography("Several expenses match this one; check the receipt to pick the right pair.").sx(hintStyling)
          else Fragment(),
          mui.Box.sx(actionsStyling)(
            receipt.fold[ReactElement](Fragment()) { case (id, filename) =>
              mui.Button
                .normal()("View receipt")
                .size("small")
                .onClick(_ => ReceiptFileViewer.openInNewTab(props.ctx.api.client, id, filename, props.onError))
            },
            mui.Button.normal()("Merge").variant("contained").size("small").onClick(_ => props.onMerge(c)),
            mui.Button.normal()("Dismiss").size("small").onClick(_ => props.onDismiss(c))
          )
        )
        .withKey(ExpenseList.dismissKey(c))
    }

    def group(title: String, hint: String, candidates: List[Candidate], filenames: Map[UUID, String]): ReactElement =
      if (candidates.isEmpty) Fragment()
      else
        Fragment(
          mui.Typography(s"$title (${candidates.size})").sx(groupTitleStyling),
          mui.Typography(hint).sx(hintStyling),
          Fragment(candidates.map(c => card(c, filenames))*)
        )

    AsyncComponent[GetReceipts.Response](
      fetch = () => props.ctx.api.client.getReceipts,
      render = receipts => {
        val filenames = receipts.data.map(r => r.id -> r.documentFilename).toMap
        val (strong, weak) = props.candidates.partition(_.tier == Tier.Strong)
        mui.Box.sx(panelStyling)(
          mui.Typography("Possible duplicates").variant("h6"),
          group("STRONG", "Same amount, close date and same merchant.", strong, filenames),
          group(
            "WEAK",
            "Same amount and close date, but the merchant differs. Check the receipt before merging.",
            weak,
            filenames
          )
        )
      },
      progressIndicator = () => Loader(props.ctx)
    )
  }
}
