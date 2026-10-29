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
  def apply(props: Props): KeyAddingStage = component(props)

  enum DrawerForm {
    case CreateExpense, CreateRefund, LinkExpense, CreateRule
  }

  /** The form to show, by precedence: creating → refund → linking → rule. */
  def activeForm(
      creating: Option[UUID],
      refund: Option[UUID],
      linking: Option[UUID],
      rule: Option[UUID]
  ): Option[(UUID, DrawerForm)] =
    creating
      .map(_ -> DrawerForm.CreateExpense)
      .orElse(refund.map(_ -> DrawerForm.CreateRefund))
      .orElse(linking.map(_ -> DrawerForm.LinkExpense))
      .orElse(rule.map(_ -> DrawerForm.CreateRule))

  case class Props(
      ctx: AppContext,
      stmtId: UUID,
      open: Boolean,
      target: Option[(GetBankStatement.StatementRowData, DrawerForm)],
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category],
      ruleList: List[ImportRuleMatcher.Rule],
      aiSuggestions: Option[AiSuggestions],
      onClose: () => Unit,
      onCancel: DrawerForm => Unit,
      onExpenseCreated: (UUID, UUID) => Unit,
      onRefundCreated: (UUID, UUID) => Unit,
      onExpenseLinked: (UUID, UUID) => Unit,
      onRuleCreated: (ImportRuleMatcher.Rule, Option[GetMerchants.Response.Merchant]) => Unit,
      onNewEntities: (Option[ImportRuleMatcher.Rule], Option[GetMerchants.Response.Merchant]) => Unit
  )

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    def renderForm(row: GetBankStatement.StatementRowData, form: DrawerForm): ReactElement = form match {
      case DrawerForm.CreateExpense =>
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
            onCancel = () => props.onCancel(DrawerForm.CreateExpense),
            onNewEntities = props.onNewEntities
          )
        )
      case DrawerForm.CreateRefund =>
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
            onCancel = () => props.onCancel(DrawerForm.CreateRefund)
          )
        )
      case DrawerForm.LinkExpense =>
        BankRowLinkPicker(
          BankRowLinkPicker.Props(
            stmtId = props.stmtId,
            rowId = row.id,
            ctx = props.ctx,
            onSuccess = expenseId => props.onExpenseLinked(row.id, expenseId),
            onCancel = () => props.onCancel(DrawerForm.LinkExpense)
          )
        )
      case DrawerForm.CreateRule =>
        BankRowRuleForm(
          BankRowRuleForm.Props(
            stmtId = props.stmtId,
            row = row,
            merchants = props.merchants,
            categories = props.categories,
            ctx = props.ctx,
            onSuccess = props.onRuleCreated,
            onCancel = () => props.onCancel(DrawerForm.CreateRule)
          )
        )
    }

    mui.Drawer
      .anchor("right")
      .open(props.open)
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
          props.target.fold[ReactElement](Fragment()) { case (row, form) =>
            Fragment(
              BankStatementRowSummary(BankStatementRowSummary.Props(row)),
              renderForm(row, form)
            )
          }
        )
      )
  }
}
