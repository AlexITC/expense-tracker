package net.wiringbits.components.pages.expenses

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.utils.ExpenseDuplicateDetector.{Candidate, Tier}
import net.wiringbits.utils.ReceiptFileViewer
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
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
      receiptFilenames: Map[UUID, String],
      onMerge: Candidate => Unit,
      onDismiss: Candidate => Unit,
      onError: String => Unit
  ): KeyAddingStage =
    component(Props(ctx, candidates, categories, receiptFilenames, onMerge, onDismiss, onError))

  case class Props(
      ctx: AppContext,
      candidates: List[Candidate],
      categories: GetCategories.Response,
      receiptFilenames: Map[UUID, String],
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

  private def groupLabels(tier: Tier): (String, String) = tier match {
    case Tier.Strong => ("STRONG", "Same amount, close date and same merchant.")
    case Tier.Weak =>
      ("WEAK", "Same amount and close date, but the merchant differs. Check the receipt before merging.")
  }

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
        mui.Typography(categoryName(e)),
        mui.Typography(e.notes.filter(_.nonEmpty).getOrElse("—"))
      )

    def card(c: Candidate): ReactElement = {
      val receipt = props.receiptFilenames.get(c.receiptId)
      mui.Box
        .sx(cardStyling)(
          mui.Box.sx(columnsStyling)(column("Bank row", c.survivor), column("Receipt", c.loser)),
          if (c.ambiguous)
            mui.Typography("Several expenses match this one; check the receipt to pick the right pair.").sx(hintStyling)
          else Fragment(),
          mui.Box.sx(actionsStyling)(
            receipt.fold[ReactElement](Fragment()) { filename =>
              mui.Button
                .normal()("View receipt")
                .size("small")
                .onClick(_ =>
                  ReceiptFileViewer.openInNewTab(props.ctx.api.client, c.receiptId, filename, props.onError)
                )
            },
            mui.Button.normal()("Merge").variant("contained").size("small").onClick(_ => props.onMerge(c)),
            mui.Button.normal()("Dismiss").size("small").onClick(_ => props.onDismiss(c))
          )
        )
        .withKey(ExpenseList.dismissKey(c))
    }

    def group(tier: Tier, candidates: List[Candidate]): ReactElement = {
      val (title, hint) = groupLabels(tier)
      if (candidates.isEmpty) Fragment()
      else
        Fragment(
          mui.Typography(s"$title (${candidates.size})").sx(groupTitleStyling),
          mui.Typography(hint).sx(hintStyling),
          Fragment(candidates.map(c => card(c))*)
        )
    }

    val byTier = props.candidates.groupBy(_.tier)
    mui.Box.sx(panelStyling)(
      mui.Typography("Possible duplicates").variant("h6"),
      Fragment(
        Tier.values.toList.map(tier => Fragment(group(tier, byTier.getOrElse(tier, Nil))).withKey(tier.toString))*
      )
    )
  }
}
