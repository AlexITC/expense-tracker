package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.components.pages.BankStatementReviewRow.RowAction
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.core.SafeHooks
import net.wiringbits.utils.ReconciliationMatcher
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.compat.RouterCompat
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.useHistory
import org.scalajs.dom.URLSearchParams
import slinky.core.facade.{Fragment, Hooks, SetStateHookCallback}
import slinky.core.{FunctionalComponent, KeyAddingStage}

import java.util.UUID
import scala.concurrent.ExecutionContext

/** The review page body for one loaded statement. Its parent remounts it on every refresh, so state seeded from `data`
  * is always fresh.
  */
object BankStatementReviewContent {
  import BankStatementReviewLogic.*

  def apply(props: Props): KeyAddingStage = component(props)

  case class Props(
      ctx: AppContext,
      data: ReviewData,
      onRefresh: () => Unit,
      showToast: String => Unit,
      bulkIgnore: BulkRun,
      setBulkIgnore: SetStateHookCallback[BulkRun],
      bulkImport: BulkRun,
      setBulkImport: SetStateHookCallback[BulkRun]
  )

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val history = useHistory()
    val location = RouterCompat.useTypedLocation()
    val stmtId = props.data.statement.id
    val showToast = props.showToast
    val bulkIgnore = props.bulkIgnore
    val setBulkIgnore = props.setBulkIgnore
    val bulkImport = props.bulkImport
    val setBulkImport = props.setBulkImport

    val (statement, setStatement) = Hooks.useState(props.data.statement)
    val (statementRows, setStatementRows) = Hooks.useState(props.data.statement.statementRows)
    val (rowEdits, setRowEdits) = Hooks.useState[RowEdits](
      props.data.statement.statementRows.map(r => r.id -> (r.date.toString, r.amount.toString, r.description)).toMap
    )
    val (error, setError) = Hooks.useState("")

    val (merchantList, setMerchantList) = Hooks.useState(props.data.merchants)
    val (categoryList, setCategoryList) = Hooks.useState(props.data.categories)
    val (ruleList, setRuleList) = Hooks.useState(props.data.rules)
    // Kept current by `BankStatementRowActions.refreshExpenses` after single-row link changes; expense matching reads it.
    val (expenseList, setExpenseList) = Hooks.useState(props.data.expenses)

    val initialTab = {
      val params = new URLSearchParams(location.search)
      val t = params.get("tab")
      if (t == null || t.isEmpty) "ready" else t
    }
    val (rowFilter, setRowFilter) = Hooks.useState(initialTab)
    val (selectedRowIds, setSelectedRowIds) = Hooks.useState[Set[UUID]](Set.empty)

    val (drawerState, setDrawerState) = Hooks.useState[DrawerState](DrawerState.Closed)

    val suggestions = BankStatementSuggestionsPolling.useSuggestionsPolling(
      client = props.ctx.api.client,
      statement = statement,
      setStatement = setStatement,
      setError = setError,
      showToast = showToast
    )

    // Rule matching is the expensive part of rendering a row, so it only re-runs when its inputs change.
    val aiSuggestions = statement.aiSuggestions
    val rulesKey = ruleList.toString
    val merchantsKey = merchantList.toString
    val categoriesKey = categoryList.toString
    val aiKey = aiSuggestions.toString
    val rowsKey = statementRows.toString
    val expensesKey = expenseList.toString
    val effectiveRulesKey = s"$rulesKey|$merchantsKey"
    val rowInfoKey = s"$rowsKey|$effectiveRulesKey|$merchantsKey|$aiKey|$categoriesKey"
    // Explicit rules plus one virtual rule per merchant name; `ruleList` itself stays explicit-only.
    val effectiveRuleList: List[ImportRuleMatcher.Rule] = SafeHooks.useMemo(effectiveRulesKey)(() =>
      ImportRuleMatcher.effectiveRules(ruleList, merchantList.map(m => m.id -> m.name))
    )
    val rowInfoById: Map[UUID, RowInfo] = SafeHooks.useMemo(rowInfoKey)(() =>
      statementRows.map(r => r.id -> rowInfo(effectiveRuleList, merchantList, categoryList, aiSuggestions, r)).toMap
    )
    val unlinkedExpensesByDate = SafeHooks.useMemo(expensesKey)(() => ReconciliationMatcher.unlinkedByDate(expenseList))
    val expenseMatchById: Map[UUID, ReconciliationMatcher.ProposedAction] =
      SafeHooks.useMemo(rowsKey, rowInfoKey, expensesKey)(() =>
        expenseMatches(statementRows, rowInfoById, unlinkedExpensesByDate)
      )

    val rowActions = BankStatementRowActions(
      client = props.ctx.api.client,
      stmtId = stmtId,
      // Computed once from the loaded rows: no action on this page changes a row's date or the statement period
      // (row confirmation/editing is not exposed here), and a full refresh remounts with a fresh window.
      expenseWindow = candidateExpenseWindow(props.data.statement),
      setStatementRows = setStatementRows,
      setSelectedRowIds = setSelectedRowIds,
      setExpenseList = setExpenseList,
      setError = setError,
      showToast = showToast
    )

    // Stable across renders so the memoized rows only re-render when their own props change. They may only use setters
    // and values that never change for this statement.
    val onToggleSelected: (UUID, Boolean) => Unit = SafeHooks.useCallback()((rowId: UUID, checked: Boolean) =>
      if (checked) setSelectedRowIds(cur => cur + rowId) else setSelectedRowIds(cur => cur - rowId)
    )
    val onRowAction: (UUID, RowAction) => Unit =
      SafeHooks.useCallback(stmtId.toString)((rowId: UUID, action: RowAction) =>
        action match {
          case RowAction.OpenExpenseForm => setDrawerState(DrawerState.CreateExpense(rowId))
          case RowAction.OpenRefundForm => setDrawerState(DrawerState.CreateRefund(rowId))
          case RowAction.OpenLinkPicker => setDrawerState(DrawerState.LinkExpense(rowId))
          case RowAction.OpenRuleForm => setDrawerState(DrawerState.CreateRule(rowId))
          case RowAction.Ignore => rowActions.ignore(rowId)
          case RowAction.Unignore => rowActions.unignore(rowId)
          case RowAction.Unlink => rowActions.unlink(rowId)
          case RowAction.UnlinkRefund => rowActions.unlinkRefund(rowId)
        }
      )

    val drawerTarget =
      drawerState.openRowId.flatMap(id => statementRows.find(_.id == id)).map(row => (row, drawerState))

    val stmt = statement
    val tableRows = filterRows(statementRows, rowFilter, rowInfoById)
    val selectedInView = selectedRowIds.intersect(tableRows.map(_.id).toSet)
    val bulkActive = selectedInView.nonEmpty && !bulkIgnore.isRunning && !bulkImport.isRunning

    val countReady = statementRows.count(r => rowInfoById.get(r.id).exists(_.isReady))
    val countUnprocessed =
      statementRows.count(r => r.expenseId.isEmpty && r.refundId.isEmpty && r.ignoredAt.isEmpty)
    val countIgnored = statementRows.count(_.ignoredAt.isDefined)
    val countLinked = statementRows.count(r => r.expenseId.isDefined || r.refundId.isDefined)

    def handleTabChange(tab: String): Unit = {
      setRowFilter(tab)
      setSelectedRowIds(Set.empty)
      val pathname = location.pathname
      history.replace(s"$pathname?tab=$tab")
    }

    mui.Box.sx(new CSSPropertiesUtils {
      display = "flex"; flexDirection = "column"; height = "100%"; minHeight = "0"
    })(
      // ── Header + Tabs ──
      mui.Box.sx(new CSSPropertiesUtils { padding = "20px 24px 0 24px" })(
        BankStatementReviewHeader(
          BankStatementReviewHeader.Props(
            statement = stmt,
            rowCount = statementRows.size,
            regenerateDisabled = suggestions.regenerateDisabled,
            onRegenerate = suggestions.regenerate
          )
        ),
        BankStatementReviewTabs(
          BankStatementReviewTabs.Props(
            rowFilter = rowFilter,
            countReady = countReady,
            countUnprocessed = countUnprocessed,
            countIgnored = countIgnored,
            countLinked = countLinked,
            countAll = statementRows.size,
            onChange = handleTabChange
          )
        )
      ),
      // ── Bulk bar ──
      if (selectedInView.nonEmpty)
        BankStatementReviewBulkBar(
          BankStatementReviewBulkBar.Props(
            selectedCount = selectedInView.size,
            bulkActive = bulkActive,
            importRun = bulkImport,
            ignoreRun = bulkIgnore,
            onProcess = () =>
              BankStatementBulkRuns.handleBulkImport(
                client = props.ctx.api.client,
                stmtId = stmt.id,
                ids = selectedInView.toList,
                statementRows = statementRows,
                rowInfoById = rowInfoById,
                expenseMatchById = expenseMatchById,
                expenseList = expenseList,
                setStatementRows = setStatementRows,
                setBulkImport = setBulkImport,
                setSelectedRowIds = setSelectedRowIds,
                showToast = showToast,
                onRefresh = props.onRefresh
              ),
            onIgnore = () =>
              BankStatementBulkRuns.handleBulkIgnore(
                client = props.ctx.api.client,
                stmtId = stmt.id,
                ids = selectedInView.toList,
                setStatementRows = setStatementRows,
                setBulkIgnore = setBulkIgnore,
                setSelectedRowIds = setSelectedRowIds
              ),
            onClear = () => setSelectedRowIds(Set.empty)
          )
        )
      else Fragment(),
      // ── Error ──
      if (error.nonEmpty)
        mui.Box.sx(new CSSPropertiesUtils { padding = "8px 24px 0 24px" })(
          mui.Typography(error).sx(new CSSPropertiesUtils { color = "var(--lc-error)" })
        )
      else Fragment(),
      // ── Table ──
      mui.Box.sx(new CSSPropertiesUtils { flex = "1"; overflowY = "auto"; padding = "0 24px 24px 24px" })(
        BankStatementReviewTable(
          BankStatementReviewTable.Props(
            rows = tableRows,
            rowInfoById = rowInfoById,
            selectedRowIds = selectedRowIds,
            showCheckbox = rowFilter == "unprocessed" || rowFilter == "ready",
            pollingSuggestions = suggestions.polling,
            expenseMatchById = expenseMatchById,
            onSelectAll =
              checked => if (checked) setSelectedRowIds(tableRows.map(_.id).toSet) else setSelectedRowIds(Set.empty),
            onToggleSelected = onToggleSelected,
            onRowAction = onRowAction
          )
        ),
        if ((bulkImport.results ++ bulkIgnore.results).nonEmpty)
          BankStatementReviewBulkResults(
            BankStatementReviewBulkResults.Props(
              results = bulkImport.results ++ bulkIgnore.results,
              descById = statementRows.map(r => r.id -> r.description).toMap
            )
          )
        else Fragment()
      ),
      // ── Detail Drawer ──
      BankStatementReviewDrawer(
        BankStatementReviewDrawer.Props(
          ctx = props.ctx,
          stmtId = stmt.id,
          target = drawerTarget,
          merchants = merchantList,
          categories = categoryList,
          ruleList = ruleList,
          aiSuggestions = stmt.aiSuggestions,
          onClose = () => setDrawerState(DrawerState.Closed),
          onCancel = form => setDrawerState(_.closing(form)),
          onExpenseCreated = (rowId, expenseId) => {
            setStatementRows(cur => cur.map(r => if (r.id == rowId) r.copy(expenseId = Some(expenseId)) else r))
            setDrawerState(_.closing(DrawerState.CreateExpense(rowId)))
            rowActions.refreshRow(rowId)
            showToast("Expense created")
          },
          onRefundCreated = (rowId, refundId) => {
            setStatementRows(cur => cur.map(r => if (r.id == rowId) r.copy(refundId = Some(refundId)) else r))
            setDrawerState(_.closing(DrawerState.CreateRefund(rowId)))
            showToast("Refund created")
          },
          onExpenseLinked = (rowId, expenseId) => {
            setStatementRows(cur => cur.map(r => if (r.id == rowId) r.copy(expenseId = Some(expenseId)) else r))
            setDrawerState(_.closing(DrawerState.LinkExpense(rowId)))
            rowActions.refreshRow(rowId)
          },
          onRuleCreated = (rowId, rule, newMerchantOpt) => {
            setRuleList(cur => cur :+ rule)
            newMerchantOpt.foreach(m => setMerchantList(cur => cur :+ m))
            setDrawerState(_.closing(DrawerState.CreateRule(rowId)))
            showToast("Rule created")
          },
          onNewEntities = (newRule, newMerchant) => {
            newRule.foreach(r => setRuleList(cur => cur :+ r))
            newMerchant.foreach(m => setMerchantList(cur => cur :+ m))
          }
        )
      )
    )
  }
}
