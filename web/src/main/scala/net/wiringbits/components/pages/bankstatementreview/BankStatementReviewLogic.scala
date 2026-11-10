package net.wiringbits.components.pages.bankstatementreview

import net.wiringbits.api.models.bankstatements.{AiSuggestions, AiSuggestionsStatus, GetBankStatement}
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.api.models.receipts.GetReceipts
import net.wiringbits.api.utils.{ImportRuleMatcher, RowRuleVerdict}
import net.wiringbits.components.pages.bankstatements.BankStatementsLogic
import net.wiringbits.utils.ReconciliationMatcher

import java.time.LocalDate
import java.util.UUID

/** Pure logic behind the bank statement review page: no React or MUI. */
object BankStatementReviewLogic {

  type BulkResults = List[(UUID, Either[String, Unit])]

  /** A value fetched asynchronously: still loading, loaded, or failed with the error to show. */
  enum Loadable[+T] {
    case Loading
    case Loaded(value: T)
    case Failed(message: String)

    def toOption: Option[T] = this match {
      case Loaded(v) => Some(v)
      case _ => None
    }

    def map[U](f: T => U): Loadable[U] = this match {
      case Loaded(v) => Loaded(f(v))
      case Loading => Loading
      case Failed(m) => Failed(m)
    }
  }

  /** The lists the review page loads after the statement itself; kept up to date locally as the user adds entities. */
  final case class ReviewSupport(
      merchants: List[GetMerchants.Response.Merchant],
      categories: List[GetCategories.Response.Category],
      rules: List[ImportRuleMatcher.Rule],
      expenses: List[GetExpenses.Response.Expense],
      receipts: List[GetReceipts.Response.Receipt]
  ) {
    def withRule(rule: ImportRuleMatcher.Rule): ReviewSupport = copy(rules = rules :+ rule)
    def withMerchant(m: GetMerchants.Response.Merchant): ReviewSupport = copy(merchants = merchants :+ m)
    def withExpenses(list: List[GetExpenses.Response.Expense]): ReviewSupport = copy(expenses = list)
    def withoutReceipt(id: UUID): ReviewSupport = copy(receipts = receipts.filterNot(_.id == id))
  }

  /** The bank statements list, filtered to this statement's account; independent of browser history. */
  def backToListPath(stmt: GetBankStatement.Response): String =
    BankStatementsLogic.listPath(
      account = Some(
        BankStatementsLogic
          .accountKey(bankName = stmt.bankName, productType = stmt.productType, accountLast4 = stmt.accountLast4)
      ),
      name = ""
    )

  /** Apply `f` to the row with `rowId`, leaving the others untouched. */
  def patchRow(rows: List[GetBankStatement.StatementRowData], rowId: UUID)(
      f: GetBankStatement.StatementRowData => GetBankStatement.StatementRowData
  ): List[GetBankStatement.StatementRowData] =
    rows.map(r => if (r.id == rowId) f(r) else r)

  /** Swap in a freshly fetched copy of a row, matched by id. */
  def replaceRow(
      rows: List[GetBankStatement.StatementRowData],
      fresh: GetBankStatement.StatementRowData
  ): List[GetBankStatement.StatementRowData] =
    patchRow(rows, fresh.id)(_ => fresh)

  /** The expense dates (from, to) that can match the statement's rows: a row matches expenses up to
    * `MaxDateDifferenceDays` before it. Rows outside the statement period widen the window.
    */
  def candidateExpenseWindow(statement: GetBankStatement.Response): (LocalDate, LocalDate) = {
    val dates = statement.statementRows.map(_.date)
    val earliest = (statement.periodStart :: dates).minBy(_.toEpochDay)
    val latest = (statement.periodEnd :: dates).maxBy(_.toEpochDay)
    (earliest.minusDays(ReconciliationMatcher.MaxDateDifferenceDays), latest)
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

  /** What a rule-matched credit row prefills on its refund: the rule's merchant and that merchant's default category,
    * or a SetCategory rule's category with no merchant.
    */
  final case class RefundPrefill(merchantId: Option[UUID], categoryId: Option[UUID])

  /** Resolve the refund a credit row would create from its rule verdict alone; AI suggestions are not consulted. None
    * for debits, Ignore verdicts, rows no rule matches and SetMerchant rules whose merchant is not loaded (the form
    * could not prefill it either).
    */
  def resolveRefund(
      verdict: RowRuleVerdict.Verdict,
      merchants: List[GetMerchants.Response.Merchant],
      row: GetBankStatement.StatementRowData
  ): Option[RefundPrefill] =
    if (row.amount <= 0) None
    else
      verdict match {
        case RowRuleVerdict.SetMerchant(merchantId) =>
          merchants.find(_.id == merchantId).map(m => RefundPrefill(merchantId = Some(m.id), categoryId = m.categoryId))
        case RowRuleVerdict.SetCategory(categoryId) =>
          Some(RefundPrefill(merchantId = None, categoryId = Some(categoryId)))
        case RowRuleVerdict.Ignore | RowRuleVerdict.NoRule => None
      }

  /** Everything a table row derives from the rules, merchants, categories and AI suggestions, computed once per row so
    * rendering does not re-run the rule matcher. Debits may have a `resolved` expense import, credits a `refund`
    * prefill, never the other. The names are the display labels for the resolved import or refund, if any;
    * `merchantName` falls back to the prefill merchant's. `prefillMerchantId` is the rule-matched merchant, set even
    * when it has no default category (so the row is not ready, but the merchant is still known).
    */
  final case class RowInfo(
      verdict: RowRuleVerdict.Verdict,
      resolved: Option[ResolvedImport],
      refund: Option[RefundPrefill],
      prefillMerchantId: Option[UUID],
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
    val resolved = if (row.amount < 0) resolveImport(verdict, merchants, aiSuggestions, row) else None
    val refund = resolveRefund(verdict, merchants, row)
    val prefillMerchantId = RowRuleVerdict.merchantId(verdict)
    val open = row.expenseId.isEmpty && row.refundId.isEmpty && row.ignoredAt.isEmpty
    val isReady =
      open && (verdict == RowRuleVerdict.Ignore || (row.amount < 0 && resolved.isDefined) || (row.amount > 0 && refund.isDefined))
    RowInfo(
      verdict = verdict,
      resolved = resolved,
      refund = refund,
      prefillMerchantId = prefillMerchantId,
      isReady = isReady,
      merchantName = resolved
        .flatMap(_.merchantId)
        .orElse(prefillMerchantId)
        .flatMap(mid => merchants.find(_.id == mid))
        .map(_.name),
      categoryName = resolved
        .map(_.categoryId)
        .orElse(refund.flatMap(_.categoryId))
        .flatMap(cid => categories.find(_.id == cid))
        .map(_.name),
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

  /** The category label for an expense from its splits: Multiple when they span several categories, Unknown when it has
    * no splits or its single category is not loaded.
    */
  def expenseCategory(
      expense: GetExpenses.Response.Expense,
      categories: List[GetCategories.Response.Category]
  ): LinkedCategory =
    expense.splits.map(_.categoryId).distinct match {
      case Nil => LinkedCategory.Unknown
      case id :: Nil => categories.find(_.id == id).fold(LinkedCategory.Unknown)(c => LinkedCategory.Named(c.name))
      case _ => LinkedCategory.Multiple
    }

  /** The existing expense a row uniquely matches, with the labels the row shows for it before the link is confirmed. */
  final case class MatchedExpense(expenseId: UUID, merchantName: Option[String], category: LinkedCategory)

  /** Only a unique (AutoLink) match to an expense in `expenses` counts; the merchant name falls back to the loaded
    * merchant list when the expense does not carry one.
    */
  def matchedExpense(
      proposed: Option[ReconciliationMatcher.ProposedAction],
      expenses: List[GetExpenses.Response.Expense],
      categories: List[GetCategories.Response.Category],
      merchants: List[GetMerchants.Response.Merchant]
  ): Option[MatchedExpense] =
    proposed
      .collect { case ReconciliationMatcher.AutoLink(id) => id }
      .flatMap(id => expenses.find(_.id == id))
      .map { e =>
        MatchedExpense(
          expenseId = e.id,
          merchantName = e.merchantName.orElse(e.merchantId.flatMap(mid => merchants.find(_.id == mid)).map(_.name)),
          category = expenseCategory(e, categories)
        )
      }

  /** `matchedExpense` for every row with an expense match, keyed by row id; rows without a unique match are absent. */
  def matchedExpenses(
      expenseMatchById: Map[UUID, ReconciliationMatcher.ProposedAction],
      expenses: List[GetExpenses.Response.Expense],
      categories: List[GetCategories.Response.Category],
      merchants: List[GetMerchants.Response.Merchant]
  ): Map[UUID, MatchedExpense] =
    expenseMatchById.flatMap { case (rowId, proposed) =>
      matchedExpense(Some(proposed), expenses, categories, merchants).map(rowId -> _)
    }

  /** What the link picker lists: the matched expense (if any) pinned first regardless of the filter or the cap, then up
    * to 20 other expenses not yet linked to a row, ranked by how close their amount and date are to this row's, so the
    * plausible matches surface instead of the whole history. A non-empty filter searches that same unlinked set by
    * date, amount, merchant or notes (case-insensitive), still ranked by closeness.
    */
  def linkPickerEntries(
      expenses: List[GetExpenses.Response.Expense],
      filter: String,
      matchedId: Option[UUID],
      rowAmount: BigDecimal,
      rowDate: LocalDate
  ): (Option[GetExpenses.Response.Expense], List[GetExpenses.Response.Expense]) = {
    val f = filter.toLowerCase
    val matched = matchedId.flatMap(id => expenses.find(_.id == id))
    val candidates = expenses.filter(e => !matchedId.contains(e.id) && e.rowIds.isEmpty)
    val textMatched =
      if (f.isEmpty) candidates
      else
        candidates.filter { e =>
          e.date.toString.contains(f) || e.amount.toString.contains(f) ||
          e.merchantName.exists(_.toLowerCase.contains(f)) || e.notes.exists(_.toLowerCase.contains(f))
        }
    val ranked =
      textMatched.sortBy(e => ((e.amount.abs - rowAmount.abs).abs, Math.abs(e.date.toEpochDay - rowDate.toEpochDay)))
    (matched, ranked.take(20))
  }

  /** The linked expense's merchant name, when it has one and it is in the loaded merchant list. */
  def linkedMerchantName(
      row: GetBankStatement.StatementRowData,
      merchants: List[GetMerchants.Response.Merchant]
  ): Option[String] =
    row.expenseId.flatMap(_ => row.expenseMerchantId).flatMap(mid => merchants.find(_.id == mid)).map(_.name)

  /** The entries of a row's "…" menu: a linked or ignored row can only be undone; an open row can be linked, get a rule
    * or be ignored, and an open debit can also become a new expense even when its primary action confirms a match.
    */
  enum RowMenuItem {
    case LinkExpense, CreateExpense, CreateRule, Ignore, Unlink, UnlinkRefund, Unignore
  }

  def menuItemsFor(row: GetBankStatement.StatementRowData): List[RowMenuItem] =
    if (row.expenseId.isDefined) List(RowMenuItem.Unlink)
    else if (row.refundId.isDefined) List(RowMenuItem.UnlinkRefund)
    else if (row.ignoredAt.isDefined) List(RowMenuItem.Unignore)
    else if (row.amount < 0)
      List(RowMenuItem.LinkExpense, RowMenuItem.CreateExpense, RowMenuItem.CreateRule, RowMenuItem.Ignore)
    else List(RowMenuItem.LinkExpense, RowMenuItem.CreateRule, RowMenuItem.Ignore)

  /** The single primary button a row shows. Ready rows open the prefilled create form for review instead of importing
    * straight away; bulk "Process ready" is the one-click path. A ready debit that uniquely matches an existing expense
    * offers to confirm that link instead, since creating a new expense would duplicate it.
    */
  enum PrimaryAction {
    case Linked, Unignore, IgnoreByRule, ConfirmLink, ReviewExpense, ReviewRefund, CreateRefund, CreateExpense
  }

  /** `matchedExpense` is the row's unique (AutoLink) expense match, if any; it only affects ready debits. */
  def primaryActionFor(
      row: GetBankStatement.StatementRowData,
      isReady: Boolean,
      verdict: RowRuleVerdict.Verdict,
      matchedExpense: Option[MatchedExpense]
  ): PrimaryAction =
    if (row.expenseId.isDefined || row.refundId.isDefined) PrimaryAction.Linked
    else if (row.ignoredAt.isDefined) PrimaryAction.Unignore
    else if (isReady && verdict == RowRuleVerdict.Ignore) PrimaryAction.IgnoreByRule
    else if (isReady && row.amount > 0) PrimaryAction.ReviewRefund
    else if (isReady && matchedExpense.isDefined) PrimaryAction.ConfirmLink
    else if (isReady) PrimaryAction.ReviewExpense
    else if (row.amount > 0) PrimaryAction.CreateRefund
    else PrimaryAction.CreateExpense

  /** What "Process ready" does with one selected row. */
  enum BulkRowDecision {
    case Ignore
    case Link(expenseId: UUID)
    case Create(resolved: ResolvedImport, receiptId: Option[UUID])
    case CreateRefund(prefill: RefundPrefill)
    case Fail(message: String)
  }

  /** A bulk action's lifecycle. Results are kept while running and after it finishes, so they stay displayed. */
  enum BulkRun {
    case Idle
    case Running(total: Int, override val results: BulkResults)
    case Done(override val results: BulkResults)

    /** Idle has none; Running and Done override this with their own field. */
    def results: BulkResults = Nil

    def isRunning: Boolean = this match {
      case Running(_, _) => true
      case _ => false
    }

    /** Append a row's outcome; only a running action records anything. */
    def recorded(rowId: UUID, outcome: Either[String, Unit]): BulkRun = this match {
      case Running(total, rs) => Running(total, rs :+ (rowId -> outcome))
      case other => other
    }

    def finished: BulkRun = this match {
      case Running(_, rs) => Done(rs)
      case other => other
    }
  }

  object BulkRun {
    extension (r: BulkRun.Running) {
      def done: Int = r.results.size
      def failed: Int = r.results.count(_._2.isLeft)
    }
  }

  /** Bulk Process/Ignore need the support lists: without expense matching, a row ready by AI would get a duplicate
    * expense.
    */
  def bulkActionsEnabled(
      supportLoaded: Boolean,
      selectedCount: Int,
      bulkIgnore: BulkRun,
      bulkImport: BulkRun
  ): Boolean =
    supportLoaded && selectedCount > 0 && !bulkIgnore.isRunning && !bulkImport.isRunning

  /** Which per-row form the side drawer shows, if any. */
  enum DrawerState {
    case Closed
    case CreateExpense(rowId: UUID)
    case CreateRefund(rowId: UUID)
    case LinkExpense(rowId: UUID)
    case CreateRule(rowId: UUID)

    /** The row whose form is showing; a method named `rowId` would clash with the cases' fields. */
    def openRowId: Option[UUID] = this match {
      case Closed => None
      case CreateExpense(id) => Some(id)
      case CreateRefund(id) => Some(id)
      case LinkExpense(id) => Some(id)
      case CreateRule(id) => Some(id)
    }

    /** Close the drawer only if `form` is still the one showing. */
    def closing(form: DrawerState): DrawerState = if (this == form) Closed else this
  }

  private val manualLinkHint = "link it manually via … → Link to existing expense"

  /** Decide a selected row's bulk action from its info, its expense match and its receipt match. An Ignore verdict
    * wins; a credit with a refund prefill becomes a refund; otherwise a unique match is linked unless an earlier row in
    * this run already took that expense (the receipt match is not used); an ambiguous match fails so the user picks
    * manually; otherwise a new expense is created when the row resolves to a category, attaching `receiptMatch` if any.
    */
  def bulkRowDecision(
      info: Option[RowInfo],
      expenseMatch: Option[ReconciliationMatcher.ProposedAction],
      receiptMatch: Option[UUID],
      linkedThisRun: Set[UUID]
  ): BulkRowDecision =
    if (info.exists(_.verdict == RowRuleVerdict.Ignore)) BulkRowDecision.Ignore
    else
      info.flatMap(_.refund).map(BulkRowDecision.CreateRefund(_)).getOrElse {
        expenseMatch match {
          case Some(ReconciliationMatcher.AutoLink(expenseId)) if linkedThisRun.contains(expenseId) =>
            BulkRowDecision
              .Fail(s"Its matching expense was already linked to another row in this run — $manualLinkHint")
          case Some(ReconciliationMatcher.AutoLink(expenseId)) => BulkRowDecision.Link(expenseId)
          case Some(ReconciliationMatcher.NeedsReview(ids)) =>
            BulkRowDecision.Fail(s"Matches ${ids.size} existing expenses — $manualLinkHint")
          case None | Some(ReconciliationMatcher.CreateNew) =>
            info
              .flatMap(_.resolved)
              .fold(BulkRowDecision.Fail("No category"))(BulkRowDecision.Create(_, receiptMatch))
        }
      }

  /** Matches only ready debit rows that would become an expense (not Ignore-verdict rows) against the unlinked
    * expenses, given as `ReconciliationMatcher.unlinkedByDate`.
    */
  def expenseMatches(
      rows: List[GetBankStatement.StatementRowData],
      rowInfoById: Map[UUID, RowInfo],
      unlinkedByDate: Map[LocalDate, List[GetExpenses.Response.Expense]]
  ): Map[UUID, ReconciliationMatcher.ProposedAction] =
    rows.flatMap { row =>
      rowInfoById
        .get(row.id)
        .filter(info => info.isReady && info.verdict != RowRuleVerdict.Ignore && row.amount < 0)
        .map { info =>
          val candidates = ReconciliationMatcher.candidatesFor(row, unlinkedByDate)
          row.id -> ReconciliationMatcher.matchRow(row, info.verdict, candidates)
        }
    }.toMap

  /** The receipt each row would attach when "Process ready" creates its expense: only ready, non-Ignore debit rows that
    * resolve to a new expense (no refund prefill, and an expense match of None or CreateNew) are considered, and only a
    * single matching receipt counts. A receipt claimed by more than one row is ambiguous and attached to none.
    */
  def receiptMatches(
      rows: List[GetBankStatement.StatementRowData],
      rowInfoById: Map[UUID, RowInfo],
      expenseMatchById: Map[UUID, ReconciliationMatcher.ProposedAction],
      receipts: List[GetReceipts.Response.Receipt]
  ): Map[UUID, GetReceipts.Response.Receipt] = {
    val receiptById = receipts.map(r => r.id -> r).toMap
    val claims = rows.flatMap { row =>
      val createsExpense = rowInfoById.get(row.id).exists { info =>
        info.isReady && info.verdict != RowRuleVerdict.Ignore && row.amount < 0 && info.resolved.isDefined &&
        info.refund.isEmpty
      } && expenseMatchById.get(row.id).forall(_ == ReconciliationMatcher.CreateNew)
      if (!createsExpense) Nil
      else
        ReconciliationMatcher.matchReceipt(row, receipts) match {
          case ReconciliationMatcher.ReceiptMatch.Single(receiptId) => List(row.id -> receiptId)
          case _ => Nil
        }
    }
    val claimCount = claims.groupMapReduce(_._2)(_ => 1)(_ + _)
    claims.collect {
      case (rowId, receiptId) if claimCount(receiptId) == 1 => rowId -> receiptById(receiptId)
    }.toMap
  }

  /** The receipt id each row attaches when processed: its matched receipt, unless the user detached it. */
  def effectiveReceiptIds(
      matches: Map[UUID, GetReceipts.Response.Receipt],
      detached: Set[UUID]
  ): Map[UUID, UUID] =
    (matches -- detached).view.mapValues(_.id).toMap

  /** A merchant (and its default category) read from the attached receipt, offered in place of the form's current
    * merchant.
    */
  final case class ReceiptOffer(receiptId: UUID, merchant: GetMerchants.Response.Merchant, categoryId: Option[UUID])

  /** What the create-expense form offers once a receipt is attached: the receipt is better evidence than a rule or AI
    * suggestion, so its merchant is offered when it resolves to one of the user's merchants and differs from the
    * current one (a free-text or blank merchant counts as different). The user confirms the switch or keeps the
    * suggestion; either way the receipt is `decided` and not offered again. Receipts carry no notes, so notes are
    * untouched.
    */
  def receiptOffer(
      receipt: Option[GetReceipts.Response.Receipt],
      currentMerchantId: Option[UUID],
      merchants: List[GetMerchants.Response.Merchant],
      decided: Set[UUID]
  ): Option[ReceiptOffer] =
    for {
      r <- receipt
      if !decided.contains(r.id)
      merchantId <- r.merchantId
      merchant <- merchants.find(_.id == merchantId)
      if !currentMerchantId.contains(merchant.id)
    } yield ReceiptOffer(r.id, merchant, merchant.categoryId)

  /** The toast shown after "Process ready", listing only the non-zero counts. */
  def bulkImportSummary(linked: Int, created: Int, ignored: Int, failed: Int): String = {
    val parts = List(linked -> "linked", created -> "created", ignored -> "ignored", failed -> "failed")
      .collect { case (n, label) if n > 0 => s"$n $label" }
    if (parts.isEmpty) "No rows processed" else parts.mkString(", ")
  }

  /** The rows shown under a `?tab=` value; any other value shows the unprocessed rows. */
  def filterRows(
      rows: List[GetBankStatement.StatementRowData],
      tab: String,
      rowInfoById: Map[UUID, RowInfo]
  ): List[GetBankStatement.StatementRowData] = tab match {
    case "ignored" => rows.filter(_.ignoredAt.isDefined)
    case "linked" => rows.filter(r => r.expenseId.isDefined || r.refundId.isDefined)
    case "ready" => rows.filter(r => rowInfoById.get(r.id).exists(_.isReady))
    case "all" => rows
    case _ => rows.filter(r => r.expenseId.isEmpty && r.refundId.isEmpty && r.ignoredAt.isEmpty)
  }
}
