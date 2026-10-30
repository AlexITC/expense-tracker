package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.bankstatements.{AiSuggestions, GetBankStatement}
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import slinky.core.facade.{Fragment, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}

import java.util.UUID

/** Side drawer hosting the per-row forms: create expense, create refund, link to an expense, or create a rule. */
object BankStatementReviewDrawer {
  import BankStatementReviewLogic.DrawerState

  def apply(props: Props): KeyAddingStage = component(props)

  case class Props(
      ctx: AppContext,
      stmtId: UUID,
      state: DrawerState,
      row: Option[GetBankStatement.StatementRowData],
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category],
      ruleList: List[ImportRuleMatcher.Rule],
      aiSuggestions: Option[AiSuggestions],
      onClose: () => Unit,
      onCancel: DrawerState => Unit,
      onExpenseCreated: (UUID, UUID) => Unit,
      onRefundCreated: (UUID, UUID) => Unit,
      onExpenseLinked: (UUID, UUID) => Unit,
      onRuleCreated: (UUID, ImportRuleMatcher.Rule, Option[GetMerchants.Response.Merchant]) => Unit,
      onNewEntities: (Option[ImportRuleMatcher.Rule], Option[GetMerchants.Response.Merchant]) => Unit
  )

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    def renderForm(row: GetBankStatement.StatementRowData, state: DrawerState): ReactElement = state match {
      case DrawerState.Closed => Fragment()
      case form: DrawerState.CreateExpense =>
        BankRowCreateExpenseForm(
          BankRowCreateExpenseForm.Props(
            stmtId = props.stmtId,
            row = row,
            merchants = props.merchants,
            categories = props.categories,
            ruleList = props.ruleList,
            aiSuggestion = props.aiSuggestions.flatMap(_.rows.find(_.rowId == row.id)),
            ctx = props.ctx,
            onSuccess = expenseId => props.onExpenseCreated(row.id, expenseId),
            onCancel = () => props.onCancel(form),
            onNewEntities = props.onNewEntities
          )
        )
      case form: DrawerState.CreateRefund =>
        BankRowCreateRefundForm(
          BankRowCreateRefundForm.Props(
            stmtId = props.stmtId,
            rowId = row.id,
            row = row,
            merchants = props.merchants,
            categories = props.categories,
            ruleList = props.ruleList,
            ctx = props.ctx,
            onSuccess = refundId => props.onRefundCreated(row.id, refundId),
            onCancel = () => props.onCancel(form)
          )
        )
      case form: DrawerState.LinkExpense =>
        BankRowLinkPicker(
          BankRowLinkPicker.Props(
            stmtId = props.stmtId,
            rowId = row.id,
            ctx = props.ctx,
            onSuccess = expenseId => props.onExpenseLinked(row.id, expenseId),
            onCancel = () => props.onCancel(form)
          )
        )
      case form: DrawerState.CreateRule =>
        BankRowRuleForm(
          BankRowRuleForm.Props(
            stmtId = props.stmtId,
            row = row,
            merchants = props.merchants,
            categories = props.categories,
            ctx = props.ctx,
            onSuccess = (rule, newMerchant) => props.onRuleCreated(row.id, rule, newMerchant),
            onCancel = () => props.onCancel(form)
          )
        )
    }

    mui.Drawer
      .anchor("right")
      .open(props.state != DrawerState.Closed)
      .onClose((_, _) => props.onClose())(
        mui.Box.sx(new CSSPropertiesUtils {
          width = "480px"; maxWidth = "92vw"; height = "100%"
          display = "flex"; flexDirection = "column"; padding = "20px"
        })(
          mui.Box.sx(new CSSPropertiesUtils {
            display = "flex"; alignItems = "center"; marginBottom = "16px"
          })(
            mui
              .Typography("Review transaction")
              .sx(new CSSPropertiesUtils { fontSize = "16px"; fontWeight = "600"; color = "var(--lc-text)" }),
            mui.Box.sx(new CSSPropertiesUtils { flex = "1" })(),
            (mui.IconButton
              .normal()(
                mui.Typography("×").sx(new CSSPropertiesUtils { fontSize = "22px" })
              )
              .onClick(_ => props.onClose())
              .build: ReactElement)
          ),
          props.row.fold[ReactElement](Fragment()) { row =>
            Fragment(
              BankStatementRowSummary(BankStatementRowSummary.Props(row)),
              renderForm(row, props.state)
            )
          }
        )
      )
  }
}
