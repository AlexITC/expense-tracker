package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.bankstatements.{CreateExpenseFromRow, LinkRowToExpense}
import net.wiringbits.components.pages.BankStatementReviewDrawer.DrawerForm
import net.wiringbits.components.pages.BankStatementReviewRow.RowAction
import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.utils.ReconciliationMatcher
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.compat.RouterCompat
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.useHistory
import org.scalajs.dom
import org.scalajs.dom.URLSearchParams
import slinky.core.facade.{Fragment, Hooks, SetStateHookCallback}
import slinky.core.{FunctionalComponent, KeyAddingStage}

import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.util.{Failure, Success}

/** The review page body for one loaded statement. Its parent remounts it on every refresh, so state seeded from `data`
  * is always fresh.
  */
object BankStatementReviewContent {
  import BankStatementReviewLogic.*
  import BankStatementReviewPage.*

  def apply(props: Props): KeyAddingStage = component(props)

  case class Props(
      ctx: AppContext,
      data: ReviewData,
      onRefresh: () => Unit,
      showToast: String => Unit,
      bulkIgnoreProgress: BulkProgress,
      setBulkIgnoreProgress: SetStateHookCallback[BulkProgress],
      bulkIgnoreResults: BulkResults,
      setBulkIgnoreResults: SetStateHookCallback[BulkResults],
      bulkImportProgress: BulkProgress,
      setBulkImportProgress: SetStateHookCallback[BulkProgress],
      bulkImportResults: BulkResults,
      setBulkImportResults: SetStateHookCallback[BulkResults]
  )

  // Statements confirmed before AI suggestions existed stay NULL forever, so polling is capped.
  private val SuggestionsPollMaxAttempts = 20
  private val SuggestionsPollIntervalMs = 3000

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val history = useHistory()
    val location = RouterCompat.useTypedLocation()
    val stmtId = props.data.statement.id
    val showToast = props.showToast
    val bulkIgnoreProgress = props.bulkIgnoreProgress
    val setBulkIgnoreProgress = props.setBulkIgnoreProgress
    val bulkIgnoreResults = props.bulkIgnoreResults
    val setBulkIgnoreResults = props.setBulkIgnoreResults
    val bulkImportProgress = props.bulkImportProgress
    val setBulkImportProgress = props.setBulkImportProgress
    val bulkImportResults = props.bulkImportResults
    val setBulkImportResults = props.setBulkImportResults

    val (statement, setStatement) = Hooks.useState(props.data.statement)
    val (statementRows, setStatementRows) = Hooks.useState(props.data.statement.statementRows)
    val (rowEdits, setRowEdits) = Hooks.useState[RowEdits](
      props.data.statement.statementRows.map(r => r.id -> (r.date.toString, r.amount.toString, r.description)).toMap
    )
    val (error, setError) = Hooks.useState("")

    val (merchantList, setMerchantList) = Hooks.useState(props.data.merchants)
    val (categoryList, setCategoryList) = Hooks.useState(props.data.categories)
    val (ruleList, setRuleList) = Hooks.useState(props.data.rules)
    // Kept current by `refreshExpenses` after single-row link changes; expense matching reads it.
    val (expenseList, setExpenseList) = Hooks.useState(props.data.expenses)

    val initialTab = {
      val params = new URLSearchParams(location.search)
      val t = params.get("tab")
      if (t == null || t.isEmpty) Tab.Ready else Tab.parse(t)
    }
    val (rowFilter, setRowFilter) = Hooks.useState(initialTab)
    val (selectedRowIds, setSelectedRowIds) = Hooks.useState[Set[UUID]](Set.empty)

    val (creatingForRow, setCreatingForRow) = Hooks.useState[Option[UUID]](None)
    val (linkingForRow, setLinkingForRow) = Hooks.useState[Option[UUID]](None)
    val (creatingRefundForRow, setCreatingRefundForRow) = Hooks.useState[Option[UUID]](None)
    val (ruleFormForRow, setRuleFormForRow) = Hooks.useState[Option[UUID]](None)

    // Poll for AI suggestions until they are resolved or the attempts cap is reached
    val (suggestionsPollAttempts, setSuggestionsPollAttempts) = Hooks.useState(0)
    val (regeneratingSuggestions, setRegeneratingSuggestions) = Hooks.useState(false)
    val regenerateSuggestionsDisabled = regeneratingSuggestions ||
      !canRegenerateSuggestions(statement.aiSuggestions, suggestionsPollAttempts >= SuggestionsPollMaxAttempts)
    val pollingSuggestions =
      suggestionsPending(statement.aiSuggestions) && suggestionsPollAttempts < SuggestionsPollMaxAttempts
    val suggestionsPollKey = s"$stmtId:$pollingSuggestions"
    Hooks.useEffect(
      () => {
        if (pollingSuggestions) {
          val timer = dom.window.setInterval(
            () => {
              setSuggestionsPollAttempts(_ + 1)
              props.ctx.api.client.getBankStatement(stmtId).onComplete {
                case Success(full) => setStatement(cur => cur.copy(aiSuggestions = full.aiSuggestions))
                case Failure(_) => ()
              }
            },
            SuggestionsPollIntervalMs
          )
          () => { dom.window.clearInterval(timer); () }
        } else () => ()
      },
      js.Array(suggestionsPollKey)
    )

    // Rule matching is the expensive part of rendering a row, so it only re-runs when its inputs change.
    val aiSuggestions = statement.aiSuggestions
    // Explicit rules plus one virtual rule per merchant name; `ruleList` itself stays explicit-only.
    val effectiveRuleList: List[ImportRuleMatcher.Rule] = Hooks.useMemo(
      () => ImportRuleMatcher.effectiveRules(ruleList, merchantList.map(m => m.id -> m.name)),
      List(ruleList, merchantList)
    )
    val rowInfoById: Map[UUID, RowInfo] = Hooks.useMemo(
      () =>
        statementRows.map(r => r.id -> rowInfo(effectiveRuleList, merchantList, categoryList, aiSuggestions, r)).toMap,
      List(statementRows, effectiveRuleList, merchantList, aiSuggestions, categoryList)
    )
    val unlinkedExpensesByDate = Hooks.useMemo(
      () => ReconciliationMatcher.unlinkedByDate(expenseList),
      List(expenseList)
    )
    val expenseMatchById: Map[UUID, ReconciliationMatcher.ProposedAction] = Hooks.useMemo(
      () => expenseMatches(statementRows, rowInfoById, unlinkedExpensesByDate),
      List(statementRows, rowInfoById, unlinkedExpensesByDate)
    )
    def handleIgnore(stmtId: UUID, rowId: UUID): Unit =
      props.ctx.api.client.ignoreRow(stmtId, rowId).onComplete {
        case Success(_) =>
          setError("")
          setSelectedRowIds(cur => cur - rowId)
          setStatementRows(cur => cur.map(r => if (r.id == rowId) r.copy(ignoredAt = Some(Instant.now())) else r))
          showToast("Row ignored")
        case Failure(ex) => setError(ex.getMessage)
      }

    // Setting Pending and resetting the attempts re-enables the poll effect above
    def handleRegenerateSuggestions(stmtId: UUID): Unit = {
      setRegeneratingSuggestions(true)
      props.ctx.api.client.regenerateBankStatementAiSuggestions(stmtId).onComplete {
        case Success(resp) =>
          setError("")
          setStatement(cur => cur.copy(aiSuggestions = Some(resp.aiSuggestions)))
          setSuggestionsPollAttempts(0)
          setRegeneratingSuggestions(false)
          showToast("Regenerating AI suggestions…")
        case Failure(ex) =>
          setError(ex.getMessage)
          setRegeneratingSuggestions(false)
      }
    }

    def handleUnignore(stmtId: UUID, rowId: UUID): Unit =
      props.ctx.api.client.unignoreRow(stmtId, rowId).onComplete {
        case Success(_) =>
          setError("")
          setStatementRows(cur => cur.map(r => if (r.id == rowId) r.copy(ignoredAt = None) else r))
        case Failure(ex) => setError(ex.getMessage)
      }

    def handleUnlink(stmtId: UUID, rowId: UUID): Unit =
      props.ctx.api.client.unlinkRow(stmtId, rowId).onComplete {
        case Success(_) =>
          setError("")
          setStatementRows(cur =>
            cur.map(r =>
              if (r.id == rowId) r.copy(expenseId = None, expenseMerchantId = None, expenseCategoryId = None) else r
            )
          )
          // The expense is unlinked again, so keep the expense list current.
          refreshExpenses()
        case Failure(ex) => setError(ex.getMessage)
      }

    def handleUnlinkRefund(stmtId: UUID, rowId: UUID): Unit =
      props.ctx.api.client.unlinkRowRefund(stmtId, rowId).onComplete {
        case Success(_) =>
          setError("")
          setStatementRows(cur => cur.map(r => if (r.id == rowId) r.copy(refundId = None) else r))
        case Failure(ex) => setError(ex.getMessage)
      }

    // The drawer forms only report the new expense id, so re-read the row to get the expense's merchant and category.
    // Single-row changes refresh just that row and the expense list, so the page is not remounted.
    def refreshRow(rowId: UUID): Unit = {
      props.ctx.api.client.getBankStatement(stmtId).onComplete {
        case Success(full) =>
          full.statementRows.find(_.id == rowId).foreach { fresh =>
            setStatementRows(cur => cur.map(r => if (r.id == rowId) fresh else r))
          }
        case Failure(ex) => setError(ex.getMessage)
      }
      refreshExpenses()
    }

    def refreshExpenses(): Unit =
      props.ctx.api.client.getExpenses.onComplete {
        case Success(resp) => setExpenseList(resp.data)
        case Failure(ex) => setError(ex.getMessage)
      }

    def handleBulkIgnore(stmtId: UUID, ids: List[UUID]): Unit = {
      val total = ids.length
      setBulkIgnoreProgress(Some((0, total, 0)))
      setBulkIgnoreResults(Nil)
      def loop(remaining: List[UUID], done: Int, failed: Int): Unit =
        remaining match {
          case Nil => setBulkIgnoreProgress(None); setSelectedRowIds(Set.empty)
          case rowId :: rest =>
            props.ctx.api.client.ignoreRow(stmtId, rowId).onComplete {
              case Success(_) =>
                setStatementRows(cur => cur.map(r => if (r.id == rowId) r.copy(ignoredAt = Some(Instant.now())) else r))
                setBulkIgnoreResults(cur => cur :+ (rowId -> Right(())))
                setBulkIgnoreProgress(Some((done + 1, total, failed)))
                loop(rest, done + 1, failed)
              case Failure(ex) =>
                setBulkIgnoreResults(cur => cur :+ (rowId -> Left(ex.getMessage)))
                setBulkIgnoreProgress(Some((done + 1, total, failed + 1)))
                loop(rest, done + 1, failed + 1)
            }
        }
      loop(ids, 0, 0)
    }

    // Matches are taken from this render; expenses linked during a bulk run are tracked in the loop instead.
    def handleBulkImport(stmtId: UUID, ids: List[UUID]): Unit = {
      val total = ids.length
      setBulkImportProgress(Some((0, total, 0)))
      setBulkImportResults(Nil)
      final case class Counts(linked: Int, created: Int, ignored: Int, failed: Int) {
        def done: Int = linked + created + ignored + failed
      }
      def loop(remaining: List[UUID], counts: Counts, linkedThisRun: Set[UUID]): Unit =
        remaining match {
          case Nil =>
            setBulkImportProgress(None)
            setSelectedRowIds(Set.empty)
            showToast(bulkImportSummary(counts.linked, counts.created, counts.ignored, counts.failed))
            props.onRefresh()
          case rowId :: rest =>
            def advance(outcome: Either[String, Unit], next: Counts, nextLinked: Set[UUID]): Unit = {
              setBulkImportResults(cur => cur :+ (rowId -> outcome))
              setBulkImportProgress(Some((next.done, total, next.failed)))
              loop(rest, next, nextLinked)
            }
            def succeed(next: Counts): Unit = advance(Right(()), next, linkedThisRun)
            def fail(message: String): Unit =
              advance(Left(message), counts.copy(failed = counts.failed + 1), linkedThisRun)
            statementRows.find(_.id == rowId) match {
              case None => fail("Row not found")
              case Some(row) =>
                bulkRowDecision(rowInfoById.get(rowId), expenseMatchById.get(rowId), linkedThisRun) match {
                  case BulkRowDecision.Fail(message) => fail(message)
                  case BulkRowDecision.Ignore =>
                    props.ctx.api.client.ignoreRow(stmtId, rowId).onComplete {
                      case Success(_) =>
                        setStatementRows(cur =>
                          cur.map(r => if (r.id == rowId) r.copy(ignoredAt = Some(Instant.now())) else r)
                        )
                        succeed(counts.copy(ignored = counts.ignored + 1))
                      case Failure(ex) => fail(ex.getMessage)
                    }
                  case BulkRowDecision.Link(expenseId) =>
                    props.ctx.api.client
                      .linkRowToExpense(stmtId, rowId, LinkRowToExpense.Request(expenseId))
                      .onComplete {
                        case Success(_) =>
                          val expense = expenseList.find(_.id == expenseId)
                          // The server only reports a category for single-split expenses.
                          val categoryId = expense.flatMap(_.splits match {
                            case List(single) => Some(single.categoryId)
                            case _ => None
                          })
                          setStatementRows(cur =>
                            cur.map(r =>
                              if (r.id == rowId)
                                r.copy(
                                  expenseId = Some(expenseId),
                                  expenseMerchantId = expense.flatMap(_.merchantId),
                                  expenseCategoryId = categoryId
                                )
                              else r
                            )
                          )
                          advance(Right(()), counts.copy(linked = counts.linked + 1), linkedThisRun + expenseId)
                        case Failure(ex) => fail(ex.getMessage)
                      }
                  case BulkRowDecision.Create(res) =>
                    val req = CreateExpenseFromRow.Request(
                      amount = row.amount.abs,
                      date = row.date,
                      time = None,
                      merchantId = res.merchantId,
                      notes = Some(row.description).filter(_.nonEmpty),
                      splits = List(CreateExpenseFromRow.Split(res.categoryId, row.amount.abs)),
                      receiptId = None
                    )
                    props.ctx.api.client.createExpenseFromRow(stmtId, rowId, req).onComplete {
                      case Success(resp) =>
                        setStatementRows(cur =>
                          cur.map(r =>
                            if (r.id == rowId)
                              r.copy(
                                expenseId = Some(resp.expenseId),
                                expenseMerchantId = res.merchantId,
                                expenseCategoryId = Some(res.categoryId)
                              )
                            else r
                          )
                        )
                        succeed(counts.copy(created = counts.created + 1))
                      case Failure(ex) => fail(ex.getMessage)
                    }
                }
            }
        }
      loop(ids, Counts(0, 0, 0, 0), Set.empty)
    }

    // Stable across renders so the memoized rows only re-render when their own props change. They may only use setters
    // and values that never change for this statement.
    val onToggleSelected: (UUID, Boolean) => Unit = Hooks.useCallback(
      (rowId: UUID, checked: Boolean) =>
        if (checked) setSelectedRowIds(cur => cur + rowId) else setSelectedRowIds(cur => cur - rowId),
      ""
    )
    val onRowAction: (UUID, RowAction) => Unit = Hooks.useCallback(
      (rowId: UUID, action: RowAction) =>
        action match {
          case RowAction.OpenExpenseForm => setCreatingForRow(Some(rowId)); setLinkingForRow(None)
          case RowAction.OpenRefundForm => setCreatingRefundForRow(Some(rowId))
          case RowAction.OpenLinkPicker => setLinkingForRow(Some(rowId)); setCreatingForRow(None)
          case RowAction.OpenRuleForm => setRuleFormForRow(Some(rowId))
          case RowAction.Ignore => handleIgnore(stmtId, rowId)
          case RowAction.Unignore => handleUnignore(stmtId, rowId)
          case RowAction.Unlink => handleUnlink(stmtId, rowId)
          case RowAction.UnlinkRefund => handleUnlinkRefund(stmtId, rowId)
        },
      List(stmtId.toString)
    )

    val drawerOpen =
      creatingForRow.isDefined || creatingRefundForRow.isDefined ||
        linkingForRow.isDefined || ruleFormForRow.isDefined
    val drawerTarget = BankStatementReviewDrawer
      .activeForm(creatingForRow, creatingRefundForRow, linkingForRow, ruleFormForRow)
      .flatMap { case (id, form) => statementRows.find(_.id == id).map(_ -> form) }

    def closeDrawer(): Unit = {
      setCreatingForRow(None); setCreatingRefundForRow(None)
      setLinkingForRow(None); setRuleFormForRow(None)
    }

    val stmt = statement
    val tableRows = filterRows(statementRows, rowFilter, rowInfoById)
    val selectedInView = selectedRowIds.intersect(tableRows.map(_.id).toSet)
    val bulkActive = selectedInView.nonEmpty && bulkIgnoreProgress.isEmpty && bulkImportProgress.isEmpty

    val counts = tabCounts(statementRows, rowInfoById)

    def handleTabChange(tab: Tab): Unit = {
      setRowFilter(tab)
      setSelectedRowIds(Set.empty)
      val pathname = location.pathname
      history.replace(s"$pathname?tab=${tab.key}")
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
            regenerateDisabled = regenerateSuggestionsDisabled,
            onRegenerate = () => handleRegenerateSuggestions(stmt.id)
          )
        ),
        BankStatementReviewTabs(
          BankStatementReviewTabs.Props(rowFilter = rowFilter, counts = counts, onChange = handleTabChange)
        )
      ),
      // ── Bulk bar ──
      if (selectedInView.nonEmpty)
        BankStatementReviewBulkBar(
          BankStatementReviewBulkBar.Props(
            selectedCount = selectedInView.size,
            bulkActive = bulkActive,
            importProgress = bulkImportProgress,
            ignoreProgress = bulkIgnoreProgress,
            onProcess = () => handleBulkImport(stmt.id, selectedInView.toList),
            onIgnore = () => handleBulkIgnore(stmt.id, selectedInView.toList),
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
            showCheckbox = rowFilter == Tab.Unprocessed || rowFilter == Tab.Ready,
            pollingSuggestions = pollingSuggestions,
            expenseMatchById = expenseMatchById,
            onSelectAll =
              checked => if (checked) setSelectedRowIds(tableRows.map(_.id).toSet) else setSelectedRowIds(Set.empty),
            onToggleSelected = onToggleSelected,
            onRowAction = onRowAction
          )
        ),
        if (bulkImportResults.nonEmpty || bulkIgnoreResults.nonEmpty)
          BankStatementReviewBulkResults(
            BankStatementReviewBulkResults.Props(
              results = bulkImportResults ++ bulkIgnoreResults,
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
          open = drawerOpen,
          target = drawerTarget,
          merchants = merchantList,
          categories = categoryList,
          ruleList = ruleList,
          aiSuggestions = stmt.aiSuggestions,
          onClose = () => closeDrawer(),
          onCancel = {
            case DrawerForm.CreateExpense => setCreatingForRow(None)
            case DrawerForm.CreateRefund => setCreatingRefundForRow(None)
            case DrawerForm.LinkExpense => setLinkingForRow(None)
            case DrawerForm.CreateRule => setRuleFormForRow(None)
          },
          onExpenseCreated = (rowId, expenseId) => {
            setStatementRows(cur => cur.map(r => if (r.id == rowId) r.copy(expenseId = Some(expenseId)) else r))
            setCreatingForRow(None)
            refreshRow(rowId)
            showToast("Expense created")
          },
          onRefundCreated = (rowId, refundId) => {
            setStatementRows(cur => cur.map(r => if (r.id == rowId) r.copy(refundId = Some(refundId)) else r))
            setCreatingRefundForRow(None)
            showToast("Refund created")
          },
          onExpenseLinked = (rowId, expenseId) => {
            setStatementRows(cur => cur.map(r => if (r.id == rowId) r.copy(expenseId = Some(expenseId)) else r))
            setLinkingForRow(None)
            refreshRow(rowId)
          },
          onRuleCreated = (rule, newMerchantOpt) => {
            setRuleList(cur => cur :+ rule)
            newMerchantOpt.foreach(m => setMerchantList(cur => cur :+ m))
            setRuleFormForRow(None)
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
