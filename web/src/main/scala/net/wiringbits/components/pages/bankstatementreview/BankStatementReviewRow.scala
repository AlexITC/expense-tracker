package net.wiringbits.components.pages.bankstatementreview

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.api.models.bankstatements.GetBankStatement
import net.wiringbits.api.models.receipts.GetReceipts
import net.wiringbits.api.utils.RowRuleVerdict
import net.wiringbits.components.pages.bankstatementreview.BankStatementReviewLogic.{
  LinkedCategory,
  MatchedExpense,
  PrimaryAction,
  RowInfo,
  RowMenuItem,
  menuItemsFor,
  primaryActionFor
}
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, React, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}

import java.util.UUID
import scala.scalajs.js

/** One row of the bank statement review table. Memoized on props equality, so the parent must pass stable callbacks;
  * the "…" menu state lives here so opening it re-renders only this row.
  */
object BankStatementReviewRow {
  def apply(props: Props): KeyAddingStage = component(props)

  /** What a row asks the page to do; the page owns the drawers and the API calls. */
  enum RowAction {
    case OpenExpenseForm, OpenRefundForm, OpenLinkPicker, OpenRuleForm, Ignore, Unignore, Unlink, UnlinkRefund
  }

  /** What a row asks the page to do with its matched receipt. */
  enum ReceiptAction {
    case View(receiptId: UUID, filename: String)
    case Detach
    case Reattach
  }

  case class Props(
      row: GetBankStatement.StatementRowData,
      info: RowInfo,
      selected: Boolean,
      showCheckbox: Boolean,
      pollingSuggestions: Boolean,
      matchedExpense: Option[MatchedExpense],
      matchedReceipt: Option[GetReceipts.Response.Receipt],
      receiptDetached: Boolean,
      onToggleSelected: (UUID, Boolean) => Unit,
      onAction: (UUID, RowAction) => Unit,
      onReceiptAction: (UUID, ReceiptAction) => Unit
  )

  // Slinky wraps Scala props in a single JS object, so React's default shallow compare never matches; compare the
  // case class instead.
  val component: FunctionalComponent[Props] = React.memo(
    FunctionalComponent[Props] { props =>
      val row = props.row
      val info = props.info
      val (menuAnchor, setMenuAnchor) = Hooks.useState[Option[dom.Element]](None)

      def act(action: RowAction): Unit = props.onAction(row.id, action)

      def primaryAction: ReactElement =
        primaryActionFor(
          row,
          isReady = info.isReady,
          verdict = info.verdict,
          matchedExpense = props.matchedExpense
        ) match {
          case PrimaryAction.Linked =>
            (mui.Button
              .normal()("Linked")
              .size("small")
              .disabled(true)
              .sx(new CSSPropertiesUtils { color = "var(--lc-brand)" })
              .build: ReactElement)
          case PrimaryAction.Unignore =>
            (mui.Button
              .normal()("Un-ignore")
              .size("small")
              .onClick(_ => act(RowAction.Unignore))
              .build: ReactElement)
          case PrimaryAction.IgnoreByRule =>
            (mui.Button
              .normal()("Ignore")
              .size("small")
              .variant("contained")
              .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" })
              .onClick(_ => act(RowAction.Ignore))
              .build: ReactElement)
          case PrimaryAction.ReviewRefund =>
            (mui.Button
              .normal()("Import refund")
              .size("small")
              .variant("contained")
              .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" })
              .onClick(_ => act(RowAction.OpenRefundForm))
              .build: ReactElement)
          case PrimaryAction.ReviewExpense =>
            (mui.Button
              .normal()("Confirm")
              .size("small")
              .variant("contained")
              .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" })
              .onClick(_ => act(RowAction.OpenExpenseForm))
              .build: ReactElement)
          case PrimaryAction.ConfirmLink =>
            (mui.Button
              .normal()("Confirm")
              .size("small")
              .variant("contained")
              .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-brand)" })
              .onClick(_ => act(RowAction.OpenLinkPicker))
              .build: ReactElement)
          case PrimaryAction.CreateRefund =>
            (mui.Button
              .normal()("Create refund")
              .size("small")
              .variant("outlined")
              .onClick(_ => act(RowAction.OpenRefundForm))
              .build: ReactElement)
          case PrimaryAction.CreateExpense =>
            (mui.Button
              .normal()("Create expense")
              .size("small")
              .variant("outlined")
              .onClick(_ => act(RowAction.OpenExpenseForm))
              .build: ReactElement)
        }

      // Small chip reused by the Match (merchant) and Category cells.
      def tintChip(lbl: String, textColor: String): ReactElement =
        (mui.Chip
          .label(lbl)
          .size("small")
          .sx(new CSSPropertiesUtils {
            height = "22px"; fontSize = "12px"; backgroundColor = "var(--lc-tint)"; color = textColor
          }): ReactElement)

      // Opens the link picker with the matched expense first.
      def linkExistingChip: ReactElement =
        (mui.Chip
          .label("↔ Link existing")
          .size("small")
          .clickable(true)
          .onClick(_ => act(RowAction.OpenLinkPicker))
          .sx(new CSSPropertiesUtils {
            height = "22px"; fontSize = "12px"; backgroundColor = "var(--lc-tint)"; color = "var(--lc-brand)"
          }): ReactElement)

      def linkedCategoryChip(category: LinkedCategory): ReactElement =
        category match {
          case LinkedCategory.Named(name) => tintChip(lbl = name, textColor = "var(--lc-brand-dark)")
          case LinkedCategory.Multiple => tintChip(lbl = "Multiple", textColor = "var(--lc-text-2)")
          case LinkedCategory.Unknown => dash
        }

      def dash: ReactElement =
        mui.Typography("—").sx(new CSSPropertiesUtils { color = "var(--lc-hint)"; fontSize = "13px" })

      def suggestingOrDash: ReactElement =
        if (props.pollingSuggestions && row.ignoredAt.isEmpty && row.amount < 0)
          mui.Typography("suggesting…").sx(new CSSPropertiesUtils { color = "var(--lc-hint)"; fontSize = "13px" })
        else dash

      def smallTextButton(lbl: String, onClick: () => Unit): ReactElement =
        (mui.Button
          .normal()(lbl)
          .size("small")
          .sx(new CSSPropertiesUtils { minWidth = "0"; padding = "0 4px"; fontSize = "12px" })
          .onClick(_ => onClick())
          .build: ReactElement)

      // The receipt processing would attach, with View plus Detach (or Re-attach once the user opted out).
      def receiptControls(receipt: GetReceipts.Response.Receipt): ReactElement =
        Fragment(
          if (props.receiptDetached) tintChip(lbl = "Receipt detached", textColor = "var(--lc-text-2)")
          else tintChip(lbl = "🧾 Receipt", textColor = "var(--lc-brand)"),
          smallTextButton(
            "View",
            () => props.onReceiptAction(row.id, ReceiptAction.View(receipt.id, receipt.documentFilename))
          ),
          if (props.receiptDetached)
            smallTextButton("Re-attach", () => props.onReceiptAction(row.id, ReceiptAction.Reattach))
          else smallTextButton("Detach", () => props.onReceiptAction(row.id, ReceiptAction.Detach))
        )

      // Linked rows show the linked expense's merchant (else "Linked"); rows matched to an existing expense show that
      // expense's merchant plus a "↔ Link existing" chip that opens the link picker; other rows the merchant set on
      // import, rule-matched or AI-suggested. "→ Ignore" takes over when an Ignore rule applies, and the receipt controls
      // show when processing would attach a receipt.
      def matchCell: ReactElement =
        if (row.expenseId.isDefined || row.refundId.isDefined)
          tintChip(lbl = info.linkedMerchantName.getOrElse("Linked"), textColor = "var(--lc-brand)")
        else if (info.verdict == RowRuleVerdict.Ignore)
          tintChip(lbl = "→ Ignore", textColor = "var(--lc-brand-dark)")
        else {
          val merchant = props.matchedExpense match {
            case Some(matched) =>
              matched.merchantName.fold(dash)(name => tintChip(lbl = name, textColor = "var(--lc-brand-dark)"))
            case None =>
              info.merchantName match {
                case Some(name) => tintChip(lbl = name, textColor = "var(--lc-brand-dark)")
                case None => suggestingOrDash
              }
          }
          if (props.matchedExpense.isDefined || props.matchedReceipt.isDefined)
            mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "4px"; alignItems = "center" })(
              merchant,
              if (props.matchedExpense.isDefined) linkExistingChip else Fragment(),
              props.matchedReceipt.fold[ReactElement](Fragment())(receiptControls)
            )
          else merchant
        }

      // Linked rows show the linked expense's category, and rows matched to an existing expense that expense's category;
      // other rows the category the expense will get on import, tagged "AI" when it came from a suggestion rather than a
      // rule; rule-matched credits show their refund's category.
      def categoryCell: ReactElement =
        if (row.expenseId.isDefined || row.refundId.isDefined) linkedCategoryChip(info.linkedCategory)
        else if (info.verdict == RowRuleVerdict.Ignore) dash
        else
          (props.matchedExpense, info.resolved) match {
            case (Some(matched), _) => linkedCategoryChip(matched.category)
            case (None, Some(res)) =>
              mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "4px"; alignItems = "center" })(
                tintChip(lbl = info.categoryName.getOrElse(""), textColor = "var(--lc-brand-dark)"),
                if (res.categoryFromAi)
                  (mui
                    .Typography("AI")
                    .sx(new CSSPropertiesUtils {
                      fontSize = "10px"; fontWeight = "700"; color = "var(--lc-hint)"; letterSpacing = "0.5px"
                    }): ReactElement)
                else Fragment()
              )
            case (None, None) =>
              info.refund
                .flatMap(_.categoryId)
                .fold(suggestingOrDash)(_ =>
                  tintChip(lbl = info.categoryName.getOrElse(""), textColor = "var(--lc-brand-dark)")
                )
          }

      def menu: ReactElement =
        menuAnchor.fold[ReactElement](Fragment()) { anchor =>
          def closeMenuAnd(action: RowAction): Unit = { setMenuAnchor(None); act(action) }
          mui
            .Menu(open = true)
            .anchorEl(anchor)
            // Menu types onClose as js.Any, so hand it a typed JS function.
            .onClose((((_, _) => setMenuAnchor(None)): js.Function2[js.Any, js.Any, Unit]))(
              menuItemsFor(row).map { item =>
                val (label, action) = item match {
                  case RowMenuItem.LinkExpense => ("Link to existing expense", RowAction.OpenLinkPicker)
                  case RowMenuItem.CreateExpense => ("Create new expense", RowAction.OpenExpenseForm)
                  case RowMenuItem.CreateRule => ("Create import rule", RowAction.OpenRuleForm)
                  case RowMenuItem.Ignore => ("Ignore", RowAction.Ignore)
                  case RowMenuItem.Unlink => ("Unlink", RowAction.Unlink)
                  case RowMenuItem.UnlinkRefund => ("Unlink", RowAction.UnlinkRefund)
                  case RowMenuItem.Unignore => ("Un-ignore", RowAction.Unignore)
                }
                (mui.MenuItem
                  .normal()(label)
                  .onClick(_ => closeMenuAnd(action))
                  .withKey(item.toString)
                  .build: ReactElement)
              }*
            )
        }

      val amtSign = if (row.amount > 0) "+" else ""
      val amtColor = if (row.amount > 0) "var(--lc-brand)" else "var(--lc-error)"

      mui.TableRow
        .hover(true)
        .selected(props.selected)(
          mui.TableCell.padding("checkbox")(
            if (props.showCheckbox)
              (mui.Checkbox
                .checked(props.selected)
                .size("small")
                .onChange((_, checked) => props.onToggleSelected(row.id, checked)): ReactElement)
            else Fragment()
          ),
          mui.TableCell.sx(new CSSPropertiesUtils {
            fontFamily = "\"IBM Plex Mono\", monospace"
            fontSize = "13px"; color = "var(--lc-text-2)"; whiteSpace = "nowrap"
          })(row.date.toString),
          mui.TableCell.align("right")(
            mui
              .Typography(s"$amtSign${row.amount}")
              .sx(new CSSPropertiesUtils {
                fontWeight = "700"; fontSize = "13px"; color = amtColor
                fontFamily = "\"IBM Plex Mono\", monospace"
              })
          ),
          mui.TableCell.sx(new CSSPropertiesUtils { maxWidth = "280px" })(
            {
              val desc: ReactElement = mui
                .Typography(row.description)
                .sx(new CSSPropertiesUtils {
                  fontSize = "13px"; fontFamily = "\"IBM Plex Mono\", monospace"
                  overflow = "hidden"; textOverflow = "ellipsis"; whiteSpace = "nowrap"
                  display = "block"
                })
              if (row.description.nonEmpty) mui.Tooltip(row.description)(desc)
              else desc
            },
            if (row.txnType == "purchase") Fragment(): ReactElement
            else
              mui.Chip
                .label(row.txnType)
                .size("small")
                .sx(new CSSPropertiesUtils {
                  height = "18px"; fontSize = "11px"; marginTop = "2px";
                  backgroundColor = "var(--lc-tint)"; color = "var(--lc-text-2)"
                })
          ),
          mui.TableCell(categoryCell),
          mui.TableCell(matchCell),
          mui.TableCell.align("right")(
            mui.Box.sx(new CSSPropertiesUtils {
              display = "flex"; gap = "4px"
              justifyContent = "flex-end"; alignItems = "center"
            })(
              primaryAction,
              (mui.IconButton
                .normal()(
                  mui
                    .Typography("…")
                    .sx(new CSSPropertiesUtils { fontSize = "18px"; lineHeight = "1" })
                )
                .size("small")
                .onClick(e => setMenuAnchor(Some(e.currentTarget)))
                .build: ReactElement),
              menu
            )
          )
        )
    },
    (prev: Props, next: Props) => prev == next
  )
}
