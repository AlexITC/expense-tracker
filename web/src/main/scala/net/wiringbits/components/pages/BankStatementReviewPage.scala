package net.wiringbits.components.pages

import com.olvind.mui.csstype.mod.Property.FlexDirection
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.bankstatements.{
  AiSuggestions,
  AiSuggestionsStatus,
  ConfirmBankStatementRow,
  CreateExpenseFromRow,
  GetBankStatement,
  IgnoreRow,
  UnignoreRow
}
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.api.models.merchants.{GetImportRules, GetMerchants}
import net.wiringbits.components.pages.BankStatementReviewRow.RowAction
import net.wiringbits.api.utils.{ImportRuleMatcher, RowRuleVerdict}
import net.wiringbits.components.widgets.Loader
import net.wiringbits.models.User
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.components.core.AsyncComponent
import net.wiringbits.webapp.utils.slinkyUtils.components.core.widgets.Container
import net.wiringbits.webapp.utils.slinkyUtils.compat.RouterCompat
import net.wiringbits.webapp.utils.slinkyUtils.core.GenericHooks
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.{useHistory, useParams}
import org.scalajs.dom
import org.scalajs.dom.URLSearchParams
import slinky.core.facade.{Fragment, Hooks, ReactElement, SetStateHookCallback}
import slinky.core.{FunctionalComponent, KeyAddingStage}

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.util.{Failure, Success, Try}

object BankStatementReviewPage {
  def apply(ctx: AppContext, user: User): KeyAddingStage =
    component(Props(ctx = ctx, user = user))

  case class Props(ctx: AppContext, user: User)

  type RowEdits = Map[UUID, (String, String, String)]

  /** A one-line summary of the statement metadata. */
  def statementMetadataLine(stmt: GetBankStatement.Response): ReactElement = {
    val productType = stmt.productType match {
      case "credit" => "Type: Credit card"
      case "debit" => "Type: Debit account"
      case other => s"Type: $other"
    }
    val parts = List(
      s"Bank: ${stmt.bankName}",
      productType,
      s"Currency: ${stmt.currency}",
      s"Account: ••••${stmt.accountLast4}",
      s"Period: ${stmt.periodStart} – ${stmt.periodEnd}"
    )
    mui
      .Typography(parts.mkString("    ·    "))
      .sx(new CSSPropertiesUtils { fontSize = "13px"; color = "var(--lc-text-2)"; marginBottom = "8px" })
  }

  def resultLabel(
      rowId: UUID,
      descById: Map[UUID, String],
      outcome: Either[String, Unit]
  ): String = {
    val desc = descById.getOrElse(rowId, rowId.toString.take(8))
    outcome match {
      case Right(_) => s"✓ $desc"
      case Left(err) => s"✗ $desc: $err"
    }
  }

  /** AI suggestions are still being computed: not written yet (None) or explicitly Pending. */
  def suggestionsPending(ai: Option[AiSuggestions]): Boolean =
    ai.forall(_.status == AiSuggestionsStatus.Pending)

  /** Suggestions can be regenerated unless a computation is still Pending within the poll window; None (never
    * generated) is allowed, and so is a Pending that outlived the poll window (likely a stuck or lost job).
    */
  def canRegenerateSuggestions(ai: Option[AiSuggestions], pollAttemptsExhausted: Boolean): Boolean =
    pollAttemptsExhausted || !ai.exists(_.status == AiSuggestionsStatus.Pending)

  /** A row's resolved import target: the merchant to link (if any), the category the expense will get, and whether that
    * category came from an AI suggestion (true) rather than a matched rule (false): a SetMerchant rule's merchant
    * default category, or a SetCategory rule's category.
    */
  final case class ResolvedImport(merchantId: Option[UUID], categoryId: UUID, categoryFromAi: Boolean)

  /** Resolve what importing a row would create. `ruleList` must already be the effective rules
    * (`ImportRuleMatcher.effectiveRules`), so a merchant-name match counts as a SetMerchant rule. Merchant: a
    * SetMerchant rule's merchant, else the AI-suggested merchant. Category: that rule-merchant's default category, else
    * the AI-suggested category. A SetCategory rule resolves to its category with no merchant, and AI is not consulted
    * for such rows. An Ignore rule takes precedence over any suggestion, so such rows resolve to None (they are
    * ignored, not imported). None also means "nothing to import yet" — no rule and no usable suggestion. AI suggestions
    * are only consulted once Done.
    */
  def resolveImport(
      ruleList: List[ImportRuleMatcher.Rule],
      merchants: List[GetMerchants.Response.Merchant],
      aiSuggestions: Option[AiSuggestions],
      row: GetBankStatement.StatementRowData
  ): Option[ResolvedImport] =
    resolveImport(RowRuleVerdict.verdict(ruleList, row.description), merchants, aiSuggestions, row)

  /** Same as above, for a verdict the caller already computed for this row. */
  def resolveImport(
      verdict: RowRuleVerdict.Verdict,
      merchants: List[GetMerchants.Response.Merchant],
      aiSuggestions: Option[AiSuggestions],
      row: GetBankStatement.StatementRowData
  ): Option[ResolvedImport] =
    verdict match {
      case RowRuleVerdict.Ignore => None
      case RowRuleVerdict.SetCategory(categoryId) =>
        Some(ResolvedImport(merchantId = None, categoryId = categoryId, categoryFromAi = false))
      case _ =>
        val ruleMerchantId = RowRuleVerdict.merchantId(verdict)
        val ruleCategoryId = ruleMerchantId.flatMap(mid => merchants.find(_.id == mid).flatMap(_.categoryId))
        val ai = aiSuggestions
          .filter(_.status == AiSuggestionsStatus.Done)
          .flatMap(_.rows.find(_.rowId == row.id))
        val merchantId = ruleMerchantId.orElse(ai.flatMap(_.merchantId))
        ruleCategoryId
          .map(c => ResolvedImport(merchantId, c, categoryFromAi = false))
          .orElse(ai.flatMap(_.categoryId).map(c => ResolvedImport(merchantId, c, categoryFromAi = true)))
    }

  /** Everything a table row derives from the rules, merchants, categories and AI suggestions, computed once per row so
    * rendering does not re-run the rule matcher. The names are the display labels for the resolved import, if any.
    */
  final case class RowInfo(
      verdict: RowRuleVerdict.Verdict,
      resolved: Option[ResolvedImport],
      isReady: Boolean,
      merchantName: Option[String],
      categoryName: Option[String],
      linkedMerchantName: Option[String],
      linkedCategory: LinkedCategory
  )

  /** `ruleList` must already be the effective rules (`ImportRuleMatcher.effectiveRules`). */
  def rowInfo(
      ruleList: List[ImportRuleMatcher.Rule],
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category],
      aiSuggestions: Option[AiSuggestions],
      row: GetBankStatement.StatementRowData
  ): RowInfo = {
    val verdict = RowRuleVerdict.verdict(ruleList, row.description)
    val resolved = resolveImport(verdict, merchants, aiSuggestions, row)
    val isReady = row.expenseId.isEmpty && row.refundId.isEmpty && row.ignoredAt.isEmpty && row.amount < 0 &&
      (verdict == RowRuleVerdict.Ignore || resolved.isDefined)
    RowInfo(
      verdict = verdict,
      resolved = resolved,
      isReady = isReady,
      merchantName = resolved.flatMap(_.merchantId).flatMap(mid => merchants.find(_.id == mid)).map(_.name),
      categoryName = resolved.flatMap(res => categories.find(_.id == res.categoryId)).map(_.name),
      linkedMerchantName = linkedMerchantName(row, merchants),
      linkedCategory = linkedCategoryDisplay(row, categories)
    )
  }

  /** What the Category cell shows for a linked row. The server only reports a category for single-split expenses, so a
    * linked expense without one is shown as Multiple; Unknown covers ids not loaded yet and refund-only links.
    */
  enum LinkedCategory {
    case Named(name: String)
    case Multiple
    case Unknown
  }

  def linkedCategoryDisplay(
      row: GetBankStatement.StatementRowData,
      categories: List[GetCategories.Response.Category]
  ): LinkedCategory =
    if (row.expenseId.isEmpty) LinkedCategory.Unknown
    else
      row.expenseCategoryId match {
        case None => LinkedCategory.Multiple
        case Some(id) => categories.find(_.id == id).fold(LinkedCategory.Unknown)(c => LinkedCategory.Named(c.name))
      }

  /** The linked expense's merchant name, when it has one and it is in the loaded merchant list. */
  def linkedMerchantName(
      row: GetBankStatement.StatementRowData,
      merchants: List[GetMerchants.Response.Merchant]
  ): Option[String] =
    row.expenseId.flatMap(_ => row.expenseMerchantId).flatMap(mid => merchants.find(_.id == mid)).map(_.name)

  /** The entries of a row's "…" menu: a linked or ignored row can only be undone; an open row can be linked, get a rule
    * or be ignored.
    */
  enum RowMenuItem {
    case LinkExpense, CreateRule, Ignore, Unlink, UnlinkRefund, Unignore
  }

  def menuItemsFor(row: GetBankStatement.StatementRowData): List[RowMenuItem] =
    if (row.expenseId.isDefined) List(RowMenuItem.Unlink)
    else if (row.refundId.isDefined) List(RowMenuItem.UnlinkRefund)
    else if (row.ignoredAt.isDefined) List(RowMenuItem.Unignore)
    else List(RowMenuItem.LinkExpense, RowMenuItem.CreateRule, RowMenuItem.Ignore)

  /** The single primary button a row shows. Ready rows open the prefilled create form for review instead of importing
    * straight away; bulk "Process ready" is the one-click path.
    */
  enum PrimaryAction {
    case Linked, Unignore, IgnoreByRule, ReviewExpense, ReviewRefund, CreateRefund, CreateExpense
  }

  def primaryActionFor(
      row: GetBankStatement.StatementRowData,
      isReady: Boolean,
      verdict: RowRuleVerdict.Verdict
  ): PrimaryAction =
    if (row.expenseId.isDefined || row.refundId.isDefined) PrimaryAction.Linked
    else if (row.ignoredAt.isDefined) PrimaryAction.Unignore
    else if (isReady && verdict == RowRuleVerdict.Ignore) PrimaryAction.IgnoreByRule
    else if (isReady && row.amount > 0) PrimaryAction.ReviewRefund
    else if (isReady) PrimaryAction.ReviewExpense
    else if (row.amount > 0) PrimaryAction.CreateRefund
    else PrimaryAction.CreateExpense

  private val hintStyling = new CSSPropertiesUtils { fontSize = "0.8em"; color = "var(--lc-brand)" }

  /** Everything the review page loads up front; re-fetched as a whole on every refresh. */
  final case class ReviewData(
      statement: GetBankStatement.Response,
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category],
      rules: List[ImportRuleMatcher.Rule],
      expenses: List[GetExpenses.Response.Expense]
  )

  type BulkProgress = Option[(Int, Int, Int)]
  type BulkResults = List[(UUID, Either[String, Unit])]

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val (timesRefreshing, forceRefresh) = GenericHooks.useForceRefresh
    val params = useParams()
    val stmtIdOpt = Try(UUID.fromString(params.get("stmtId").getOrElse(""))).toOption

    // Kept here, not in the content, so they survive the remount a refresh causes.
    val (toast, setToast) = Hooks.useState[Option[String]](None)
    val (bulkIgnoreProgress, setBulkIgnoreProgress) = Hooks.useState[BulkProgress](None)
    val (bulkIgnoreResults, setBulkIgnoreResults) = Hooks.useState[BulkResults](Nil)
    val (bulkImportProgress, setBulkImportProgress) = Hooks.useState[BulkProgress](None)
    val (bulkImportResults, setBulkImportResults) = Hooks.useState[BulkResults](Nil)

    def showToast(msg: String): Unit = {
      setToast(Some(msg))
      dom.window.setTimeout(() => setToast(None), 3000)
    }

    def fetch(stmtId: UUID): Future[ReviewData] = {
      val client = props.ctx.api.client
      val statementF = client.getBankStatement(stmtId)
      val merchantsF = client.getMerchants
      val categoriesF = client.getCategories
      val rulesF = client.getImportRules
      val expensesF = client.getExpenses
      for {
        statement <- statementF
        merchants <- merchantsF
        categories <- categoriesF
        rules <- rulesF
        expenses <- expensesF
      } yield ReviewData(
        statement = statement,
        merchants = merchants.data,
        categories = categories.data,
        rules = rules.data.map(ImportRuleMatcher.Rule.fromApi),
        expenses = expenses.data
      )
    }

    stmtIdOpt match {
      case None =>
        mui.Typography("Invalid statement ID").sx(new CSSPropertiesUtils { color = "var(--lc-error)" })
      case Some(stmtId) =>
        Fragment(
          AsyncComponent[ReviewData](
            fetch = () => fetch(stmtId),
            render = data =>
              BankStatementReviewContent(
                BankStatementReviewContent.Props(
                  ctx = props.ctx,
                  data = data,
                  onRefresh = () => forceRefresh(),
                  showToast = showToast,
                  bulkIgnoreProgress = bulkIgnoreProgress,
                  setBulkIgnoreProgress = setBulkIgnoreProgress,
                  bulkIgnoreResults = bulkIgnoreResults,
                  setBulkIgnoreResults = setBulkIgnoreResults,
                  bulkImportProgress = bulkImportProgress,
                  setBulkImportProgress = setBulkImportProgress,
                  bulkImportResults = bulkImportResults,
                  setBulkImportResults = setBulkImportResults
                )
              ).withKey(timesRefreshing.toString),
            progressIndicator = () => Loader(props.ctx),
            watchedObjects = List(timesRefreshing)
          ),
          toast match {
            case None => Fragment()
            case Some(msg) =>
              mui.Snackbar
                .open(true)
                .autoHideDuration(3000.0)
                .onClose((_, _) => setToast(None))(
                  mui.Alert.severity("success")(msg)
                )
          }
        )
    }
  }
}

/** The review page body for one loaded statement. Its parent remounts it on every refresh, so state seeded from `data`
  * is always fresh.
  */
object BankStatementReviewContent {
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
    // Kept current by `refreshExpenses` after single-row link changes; the per-row expense matching reads it.
    val (expenseList, setExpenseList) = Hooks.useState(props.data.expenses)

    val initialTab = {
      val params = new URLSearchParams(location.search)
      val t = params.get("tab")
      if (t == null || t.isEmpty) "ready" else t
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
    def isReadyToProcess(row: GetBankStatement.StatementRowData): Boolean = rowInfoById.get(row.id).exists(_.isReady)

    def filteredRows: List[GetBankStatement.StatementRowData] = rowFilter match {
      case "ignored" => statementRows.filter(_.ignoredAt.isDefined)
      case "linked" => statementRows.filter(r => r.expenseId.isDefined || r.refundId.isDefined)
      case "ready" => statementRows.filter(isReadyToProcess)
      case "all" => statementRows
      case _ => statementRows.filter(r => r.expenseId.isEmpty && r.refundId.isEmpty && r.ignoredAt.isEmpty)
    }

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

    def handleUnlinkRefund(stmtId: UUID, rowId: UUID): Unit =
      props.ctx.api.client.unlinkRowRefund(stmtId, rowId).onComplete {
        case Success(_) =>
          setError("")
          setStatementRows(cur => cur.map(r => if (r.id == rowId) r.copy(refundId = None) else r))
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

    def handleBulkImport(stmtId: UUID, ids: List[UUID]): Unit = {
      val total = ids.length
      setBulkImportProgress(Some((0, total, 0)))
      setBulkImportResults(Nil)
      def loop(remaining: List[UUID], done: Int, failed: Int): Unit =
        remaining match {
          case Nil =>
            setBulkImportProgress(None)
            setSelectedRowIds(Set.empty)
            if (failed == 0) showToast(s"Imported $done expenses")
          case rowId :: rest =>
            statementRows.find(_.id == rowId) match {
              case None =>
                setBulkImportResults(cur => cur :+ (rowId -> Left("Row not found")))
                loop(rest, done + 1, failed + 1)
              case Some(row) =>
                val info = rowInfoById.get(row.id)
                if (info.exists(_.verdict == RowRuleVerdict.Ignore))
                  props.ctx.api.client.ignoreRow(stmtId, rowId).onComplete {
                    case Success(_) =>
                      setStatementRows(cur =>
                        cur.map(r => if (r.id == rowId) r.copy(ignoredAt = Some(Instant.now())) else r)
                      )
                      setBulkImportResults(cur => cur :+ (rowId -> Right(())))
                      setBulkImportProgress(Some((done + 1, total, failed)))
                      loop(rest, done + 1, failed)
                    case Failure(ex) =>
                      setBulkImportResults(cur => cur :+ (rowId -> Left(ex.getMessage)))
                      setBulkImportProgress(Some((done + 1, total, failed + 1)))
                      loop(rest, done + 1, failed + 1)
                  }
                else
                  info.flatMap(_.resolved) match {
                    case None =>
                      setBulkImportResults(cur => cur :+ (rowId -> Left("No category")))
                      loop(rest, done + 1, failed + 1)
                    case Some(res) =>
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
                          setBulkImportResults(cur => cur :+ (rowId -> Right(())))
                          setBulkImportProgress(Some((done + 1, total, failed)))
                          loop(rest, done + 1, failed)
                        case Failure(ex) =>
                          setBulkImportResults(cur => cur :+ (rowId -> Left(ex.getMessage)))
                          setBulkImportProgress(Some((done + 1, total, failed + 1)))
                          loop(rest, done + 1, failed + 1)
                      }
                  }
            }
        }
      loop(ids, 0, 0)
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
    val drawerRowId =
      creatingForRow.orElse(creatingRefundForRow).orElse(linkingForRow).orElse(ruleFormForRow)

    def closeDrawer(): Unit = {
      setCreatingForRow(None); setCreatingRefundForRow(None)
      setLinkingForRow(None); setRuleFormForRow(None)
    }

    val stmt = statement
    val tableRows = filteredRows
    val selectedInView = selectedRowIds.intersect(tableRows.map(_.id).toSet)
    val bulkActive = selectedInView.nonEmpty && bulkIgnoreProgress.isEmpty && bulkImportProgress.isEmpty
    val allTableSelected = tableRows.nonEmpty && tableRows.forall(r => selectedRowIds.contains(r.id))
    val someTableSelected = tableRows.exists(r => selectedRowIds.contains(r.id)) && !allTableSelected

    val countReady = statementRows.count(isReadyToProcess)
    val countUnprocessed =
      statementRows.count(r => r.expenseId.isEmpty && r.refundId.isEmpty && r.ignoredAt.isEmpty)
    val countIgnored = statementRows.count(_.ignoredAt.isDefined)
    val countLinked = statementRows.count(r => r.expenseId.isDefined || r.refundId.isDefined)

    val hcell = new CSSPropertiesUtils {
      fontWeight = "700"; fontSize = "11px"; letterSpacing = "0.06em"
      textTransform = "uppercase"; color = "var(--lc-text-2)"
    }

    mui.Box.sx(new CSSPropertiesUtils {
      display = "flex"; flexDirection = "column"; height = "100%"; minHeight = "0"
    })(
      // ── Header + Tabs ──
      mui.Box.sx(new CSSPropertiesUtils { padding = "20px 24px 0 24px" })(
        mui.Box.sx(new CSSPropertiesUtils {
          display = "flex"; alignItems = "baseline"; gap = "12px"; marginBottom = "4px"
        })(
          mui
            .Typography(stmt.documentFilename)
            .sx(new CSSPropertiesUtils {
              fontSize = "18px"; fontWeight = "700"; color = "var(--lc-text)"
              fontFamily = "\"IBM Plex Sans\", sans-serif"
            }),
          mui
            .Typography(s"${statementRows.size} transactions")
            .sx(new CSSPropertiesUtils { fontSize = "14px"; color = "var(--lc-text-2)" }),
          mui.Box.sx(new CSSPropertiesUtils { flex = "1" })(),
          mui.Button
            .normal()(
              if (regenerateSuggestionsDisabled) "Regenerating…"
              else "Regenerate AI suggestions"
            )
            .size("small")
            .variant("outlined")
            .disabled(regenerateSuggestionsDisabled)
            .onClick(_ => handleRegenerateSuggestions(stmt.id)),
          mui.Button
            .normal()("Auto-reconcile")
            .size("small")
            .variant("outlined")
            .onClick(_ => history.push(s"/bank-statements/${stmt.id}/auto-reconcile")),
          mui.Button
            .normal()("← Back")
            .size("small")
            .variant("outlined")
            .onClick(_ => history.push("/bank-statements"))
        ),
        statementMetadataLine(stmt),
        mui.Tabs
          .value(rowFilter)
          .onChange((_, v) => {
            val tab = v.asInstanceOf[String]
            setRowFilter(tab)
            setSelectedRowIds(Set.empty)
            val pathname = location.pathname
            history.replace(s"$pathname?tab=$tab")
          })
          .sx(new CSSPropertiesUtils { marginTop = "8px"; minHeight = "42px" })(
            mui.Tab.normal().value("ready").label(s"Ready ($countReady)").build,
            mui.Tab.normal().value("unprocessed").label(s"Unprocessed ($countUnprocessed)").build,
            mui.Tab.normal().value("ignored").label(s"Ignored ($countIgnored)").build,
            mui.Tab.normal().value("linked").label(s"Linked ($countLinked)").build,
            mui.Tab.normal().value("all").label(s"All (${statementRows.size})").build
          )
      ),
      // ── Bulk bar ──
      if (selectedInView.nonEmpty)
        mui.Box.sx(new CSSPropertiesUtils {
          margin = "12px 24px 0 24px"; padding = "8px 16px"; borderRadius = "8px"
          display = "flex"; alignItems = "center"; gap = "12px"
          backgroundColor = "var(--lc-brand)"
        })(
          mui
            .Typography(s"${selectedInView.size} selected")
            .sx(new CSSPropertiesUtils { fontWeight = "600"; fontSize = "14px"; color = "var(--lc-brand-on)" }),
          mui.Box.sx(new CSSPropertiesUtils { flex = "1" })(),
          bulkImportProgress match {
            case Some((done, total, failed)) =>
              mui
                .Typography(s"Importing $done/$total${if (failed > 0) s" ($failed failed)" else ""}…")
                .sx(new CSSPropertiesUtils { color = "var(--lc-brand-on)"; fontSize = "14px" })
            case None =>
              (mui.Button
                .normal()("Process ready")
                .size("small")
                .variant("contained")
                .sx(new CSSPropertiesUtils { backgroundColor = "var(--lc-paper)"; color = "var(--lc-brand)" })
                .disabled(!bulkActive)
                .onClick(_ => handleBulkImport(stmt.id, selectedInView.toList))
                .build: ReactElement)
          },
          bulkIgnoreProgress match {
            case Some((done, total, failed)) =>
              mui
                .Typography(s"Ignoring $done/$total${if (failed > 0) s" ($failed failed)" else ""}…")
                .sx(new CSSPropertiesUtils { color = "var(--lc-brand-on)"; fontSize = "14px" })
            case None =>
              (mui.Button
                .normal()("Ignore")
                .size("small")
                .variant("text")
                .sx(new CSSPropertiesUtils { color = "var(--lc-brand-on)" })
                .disabled(!bulkActive)
                .onClick(_ => handleBulkIgnore(stmt.id, selectedInView.toList))
                .build: ReactElement)
          },
          (mui.IconButton
            .normal()(
              mui.Typography("×").sx(new CSSPropertiesUtils { fontSize = "20px"; color = "var(--lc-brand-on)" })
            )
            .size("small")
            .onClick(_ => setSelectedRowIds(Set.empty))
            .build: ReactElement)
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
        mui.Table.size("small")(
          mui.TableHead(
            mui.TableRow(
              mui.TableCell.padding("checkbox")(
                if (rowFilter == "unprocessed" || rowFilter == "ready")
                  (mui.Checkbox
                    .checked(allTableSelected)
                    .indeterminate(someTableSelected)
                    .size("small")
                    .onChange((_, checked) =>
                      if (checked)
                        setSelectedRowIds(tableRows.map(_.id).toSet)
                      else setSelectedRowIds(Set.empty)
                    ): ReactElement)
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
          if (tableRows.isEmpty)
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
                tableRows.flatMap { row =>
                  rowInfoById.get(row.id).map { info =>
                    (BankStatementReviewRow(
                      BankStatementReviewRow.Props(
                        row = row,
                        info = info,
                        selected = selectedRowIds.contains(row.id),
                        showCheckbox = rowFilter == "unprocessed" || rowFilter == "ready",
                        pollingSuggestions = pollingSuggestions,
                        onToggleSelected = onToggleSelected,
                        onAction = onRowAction
                      )
                    ).withKey(row.id.toString): ReactElement)
                  }
                }*
              )
            )
        ),
        if (bulkImportResults.nonEmpty || bulkIgnoreResults.nonEmpty)
          mui.Box.sx(new CSSPropertiesUtils { marginTop = "16px" })(
            Fragment({
              val descById = statementRows.map(r => r.id -> r.description).toMap
              (bulkImportResults ++ bulkIgnoreResults).map { case (rowId, outcome) =>
                val lbl = resultLabel(rowId, descById, outcome)
                val txtColor = outcome match {
                  case Right(_) => "var(--lc-success-text)"
                  case Left(_) => "var(--lc-error)"
                }
                (mui
                  .Typography(lbl)
                  .sx(new CSSPropertiesUtils { fontSize = "12px"; color = txtColor })
                  .withKey(rowId.toString): ReactElement)
              }
            }*)
          )
        else Fragment()
      ),
      // ── Detail Drawer ──
      mui.Drawer
        .anchor("right")
        .open(drawerOpen)
        .onClose((_, _) => closeDrawer())(
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
                .onClick(_ => closeDrawer())
                .build: ReactElement)
            ),
            drawerRowId
              .flatMap(id => statementRows.find(_.id == id))
              .map { row =>
                (Fragment(
                  mui.Box.sx(new CSSPropertiesUtils {
                    padding = "12px 16px"; marginBottom = "16px"; borderRadius = "8px"
                    border = "1px solid var(--lc-border)"; backgroundColor = "var(--lc-bg)"
                  })(
                    mui.Box.sx(new CSSPropertiesUtils {
                      display = "flex"; justifyContent = "space-between"; alignItems = "flex-start"
                    })(
                      mui.Box(
                        mui
                          .Typography("From statement")
                          .sx(new CSSPropertiesUtils {
                            fontSize = "11px"; fontWeight = "700"; letterSpacing = "0.06em"
                            textTransform = "uppercase"; color = "var(--lc-text-2)"; display = "block"
                          }),
                        mui
                          .Typography(row.description)
                          .sx(new CSSPropertiesUtils {
                            fontFamily = "\"IBM Plex Mono\", monospace"; fontSize = "13.5px"
                            marginTop = "4px"; display = "block"
                          }),
                        mui
                          .Typography(row.date.toString)
                          .sx(new CSSPropertiesUtils {
                            fontSize = "12px"; color = "var(--lc-text-2)"; display = "block"
                          })
                      ),
                      mui
                        .Typography(s"${if (row.amount > 0) "+" else ""}${row.amount}")
                        .sx(new CSSPropertiesUtils {
                          fontWeight = "700"; fontSize = "18px"
                          fontFamily = "\"IBM Plex Mono\", monospace"
                          color = if (row.amount > 0) "var(--lc-brand)" else "var(--lc-error)"
                        })
                    )
                  ),
                  if (creatingForRow.contains(row.id))
                    BankRowCreateExpenseForm(
                      BankRowCreateExpenseForm.Props(
                        stmtId = stmt.id,
                        row = row,
                        merchants = merchantList,
                        categories = categoryList,
                        ruleList = ruleList,
                        aiSuggestion = stmt.aiSuggestions.flatMap(_.rows.find(_.rowId == row.id)),
                        ctx = props.ctx,
                        onSuccess = expenseId => {
                          setStatementRows(cur =>
                            cur.map(r => if (r.id == row.id) r.copy(expenseId = Some(expenseId)) else r)
                          )
                          setCreatingForRow(None)
                          refreshRow(row.id)
                          showToast("Expense created")
                        },
                        onCancel = () => setCreatingForRow(None),
                        onNewEntities = (newRule, newMerchant) => {
                          newRule.foreach(r => setRuleList(cur => cur :+ r))
                          newMerchant.foreach(m => setMerchantList(cur => cur :+ m))
                        }
                      )
                    )
                  else if (creatingRefundForRow.contains(row.id))
                    BankRowCreateRefundForm(
                      BankRowCreateRefundForm.Props(
                        stmtId = stmt.id,
                        rowId = row.id,
                        row = row,
                        merchants = merchantList,
                        categories = categoryList,
                        ruleList = ruleList,
                        ctx = props.ctx,
                        onSuccess = refundId => {
                          setStatementRows(cur =>
                            cur.map(r => if (r.id == row.id) r.copy(refundId = Some(refundId)) else r)
                          )
                          setCreatingRefundForRow(None)
                          showToast("Refund created")
                        },
                        onCancel = () => setCreatingRefundForRow(None)
                      )
                    )
                  else if (linkingForRow.contains(row.id))
                    BankRowLinkPicker(
                      BankRowLinkPicker.Props(
                        stmtId = stmt.id,
                        rowId = row.id,
                        ctx = props.ctx,
                        onSuccess = expenseId => {
                          setStatementRows(cur =>
                            cur.map(r => if (r.id == row.id) r.copy(expenseId = Some(expenseId)) else r)
                          )
                          setLinkingForRow(None)
                          refreshRow(row.id)
                        },
                        onCancel = () => setLinkingForRow(None)
                      )
                    )
                  else if (ruleFormForRow.contains(row.id))
                    BankRowRuleForm(
                      BankRowRuleForm.Props(
                        stmtId = stmt.id,
                        row = row,
                        merchants = merchantList,
                        categories = categoryList,
                        ctx = props.ctx,
                        onSuccess = (rule, newMerchantOpt) => {
                          setRuleList(cur => cur :+ rule)
                          newMerchantOpt.foreach(m => setMerchantList(cur => cur :+ m))
                          setRuleFormForRow(None)
                          showToast("Rule created")
                        },
                        onCancel = () => setRuleFormForRow(None)
                      )
                    )
                  else Fragment()
                ): ReactElement)
              }
              .getOrElse(Fragment())
          )
        )
    )
  }
}
