package net.wiringbits.components.pages.bankstatementreview

import com.olvind.mui.muiMaterial.components as mui
import monix.execution.Scheduler
import net.wiringbits.AppContext
import net.wiringbits.api.models.bankstatements.GetBankStatement
import net.wiringbits.components.pages.bankstatementreview.BankStatementReviewRow.RowAction
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.components.widgets.Loader
import net.wiringbits.core.{ReactiveHooks, SafeHooks}
import net.wiringbits.utils.ReconciliationMatcher
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.compat.RouterCompat
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.useHistory
import org.scalajs.dom.URLSearchParams
import slinky.core.facade.{Fragment, Hooks, ReactElement, SetStateHookCallback}
import slinky.core.{FunctionalComponent, KeyAddingStage}

import java.util.UUID
import scala.concurrent.ExecutionContext

/** The review page body for one statement. It loads the statement first and the support lists (merchants, categories,
  * rules, candidate expenses, unlinked receipts) after it, into a store that lives as long as this component; its
  * parent keys it by statement id, so another statement gets a fresh store.
  */
object BankStatementReviewContent {
  import BankStatementReviewLogic.*

  def apply(props: Props): KeyAddingStage = component(props)

  case class Props(
      ctx: AppContext,
      stmtId: UUID,
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
    val stmtId = props.stmtId
    val showToast = props.showToast
    val bulkIgnore = props.bulkIgnore
    val setBulkIgnore = props.setBulkIgnore
    val bulkImport = props.bulkImport
    val setBulkImport = props.setBulkImport

    val store = SafeHooks.useMemo(stmtId.toString)(() => new BankStatementReviewStore(using Scheduler.global))
    val (error, setError) = Hooks.useState("")

    SafeHooks.useEffect(stmtId.toString)(() => store.load(props.ctx.api.client, stmtId))

    val statementState = ReactiveHooks.useValue(store.$statement)
    val statementRows = ReactiveHooks.useValue(store.$rows)
    val supportState = ReactiveHooks.useValue(store.$support)
    val aiSuggestions = ReactiveHooks.useValue(store.$suggestions)

    // Until the support lists load, rows are shown without rule or expense matching and actions that need them are off.
    val support = supportState.toOption
    val merchantList = support.fold(Nil)(_.merchants)
    val categoryList = support.fold(Nil)(_.categories)
    val ruleList = support.fold(Nil)(_.rules)
    // Kept current by `BankStatementRowActions.refreshExpenses` after single-row link changes; expense matching reads it.
    val expenseList = support.fold(Nil)(_.expenses)
    val supportError = supportState match {
      case Loadable.Failed(message) => Some(message)
      case _ => None
    }

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
      stmtId = stmtId,
      enabled = statementState.toOption.isDefined,
      aiSuggestions = aiSuggestions,
      setAiSuggestions = store.setSuggestions,
      setError = setError,
      showToast = showToast
    )

    // Rule matching is the expensive part of rendering a row, so it only re-runs when its inputs change.
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

    val rowActionsOpt = statementState.toOption.map(stmt =>
      BankStatementRowActions(
        client = props.ctx.api.client,
        stmtId = stmtId,
        // Computed from the loaded statement: no action on this page changes a row's date or the statement period
        // (row confirmation/editing is not exposed here).
        expenseWindow = candidateExpenseWindow(stmt),
        updateRows = store.updateRows,
        setSelectedRowIds = setSelectedRowIds,
        setExpenses = es => store.updateSupport(_.withExpenses(es)),
        setError = setError,
        showToast = showToast
      )
    )

    // Stable across renders so the memoized rows only re-render when their own props change. They may only use setters
    // and values that never change for this statement; `onRowAction` changes when the statement and the support lists
    // load. The forms need the support lists, so they don't open before those are loaded.
    val onToggleSelected: (UUID, Boolean) => Unit = SafeHooks.useCallback()((rowId: UUID, checked: Boolean) =>
      if (checked) setSelectedRowIds(cur => cur + rowId) else setSelectedRowIds(cur => cur - rowId)
    )
    val onRowAction: (UUID, RowAction) => Unit =
      SafeHooks.useCallback(s"$stmtId:${rowActionsOpt.isDefined}:${support.isDefined}")(
        (rowId: UUID, action: RowAction) =>
          action match {
            case RowAction.OpenExpenseForm => if (support.isDefined) setDrawerState(DrawerState.CreateExpense(rowId))
            case RowAction.OpenRefundForm => if (support.isDefined) setDrawerState(DrawerState.CreateRefund(rowId))
            case RowAction.OpenLinkPicker => if (support.isDefined) setDrawerState(DrawerState.LinkExpense(rowId))
            case RowAction.OpenRuleForm => if (support.isDefined) setDrawerState(DrawerState.CreateRule(rowId))
            case RowAction.Ignore => rowActionsOpt.foreach(_.ignore(rowId))
            case RowAction.Unignore => rowActionsOpt.foreach(_.unignore(rowId))
            case RowAction.Unlink => rowActionsOpt.foreach(_.unlink(rowId))
            case RowAction.UnlinkRefund => rowActionsOpt.foreach(_.unlinkRefund(rowId))
          }
      )

    val drawerTarget =
      drawerState.openRowId.flatMap(id => statementRows.find(_.id == id)).map(row => (row, drawerState))

    val tableRows = filterRows(statementRows, rowFilter, rowInfoById)
    val selectedInView = selectedRowIds.intersect(tableRows.map(_.id).toSet)
    val bulkActive = bulkActionsEnabled(support.isDefined, selectedInView.size, bulkIgnore, bulkImport)

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

    def errorLine(message: String): ReactElement =
      mui.Box.sx(new CSSPropertiesUtils { padding = "8px 24px 0 24px" })(
        mui.Typography(message).sx(new CSSPropertiesUtils { color = "var(--lc-error)" })
      )

    def renderLoaded(stmt: GetBankStatement.Response, rowActions: BankStatementRowActions): ReactElement =
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
                  updateRows = store.updateRows,
                  setBulkImport = setBulkImport,
                  setSelectedRowIds = setSelectedRowIds,
                  showToast = showToast,
                  onFinished = () => rowActions.refreshExpenses()
                ),
              onIgnore = () =>
                BankStatementBulkRuns.handleBulkIgnore(
                  client = props.ctx.api.client,
                  stmtId = stmt.id,
                  ids = selectedInView.toList,
                  updateRows = store.updateRows,
                  setBulkIgnore = setBulkIgnore,
                  setSelectedRowIds = setSelectedRowIds
                ),
              onClear = () => setSelectedRowIds(Set.empty)
            )
          )
        else Fragment(),
        // ── Error ──
        supportError.fold[ReactElement](Fragment())(errorLine),
        if (error.nonEmpty) errorLine(error) else Fragment(),
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
        // ── Detail Drawer ── (its forms need the support lists)
        support.fold[ReactElement](Fragment()) { s =>
          BankStatementReviewDrawer(
            BankStatementReviewDrawer.Props(
              ctx = props.ctx,
              stmtId = stmt.id,
              target = drawerTarget,
              merchants = s.merchants,
              categories = s.categories,
              ruleList = s.rules,
              aiSuggestions = aiSuggestions,
              onClose = () => setDrawerState(DrawerState.Closed),
              onCancel = form => setDrawerState(_.closing(form)),
              onExpenseCreated = (rowId, expenseId) => {
                store.patchRow(rowId)(_.copy(expenseId = Some(expenseId)))
                setDrawerState(_.closing(DrawerState.CreateExpense(rowId)))
                rowActions.refreshRow(rowId)
                showToast("Expense created")
              },
              onRefundCreated = (rowId, refundId) => {
                store.patchRow(rowId)(_.copy(refundId = Some(refundId)))
                setDrawerState(_.closing(DrawerState.CreateRefund(rowId)))
                showToast("Refund created")
              },
              onExpenseLinked = (rowId, expenseId) => {
                store.patchRow(rowId)(_.copy(expenseId = Some(expenseId)))
                setDrawerState(_.closing(DrawerState.LinkExpense(rowId)))
                rowActions.refreshRow(rowId)
              },
              onRuleCreated = (rowId, rule, newMerchantOpt) => {
                store.updateSupport(_.withRule(rule))
                newMerchantOpt.foreach(m => store.updateSupport(_.withMerchant(m)))
                setDrawerState(_.closing(DrawerState.CreateRule(rowId)))
                showToast("Rule created")
              },
              onNewEntities = (newRule, newMerchant) => {
                newRule.foreach(r => store.updateSupport(_.withRule(r)))
                newMerchant.foreach(m => store.updateSupport(_.withMerchant(m)))
              }
            )
          )
        }
      )

    (statementState, rowActionsOpt) match {
      case (Loadable.Loaded(stmt), Some(rowActions)) => renderLoaded(stmt, rowActions)
      case (Loadable.Failed(message), _) => errorLine(message)
      case _ => Loader(props.ctx)
    }
  }
}
