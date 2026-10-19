package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.api.models.bankstatements.GetBankStatement
import net.wiringbits.api.utils.RowRuleVerdict
import net.wiringbits.components.pages.BankStatementReviewPage.{PrimaryAction, RowInfo, primaryActionFor}
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
    case OpenExpenseForm, OpenRefundForm, OpenLinkPicker, OpenRuleForm, Ignore, Unignore
  }

  case class Props(
      row: GetBankStatement.StatementRowData,
      info: RowInfo,
      selected: Boolean,
      showCheckbox: Boolean,
      pollingSuggestions: Boolean,
      onToggleSelected: (UUID, Boolean) => Unit,
      onAction: (UUID, RowAction) => Unit
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
        primaryActionFor(row, info.isReady, info.verdict) match {
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

      def dash: ReactElement =
        mui.Typography("—").sx(new CSSPropertiesUtils { color = "var(--lc-hint)"; fontSize = "13px" })

      def suggestingOrDash: ReactElement =
        if (props.pollingSuggestions && row.ignoredAt.isEmpty && row.amount < 0)
          mui.Typography("suggesting…").sx(new CSSPropertiesUtils { color = "var(--lc-hint)"; fontSize = "13px" })
        else dash

      // Merchant linked on import — rule-matched or AI-suggested; "Linked"/"→ Ignore" take over when they apply.
      def matchCell: ReactElement =
        if (row.expenseId.isDefined || row.refundId.isDefined) tintChip("Linked", "var(--lc-brand)")
        else if (info.verdict == RowRuleVerdict.Ignore)
          tintChip("→ Ignore", "var(--lc-brand-dark)")
        else
          info.merchantName match {
            case Some(name) => tintChip(name, "var(--lc-brand-dark)")
            case None => suggestingOrDash
          }

      // Category the expense will get on import, tagged "AI" when it came from a suggestion rather than a rule.
      def categoryCell: ReactElement =
        if (row.expenseId.isDefined || row.refundId.isDefined) dash
        else if (info.verdict == RowRuleVerdict.Ignore) dash
        else
          info.resolved match {
            case Some(res) =>
              mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "4px"; alignItems = "center" })(
                tintChip(info.categoryName.getOrElse(""), "var(--lc-brand-dark)"),
                if (res.categoryFromAi)
                  (mui
                    .Typography("AI")
                    .sx(new CSSPropertiesUtils {
                      fontSize = "10px"; fontWeight = "700"; color = "var(--lc-hint)"; letterSpacing = "0.5px"
                    }): ReactElement)
                else Fragment()
              )
            case None => suggestingOrDash
          }

      def menu: ReactElement =
        menuAnchor.fold[ReactElement](Fragment()) { anchor =>
          def closeMenuAnd(action: RowAction): Unit = { setMenuAnchor(None); act(action) }
          mui
            .Menu(open = true)
            .anchorEl(anchor)
            // Menu types onClose as js.Any, so hand it a typed JS function.
            .onClose((((_, _) => setMenuAnchor(None)): js.Function2[js.Any, js.Any, Unit]))(
              mui.MenuItem
                .normal()("Link to existing expense")
                .onClick(_ => closeMenuAnd(RowAction.OpenLinkPicker))
                .build,
              mui.MenuItem.normal()("Create import rule").onClick(_ => closeMenuAnd(RowAction.OpenRuleForm)).build,
              mui.MenuItem.normal()("Ignore").onClick(_ => closeMenuAnd(RowAction.Ignore)).build
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
