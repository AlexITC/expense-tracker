package net.wiringbits.components.pages

import net.wiringbits.api.models.bankstatements.{AiSuggestions, AiSuggestionsStatus, GetBankStatement}
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.api.utils.{ImportRuleMatcher, RowRuleVerdict}
import net.wiringbits.utils.ReconciliationMatcher

import java.time.LocalDate
import java.util.UUID

/** Pure logic behind the bank statement review page: no React or MUI. */
object BankStatementReviewLogic {
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

  /** What "Process ready" does with one selected row. */
  enum BulkRowDecision {
    case Ignore
    case Link(expenseId: UUID)
    case Create(resolved: ResolvedImport)
    case Fail(message: String)
  }

  private val manualLinkHint = "link it manually via … → Link to existing expense"

  /** Decide a selected row's bulk action from its info and its expense match. An Ignore verdict wins; a unique match is
    * linked unless an earlier row in this run already took that expense; an ambiguous match fails so the user picks
    * manually; otherwise a new expense is created when the row resolves to a category.
    */
  def bulkRowDecision(
      info: Option[RowInfo],
      expenseMatch: Option[ReconciliationMatcher.ProposedAction],
      linkedThisRun: Set[UUID]
  ): BulkRowDecision =
    if (info.exists(_.verdict == RowRuleVerdict.Ignore)) BulkRowDecision.Ignore
    else
      expenseMatch match {
        case Some(ReconciliationMatcher.AutoLink(expenseId)) if linkedThisRun.contains(expenseId) =>
          BulkRowDecision.Fail(s"Its matching expense was already linked to another row in this run — $manualLinkHint")
        case Some(ReconciliationMatcher.AutoLink(expenseId)) => BulkRowDecision.Link(expenseId)
        case Some(ReconciliationMatcher.NeedsReview(ids)) =>
          BulkRowDecision.Fail(s"Matches ${ids.size} existing expenses — $manualLinkHint")
        case None | Some(ReconciliationMatcher.CreateNew) =>
          info.flatMap(_.resolved).fold(BulkRowDecision.Fail("No category"))(BulkRowDecision.Create(_))
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

  /** The toast shown after "Process ready", listing only the non-zero counts. */
  def bulkImportSummary(linked: Int, created: Int, ignored: Int, failed: Int): String = {
    val parts = List(linked -> "linked", created -> "created", ignored -> "ignored", failed -> "failed")
      .collect { case (n, label) if n > 0 => s"$n $label" }
    if (parts.isEmpty) "No rows processed" else parts.mkString(", ")
  }

  /** A tab of the review table; `key` is its `?tab=` value. */
  enum Tab(val key: String) {
    case Ready extends Tab("ready")
    case Unprocessed extends Tab("unprocessed")
    case Ignored extends Tab("ignored")
    case Linked extends Tab("linked")
    case All extends Tab("all")
  }

  object Tab {

    /** Any unknown value is the unprocessed tab. */
    def parse(key: String): Tab = Tab.values.find(_.key == key).getOrElse(Tab.Unprocessed)
  }

  /** The rows shown under a tab. */
  def filterRows(
      rows: List[GetBankStatement.StatementRowData],
      tab: Tab,
      rowInfoById: Map[UUID, RowInfo]
  ): List[GetBankStatement.StatementRowData] = tab match {
    case Tab.Ignored => rows.filter(_.ignoredAt.isDefined)
    case Tab.Linked => rows.filter(r => r.expenseId.isDefined || r.refundId.isDefined)
    case Tab.Ready => rows.filter(r => rowInfoById.get(r.id).exists(_.isReady))
    case Tab.All => rows
    case Tab.Unprocessed => rows.filter(r => r.expenseId.isEmpty && r.refundId.isEmpty && r.ignoredAt.isEmpty)
  }

  final case class TabCounts(ready: Int, unprocessed: Int, ignored: Int, linked: Int, all: Int)

  def tabCounts(rows: List[GetBankStatement.StatementRowData], rowInfoById: Map[UUID, RowInfo]): TabCounts = {
    def count(tab: Tab): Int = filterRows(rows, tab, rowInfoById).size
    TabCounts(
      ready = count(Tab.Ready),
      unprocessed = count(Tab.Unprocessed),
      ignored = count(Tab.Ignored),
      linked = count(Tab.Linked),
      all = count(Tab.All)
    )
  }
}
