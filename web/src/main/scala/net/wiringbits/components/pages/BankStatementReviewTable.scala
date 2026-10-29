package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.api.models.bankstatements.GetBankStatement.StatementRowData
import net.wiringbits.components.pages.BankStatementReviewLogic.RowInfo
import net.wiringbits.components.pages.BankStatementReviewRow.RowAction
import net.wiringbits.utils.ReconciliationMatcher
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import slinky.core.facade.{Fragment, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}

import java.util.UUID

/** The statement rows table: a select-all header plus one memoised row per filtered statement row. */
object BankStatementReviewTable {
  def apply(props: Props): KeyAddingStage = component(props)

  case class Props(
      rows: List[StatementRowData],
      rowInfoById: Map[UUID, RowInfo],
      selectedRowIds: Set[UUID],
      showCheckbox: Boolean,
      pollingSuggestions: Boolean,
      expenseMatchById: Map[UUID, ReconciliationMatcher.ProposedAction],
      onSelectAll: Boolean => Unit,
      onToggleSelected: (UUID, Boolean) => Unit,
      onRowAction: (UUID, RowAction) => Unit
  )

  private val hcell = new CSSPropertiesUtils {
    fontWeight = "700"; fontSize = "11px"; letterSpacing = "0.06em"
    textTransform = "uppercase"; color = "var(--lc-text-2)"
  }

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    val allSelected = props.rows.nonEmpty && props.rows.forall(r => props.selectedRowIds.contains(r.id))
    val someSelected = props.rows.exists(r => props.selectedRowIds.contains(r.id)) && !allSelected

    mui.Table.size("small")(
      mui.TableHead(
        mui.TableRow(
          mui.TableCell.padding("checkbox")(
            if (props.showCheckbox)
              (mui.Checkbox
                .checked(allSelected)
                .indeterminate(someSelected)
                .size("small")
                .onChange((_, checked) => props.onSelectAll(checked)): ReactElement)
            else Fragment()
          ),
          mui.TableCell.sx(hcell)("Date"),
          mui.TableCell.align("right").sx(hcell)("Amount"),
          mui.TableCell.sx(hcell)("Description"),
          mui.TableCell.sx(hcell)("Category"),
          mui.TableCell.sx(hcell)("Match"),
          mui.TableCell.align("right").sx(hcell)("")
        )
      ),
      if (props.rows.isEmpty)
        mui.TableBody(
          mui.TableRow(
            mui.TableCell.colSpan(7)(
              mui
                .Typography("No transactions in this view.")
                .sx(new CSSPropertiesUtils {
                  padding = "32px 0"; textAlign = "center"; color = "var(--lc-text-2)"; display = "block"
                })
            )
          )
        )
      else
        mui.TableBody(
          Fragment(
            props.rows.flatMap { row =>
              props.rowInfoById.get(row.id).map { info =>
                (BankStatementReviewRow(
                  BankStatementReviewRow.Props(
                    row = row,
                    info = info,
                    selected = props.selectedRowIds.contains(row.id),
                    showCheckbox = props.showCheckbox,
                    pollingSuggestions = props.pollingSuggestions,
                    linksToExpense = props.expenseMatchById.get(row.id).exists {
                      case ReconciliationMatcher.AutoLink(_) => true
                      case _ => false
                    },
                    onToggleSelected = props.onToggleSelected,
                    onAction = props.onRowAction
                  )
                ).withKey(row.id.toString): ReactElement)
              }
            }*
          )
        )
    )
  }
}
