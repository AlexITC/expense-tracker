package net.wiringbits.components.pages.expenses
import net.wiringbits.components.pages.ExpenseFormSplits

import com.olvind.mui.csstype.mod.Property.FlexDirection
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.components.widgets.Loader
import net.wiringbits.forms.{ExpenseFormData, ExpenseSplitRow}
import net.wiringbits.core.SafeHooks
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.expenses.{CreateExpense, GetExpenses, MergeDetailsSource, MergeExpenses, UpdateExpense}
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.api.models.receipts.GetReceipts
import net.wiringbits.models.User
import net.wiringbits.ui.components.inputs.{
  AmountInput,
  DateInput,
  OptionalIdSelectInput,
  OptionalNotesInput,
  OptionalTimeInput
}
import net.wiringbits.ui.forms.{ExpLocalDate, ExpLocalTime, ExpNotes, PositiveBigDecimal, SubmitHooks}
import net.wiringbits.utils.ExpenseDuplicateDetector
import net.wiringbits.utils.ExpenseDuplicateDetector.{Candidate, Shape}
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.components.core.AsyncComponent
import net.wiringbits.webapp.utils.slinkyUtils.components.core.widgets.Container
import net.wiringbits.webapp.utils.slinkyUtils.core.GenericHooks
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.useHistory
import net.wiringbits.webapp.common.validators.ValidationResult
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.web.html.{form, onKeyDown, onSubmit}

import java.time.{DayOfWeek, LocalDate}
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object ExpensesPage {
  def apply(ctx: AppContext, user: User): KeyAddingStage =
    component(Props(ctx = ctx, user = user))

  case class Props(ctx: AppContext, user: User)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val (timesRefreshing, forceRefresh) = GenericHooks.useForceRefresh

    AsyncComponent[(GetExpenses.Response, GetMerchants.Response, GetCategories.Response, GetReceipts.Response)](
      fetch = () => {
        val expensesF = props.ctx.api.client.getExpenses
        val merchantsF = props.ctx.api.client.getMerchants
        val categoriesF = props.ctx.api.client.getCategories
        val receiptsF = props.ctx.api.client.getReceipts
        for {
          expenses <- expensesF
          merchants <- merchantsF
          categories <- categoriesF
          receipts <- receiptsF
        } yield (expenses, merchants, categories, receipts)
      },
      render = { case (expenses, merchants, categories, receipts) =>
        val receiptFilenames = receipts.data.map(r => r.id -> r.documentFilename).toMap
        ExpenseList(props.ctx, expenses, merchants, categories, receiptFilenames, () => forceRefresh())
      },
      progressIndicator = () => Loader(props.ctx),
      watchedObjects = List(timesRefreshing)
    )
  }
}

object ExpenseList {
  def apply(
      ctx: AppContext,
      expenses: GetExpenses.Response,
      merchants: GetMerchants.Response,
      categories: GetCategories.Response,
      receiptFilenames: Map[UUID, String],
      onRefresh: () => Unit
  ): KeyAddingStage =
    component(Props(ctx, expenses, merchants, categories, receiptFilenames, onRefresh))

  case class Props(
      ctx: AppContext,
      expenses: GetExpenses.Response,
      merchants: GetMerchants.Response,
      categories: GetCategories.Response,
      receiptFilenames: Map[UUID, String],
      onRefresh: () => Unit
  )

  private val monthNames =
    Array("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

  private val remainderStyling = new CSSPropertiesUtils { fontSize = "0.85em"; color = "var(--lc-link)" }

  def sortExpenses(expenses: List[GetExpenses.Response.Expense]): List[GetExpenses.Response.Expense] =
    expenses.sortWith { (a, b) =>
      val dateCmp = a.date.compareTo(b.date)
      if (dateCmp != 0) dateCmp > 0
      else
        (a.time, b.time) match {
          case (None, _) => false
          case (Some(_), None) => true
          case (Some(at), Some(bt)) => at.compareTo(bt) > 0
        }
    }

  object DateFilter {
    val Today = "today"
    val Week = "week"
    val Month = "month"
    val LastMonth = "last_month"
    val All = "all"
  }

  def applyDateFilter(
      expenses: List[GetExpenses.Response.Expense],
      filter: String,
      today: LocalDate
  ): List[GetExpenses.Response.Expense] = filter match {
    case DateFilter.Today => expenses.filter(_.date == today)
    case DateFilter.Week =>
      expenses.filter(e => !e.date.isBefore(today.minusDays(6)) && !e.date.isAfter(today))
    case DateFilter.Month =>
      expenses.filter(e => !e.date.isBefore(today.withDayOfMonth(1)) && !e.date.isAfter(today))
    case DateFilter.LastMonth =>
      val start = today.minusMonths(1).withDayOfMonth(1)
      val end = today.withDayOfMonth(1).minusDays(1)
      expenses.filter(e => !e.date.isBefore(start) && !e.date.isAfter(end))
    case _ => expenses
  }

  def formatAmount(amount: BigDecimal): String =
    amount.setScale(2, BigDecimal.RoundingMode.HALF_UP).underlying.toPlainString

  def applyDeletedIds(
      expenses: List[GetExpenses.Response.Expense],
      deletedIds: Set[UUID]
  ): List[GetExpenses.Response.Expense] =
    if (deletedIds.isEmpty) expenses else expenses.filterNot(e => deletedIds.contains(e.id))

  /** Adds each merged receipt (survivorId -> receiptId) to its survivor; the losers are removed by `deletedIds`. */
  def applyMerges(
      expenses: List[GetExpenses.Response.Expense],
      merges: Map[UUID, UUID]
  ): List[GetExpenses.Response.Expense] =
    if (merges.isEmpty) expenses
    else
      expenses.map(e => merges.get(e.id).fold(e)(receiptId => e.copy(receiptIds = e.receiptIds :+ receiptId)))

  /** Copies merchant, notes and splits from each adopted expense (survivorId -> loser) onto its survivor. */
  def applyAdoptedDetails(
      expenses: List[GetExpenses.Response.Expense],
      adopted: Map[UUID, GetExpenses.Response.Expense]
  ): List[GetExpenses.Response.Expense] =
    if (adopted.isEmpty) expenses
    else
      expenses.map(e =>
        adopted
          .get(e.id)
          .fold(e)(a =>
            e.copy(merchantId = a.merchantId, merchantName = a.merchantName, notes = a.notes, splits = a.splits)
          )
      )

  /** The expenses as they are after the in-place deletes and merges, without a refetch. */
  def applyMergeState(
      expenses: List[GetExpenses.Response.Expense],
      deletedIds: Set[UUID],
      merges: Map[UUID, UUID],
      adopted: Map[UUID, GetExpenses.Response.Expense]
  ): List[GetExpenses.Response.Expense] =
    applyAdoptedDetails(applyMerges(applyDeletedIds(expenses, deletedIds), merges), adopted)

  def dismissKey(c: Candidate): String = s"${c.survivor.id}:${c.loser.id}"

  /** The merged receipts after merging `c`. Only a receipt candidate moves a receipt: an orphan has none, and a
    * receipt-on-both candidate's survivor keeps its own receipt while the loser's is deleted.
    */
  def withMergedReceipt(merged: Map[UUID, UUID], c: Candidate): Map[UUID, UUID] =
    c.shape match {
      case Shape.Receipt(loserReceiptId) => merged + (c.survivor.id -> loserReceiptId)
      case Shape.Orphan | Shape.ReceiptOnBoth(_, _) => merged
    }

  /** Flags receipt-on-both candidates whose survivor got its receipt from a merge in this session as ambiguous. Such a
    * candidate was usually an ambiguous sibling of the merged pair (e.g. recurring same-amount purchases), and merging
    * one pair doesn't show the other is a duplicate, so deleting it and its receipt needs review rather than a bulk
    * merge.
    */
  def markMergedSurvivorsAmbiguous(candidates: List[Candidate], merged: Map[UUID, UUID]): List[Candidate] =
    candidates.map { c =>
      c.shape match {
        case Shape.ReceiptOnBoth(_, _) if merged.contains(c.survivor.id) => c.copy(ambiguous = true)
        case _ => c
      }
    }

  /** The selected keys that still belong to a bulk-eligible candidate; merged, dismissed or now-ambiguous ones drop
    * out.
    */
  def effectiveSelection(selected: Set[String], candidates: List[Candidate]): Set[String] =
    candidates.filter(ExpenseDuplicateDetector.isBulkEligible).map(dismissKey).toSet.intersect(selected)

  /** The selection after the select-all checkbox: every bulk-eligible candidate, or nothing. */
  def toggleAllSelection(checked: Boolean, candidates: List[Candidate]): Set[String] =
    if (checked) effectiveSelection(candidates.map(dismissKey).toSet, candidates) else Set.empty

  /** Merges `candidates` one at a time, continuing past failures, and returns the failure messages in order.
    * Unambiguous candidates share no expense ids, so the order never conflicts.
    */
  def runBulkMerge(candidates: List[Candidate], merge: Candidate => Future[Unit])(using
      ec: ExecutionContext
  ): Future[List[String]] =
    candidates.foldLeft(Future.successful(List.empty[String])) { (previous, c) =>
      previous.flatMap { failures =>
        merge(c).transform {
          case Success(_) => Success(failures)
          case Failure(ex) => Success(failures :+ ex.getMessage)
        }
      }
    }

  def bulkFailureMessage(failures: List[String], total: Int): Option[String] =
    failures.headOption.map(first => s"${failures.size} of $total merges failed: $first")

  def bulkMergeSummary(count: Int): String =
    s"$count selected ${if (count == 1) "duplicate" else "duplicates"} will be merged into their bank-row expense. " +
      "Receipts move to the bank-row expense, duplicates without a receipt are deleted, and the bank row's details " +
      "(merchant, category, notes) are kept. Duplicate receipts of bank-row expenses that already have one are " +
      "discarded. This action cannot be undone."

  /** The confirm dialog's title and confirm button label for merging `c`. */
  def mergeDialogLabels(c: Candidate): (String, String) =
    c.shape match {
      case Shape.Receipt(_) => ("Merge expenses?", "Merge")
      case Shape.Orphan => ("Delete duplicate?", "Delete")
      case Shape.ReceiptOnBoth(_, _) => ("Discard duplicate receipt?", "Discard")
    }

  def mergeSummary(c: Candidate, source: MergeDetailsSource): String =
    c.shape match {
      case Shape.Orphan =>
        "The duplicate has no receipt and no bank row, so it will be deleted; any refunds move to the bank-row " +
          "expense, which keeps its details (merchant, category, notes). This action cannot be undone."
      case Shape.ReceiptOnBoth(_, _) =>
        "The bank-row expense keeps its receipt and details (merchant, category, notes). The duplicate expense and " +
          "its receipt (a copy of the same ticket) will be permanently deleted; any refunds move to the bank-row " +
          "expense. This action cannot be undone."
      case Shape.Receipt(_) =>
        val kept = source match {
          case MergeDetailsSource.Row => "bank row's"
          case MergeDetailsSource.Receipt => "receipt's"
        }
        s"The $kept details (merchant, category, notes) will be kept. " +
          "The receipt moves to the bank-row expense and the receipt-only expense is deleted. This action cannot be undone."
    }

  /** Rows with no category are dropped; the coverage rule then catches the mismatch. */
  def toUpdateSplits(splits: List[(Option[UUID], BigDecimal)]): List[UpdateExpense.Split] =
    splits.collect { case (Some(catId), amt) => UpdateExpense.Split(catId, amt) }

  /** The splits the edit form sends. Fails when they don't cover the amount exactly. */
  def updateSplits(
      amount: BigDecimal,
      formSplits: List[(Option[UUID], BigDecimal)]
  ): Either[String, List[UpdateExpense.Split]] = {
    val splits = toUpdateSplits(formSplits)
    ExpenseFormSplits.splitsError(amount, splits.map(_.amount)).toLeft(splits)
  }

  def isFilterActive(dateFilter: String, filterText: String): Boolean =
    dateFilter != DateFilter.Month || filterText.trim.nonEmpty

  def formatDayLabel(date: LocalDate): String = {
    val dayName = date.getDayOfWeek match {
      case DayOfWeek.MONDAY => "Monday"
      case DayOfWeek.TUESDAY => "Tuesday"
      case DayOfWeek.WEDNESDAY => "Wednesday"
      case DayOfWeek.THURSDAY => "Thursday"
      case DayOfWeek.FRIDAY => "Friday"
      case DayOfWeek.SATURDAY => "Saturday"
      case _ => "Sunday"
    }
    s"$dayName, ${date.getDayOfMonth} ${monthNames(date.getMonthValue - 1)} ${date.getYear}"
  }

  private def svgPath(d: String, size: Int = 16): ReactElement =
    slinky.web.svg.svg(
      slinky.web.svg.viewBox := "0 0 24 24",
      slinky.web.svg.width := size.toString,
      slinky.web.svg.height := size.toString,
      slinky.web.svg.fill := "currentColor"
    )(
      slinky.web.svg.path(slinky.web.svg.d := d)
    )

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val history = useHistory()

    val (createFormData, setCreateFormData) = Hooks.useState(ExpenseFormData.initial)
    val createSubmit = SubmitHooks.useSubmit()

    val (error, setError) = Hooks.useState("")
    val (showCreateForm, setShowCreateForm) = Hooks.useState(false)

    val (filterText, setFilterText) = Hooks.useState("")
    val (dateFilter, setDateFilter) = Hooks.useState(DateFilter.Month)
    val (currentPage, setCurrentPage) = Hooks.useState(0)

    val (deletedIds, setDeletedIds) = Hooks.useState[Set[UUID]](Set.empty)
    val (pendingDeleteId, setPendingDeleteId) = Hooks.useState[Option[UUID]](None)
    val (editing, setEditing) = Hooks.useState[Option[(UUID, ExpenseFormData)]](None)
    val editSubmit = SubmitHooks.useSubmit()
    val (hoveredId, setHoveredId) = Hooks.useState[Option[UUID]](None)
    val (mergedReceipts, setMergedReceipts) = Hooks.useState[Map[UUID, UUID]](Map.empty)
    val (dismissed, setDismissed) = Hooks.useState[Set[String]](Set.empty)
    val (adoptedDetails, setAdoptedDetails) = Hooks.useState[Map[UUID, GetExpenses.Response.Expense]](Map.empty)
    val (pendingMerge, setPendingMerge) = Hooks.useState[Option[(Candidate, MergeDetailsSource)]](None)
    val (showDuplicates, setShowDuplicates) = Hooks.useState(false)
    val (selectedDuplicates, setSelectedDuplicates) = Hooks.useState[Set[String]](Set.empty)
    val (showBulkMergeDialog, setShowBulkMergeDialog) = Hooks.useState(false)
    val (bulkMerging, setBulkMerging) = Hooks.useState(false)

    def save[S](
        formData: ExpenseFormData,
        submit: SubmitHooks.Control,
        splits: ExpenseFormData.Resolved => Either[String, S],
        send: (ExpenseFormData.Resolved, S) => Future[Unit]
    )(onSaved: () => Unit): Unit =
      formData.submitRequest match {
        case None =>
          submit.fail(formData.formValidationErrors.headOption.getOrElse("Please fix the highlighted fields"))
        case Some(resolved) =>
          splits(resolved) match {
            case Left(msg) => submit.fail(msg)
            case Right(validSplits) =>
              submit.begin()
              send(resolved, validSplits).onComplete {
                case Success(_) => onSaved(); props.onRefresh()
                case Failure(ex) => submit.fail(ex.getMessage)
              }
          }
      }

    def handleCreate(): Unit =
      save(
        createFormData,
        createSubmit,
        r => ExpenseFormSplits.createSplits(r.amount, r.splits, props.merchants.data, r.merchantId),
        (r, splits) =>
          props.ctx.api.client
            .createExpense(
              CreateExpense.Request(r.amount, r.date, r.time, r.merchantId, r.notes, splits)
            )
            .map(_ => ())
      ) { () =>
        createSubmit.reset()
        setCreateFormData(ExpenseFormData.initial)
        setShowCreateForm(false)
      }

    def handleDelete(expenseId: UUID): Unit =
      props.ctx.api.client
        .deleteExpense(expenseId)
        .onComplete {
          case Success(_) => setDeletedIds(deletedIds + expenseId)
          case Failure(ex) => setError(ex.getMessage)
        }

    def requestMerge(c: Candidate, source: MergeDetailsSource): Future[Unit] =
      props.ctx.api.client
        .mergeExpenses(
          c.survivor.id,
          MergeExpenses.Request(
            loserId = c.loser.id,
            keepDetailsFrom = source,
            shape = ExpenseDuplicateDetector.mergeShape(c.shape)
          )
        )
        .map { _ =>
          setDeletedIds(_ + c.loser.id)
          setMergedReceipts(withMergedReceipt(_, c))
          source match {
            case MergeDetailsSource.Row => ()
            case MergeDetailsSource.Receipt => setAdoptedDetails(_ + (c.survivor.id -> c.loser))
          }
        }

    def handleMerge(c: Candidate, source: MergeDetailsSource): Unit =
      requestMerge(c, source).failed.foreach(ex => setError(ex.getMessage))

    // The merge controls stay disabled until the run ends, so a second run can't resubmit the same pairs.
    def handleBulkMerge(candidates: List[Candidate]): Unit = {
      setBulkMerging(true)
      runBulkMerge(candidates, c => requestMerge(c, MergeDetailsSource.Row))
        .foreach { failures =>
          bulkFailureMessage(failures, total = candidates.size).foreach(setError)
          setSelectedDuplicates(Set.empty)
          setBulkMerging(false)
        }
    }

    def handleUpdate(expenseId: UUID, formData: ExpenseFormData): Unit =
      save(
        formData,
        editSubmit,
        r => updateSplits(r.amount, r.splits),
        (r, splits) =>
          props.ctx.api.client
            .updateExpense(
              expenseId,
              UpdateExpense.Request(r.amount, r.date, r.time, r.merchantId, r.notes, splits)
            )
            .map(_ => ())
      ) { () =>
        editSubmit.reset()
        setEditing(None)
      }

    def cancelEdit(): Unit = {
      editSubmit.reset()
      setEditing(None)
    }

    type Update = (ExpenseFormData => ExpenseFormData) => Unit

    val updateCreate: Update = f => setCreateFormData(cur => f(cur))
    val updateEdit: Update = f => setEditing(cur => cur.map { case (id, fd) => (id, f(fd)) })

    def splitRemainder(formData: ExpenseFormData): BigDecimal = {
      val total = formData.amount.valueOpt.map(_.value).getOrElse(BigDecimal(0))
      val used = formData.splits.flatMap(_.amount.valueOpt.map(_.value)).sum
      total - used
    }

    def submitError(submit: SubmitHooks.Control): ReactElement =
      submit.error match {
        case Some(msg) => mui.Typography(msg).color("error")
        case None => Fragment()
      }

    /** Amount, date, time, merchant and notes inputs, shared by the create and the edit form. */
    def formFields(
        formData: ExpenseFormData,
        disabled: Boolean,
        update: Update,
        autoFocusAmount: Boolean = false
    ): Seq[ReactElement] = Seq(
      mui.Box.sx(new CSSPropertiesUtils { width = "140px" })(
        AmountInput.component(
          AmountInput.Props(
            field = formData.amount,
            disabled = disabled,
            autoFocus = autoFocusAmount,
            onChange = (value: ValidationResult[PositiveBigDecimal]) =>
              update(cur => cur.copy(amount = cur.amount.updated(value)))
          )
        )
      ),
      mui.Box.sx(new CSSPropertiesUtils { width = "170px" })(
        DateInput.component(
          DateInput.Props(
            field = formData.date,
            disabled = disabled,
            onChange =
              (value: ValidationResult[ExpLocalDate]) => update(cur => cur.copy(date = cur.date.updated(value)))
          )
        )
      ),
      OptionalTimeInput.component(
        OptionalTimeInput.Props(
          field = formData.time,
          disabled = disabled,
          onChange =
            (value: ValidationResult[Option[ExpLocalTime]]) => update(cur => cur.copy(time = cur.time.updated(value)))
        )
      ),
      mui.Box.sx(new CSSPropertiesUtils { minWidth = "160px" })(
        OptionalIdSelectInput.component(
          OptionalIdSelectInput.Props(
            field = formData.merchantId,
            options = props.merchants.data.map(m => m.id.toString -> m.name),
            onChange = (value: ValidationResult[Option[UUID]]) =>
              update(cur => cur.copy(merchantId = cur.merchantId.updated(value))),
            disabled = disabled,
            noneLabel = Some("(none)")
          )
        )
      ),
      OptionalNotesInput.component(
        OptionalNotesInput.Props(
          field = formData.notes,
          disabled = disabled,
          onChange =
            (value: ValidationResult[Option[ExpNotes]]) => update(cur => cur.copy(notes = cur.notes.updated(value)))
        )
      )
    )

    def splitRow(row: ExpenseSplitRow, disabled: Boolean, update: Update): ReactElement =
      mui.ListItem
        .normal()(
          Container(
            flexDirection = FlexDirection.row,
            alignItems = Container.Alignment.center,
            child = Fragment(
              mui.Box.sx(new CSSPropertiesUtils { minWidth = "160px" })(
                OptionalIdSelectInput.component(
                  OptionalIdSelectInput.Props(
                    field = row.category,
                    options = props.categories.data.map(c => c.id.toString -> c.name),
                    onChange = (value: ValidationResult[Option[UUID]]) =>
                      update(_.updateSplit(row.key, r => r.copy(category = r.category.updated(value)))),
                    disabled = disabled,
                    noneLabel = Some("(none)")
                  )
                )
              ),
              AmountInput.component(
                AmountInput.Props(
                  field = row.amount,
                  disabled = disabled,
                  onChange = (value: ValidationResult[PositiveBigDecimal]) =>
                    update(_.updateSplit(row.key, r => r.copy(amount = r.amount.updated(value))))
                )
              ),
              mui.Button
                .normal()("Remove")
                .disabled(disabled)
                .onClick(_ => update(_.removeSplit(row.key)))
            )
          )
        )
        .withKey(row.key.toString)
        .build

    def splitsSection(formData: ExpenseFormData, disabled: Boolean, update: Update): ReactElement = {
      val rows = formData.splits.map(row => splitRow(row, disabled, update))
      val rem = splitRemainder(formData)
      Container(
        flexDirection = FlexDirection.column,
        child = Fragment(
          mui.List(rows),
          Container(
            flexDirection = FlexDirection.row,
            alignItems = Container.Alignment.center,
            child = Fragment(
              mui.Button.normal()("Add split").disabled(disabled).onClick(_ => update(_.addSplit)),
              mui.Typography(s"Uncategorized: $rem").sx(remainderStyling)
            )
          )
        )
      )
    }

    // ── computed data ──────────────────────────────────────────────────────────

    val sortedExpenses: List[GetExpenses.Response.Expense] = sortExpenses(props.expenses.data)
    val mergeStateKey: String =
      s"${props.expenses.data.size}|${deletedIds.map(_.toString).toList.sorted.mkString(",")}|" +
        mergedReceipts.map { case (s, r) => s"$s:$r" }.toList.sorted.mkString(",") + "|" +
        adoptedDetails.map { case (s, l) => s"$s:${l.id}" }.toList.sorted.mkString(",")
    val (visibleExpenses, allCandidates) = SafeHooks.useMemo(mergeStateKey) { () =>
      val merged = applyMergeState(sortedExpenses, deletedIds, mergedReceipts, adoptedDetails)
      (merged, markMergedSurvivorsAmbiguous(ExpenseDuplicateDetector.detect(merged), mergedReceipts))
    }
    val duplicateCandidates: List[Candidate] = allCandidates.filterNot(c => dismissed.contains(dismissKey(c)))
    val selectedKeys: Set[String] = effectiveSelection(selectedDuplicates, duplicateCandidates)
    val dateFilteredExpenses: List[GetExpenses.Response.Expense] =
      applyDateFilter(visibleExpenses, dateFilter, LocalDate.now())

    val filteredExpenses: List[GetExpenses.Response.Expense] =
      if (filterText.trim.isEmpty) dateFilteredExpenses
      else {
        val q = filterText.trim.toLowerCase
        dateFilteredExpenses.filter { e =>
          e.merchantName.exists(_.toLowerCase.contains(q)) ||
          e.notes.exists(_.toLowerCase.contains(q)) ||
          e.date.toString.contains(q) ||
          formatAmount(e.amount).contains(q) ||
          e.splits
            .flatMap(s => props.categories.data.find(_.id == s.categoryId))
            .exists(_.name.toLowerCase.contains(q))
        }
      }

    val pageSize = 100
    val totalPages = math.max(1, math.ceil(filteredExpenses.size.toDouble / pageSize).toInt)
    val safePage = math.min(currentPage, totalPages - 1)
    val pagedExpenses: List[GetExpenses.Response.Expense] =
      if (dateFilter == DateFilter.All)
        filteredExpenses.slice(safePage * pageSize, (safePage + 1) * pageSize)
      else
        filteredExpenses

    val groupedExpenses: List[(LocalDate, List[GetExpenses.Response.Expense])] =
      pagedExpenses
        .groupBy(_.date)
        .toList
        .sortBy(-_._1.toEpochDay)

    val filteredTotal: BigDecimal = filteredExpenses.map(_.amount).sum
    val summaryLabel: String =
      if (filterText.trim.isEmpty)
        s"${filteredExpenses.size} expenses ${dateFilter match {
            case DateFilter.Today => "today"
            case DateFilter.Week => "this week"
            case DateFilter.Month => "this month"
            case DateFilter.LastMonth => "last month"
            case _ => ""
          }}".trim
      else
        s"${filteredExpenses.size} of ${visibleExpenses.size} expenses"

    // ── sub-renders ────────────────────────────────────────────────────────────

    val dateFilterHandler: (slinky.web.SyntheticMouseEvent[org.scalajs.dom.HTMLElement], Any) => Unit =
      (_, v) =>
        Option(v.asInstanceOf[String]).foreach { s =>
          setDateFilter(s)
          setCurrentPage(0)
        }

    val clearFilters: () => Unit = () => {
      setDateFilter(DateFilter.Month); setFilterText(""); setCurrentPage(0)
    }

    // Page header: title + search + upload button
    val pageHeader: ReactElement = mui.Box.sx(new CSSPropertiesUtils {
      display = "flex"
      alignItems = "center"
      gap = "20px"
      marginBottom = "26px"
    })(
      mui
        .Typography("Expenses")
        .variant("h5")
        .sx(new CSSPropertiesUtils {
          fontWeight = "800"
          fontSize = "26px"
          letterSpacing = "-0.02em"
          flexShrink = "0"
        }),
      mui.TextField
        .outlined()
        .size("small")
        .label("Search merchants, amounts, descriptions…")
        .value(filterText)
        .onChange { e =>
          setFilterText(e.target.asInstanceOf[dom.HTMLInputElement].value)
          setCurrentPage(0)
        }
        .sx(new CSSPropertiesUtils { flex = "1 1 auto" }),
      if (isFilterActive(dateFilter = dateFilter, filterText = filterText))
        mui
          .Link("× Clear")
          .onClick(_ => clearFilters())
          .sx(new CSSPropertiesUtils { fontSize = "13px"; cursor = "pointer"; flexShrink = "0" })
          .build
      else
        Fragment(),
      mui.Button
        .normal()("Upload receipt")
        .variant("contained")
        .size("small")
        .onClick(_ => history.push("/receipts"))
        .sx(new CSSPropertiesUtils { flexShrink = "0"; whiteSpace = "nowrap" })
    )

    // Toolbar: date segmented + add expense + summary
    val toolbar: ReactElement = mui.Box.sx(new CSSPropertiesUtils {
      display = "flex"
      alignItems = "center"
      gap = "16px"
      marginBottom = "18px"
    })(
      mui
        .ToggleButtonGroup(
          mui.ToggleButton.normal(DateFilter.Today)("Today"),
          mui.ToggleButton.normal(DateFilter.Week)("Week"),
          mui.ToggleButton.normal(DateFilter.Month)("Month"),
          mui.ToggleButton.normal(DateFilter.LastMonth)("Last month"),
          mui.ToggleButton.normal(DateFilter.All)("All")
        )
        .exclusive(true)
        .size("small")
        .value(dateFilter)
        .onChange(dateFilterHandler)
        .build,
      if (showCreateForm)
        Fragment()
      else
        mui.Button
          .normal()("+ Add expense")
          .variant("outlined")
          .size("small")
          .onClick(_ => setShowCreateForm(true)),
      if (duplicateCandidates.isEmpty)
        Fragment()
      else
        mui.Button
          .normal()(s"Possible duplicates (${duplicateCandidates.size})")
          .variant(if (showDuplicates) "contained" else "outlined")
          .size("small")
          .onClick(_ => setShowDuplicates(!showDuplicates)),
      // right-side summary
      mui.Box.sx(new CSSPropertiesUtils {
        marginLeft = "auto"; display = "flex"; gap = "18px"; alignItems = "baseline"
      })(
        mui
          .Typography(summaryLabel)
          .sx(new CSSPropertiesUtils {
            fontSize = "13.5px"
            color = "var(--lc-text-2)"
            fontFamily = "\"IBM Plex Sans\", sans-serif"
          }),
        mui
          .Typography(s"€${formatAmount(filteredTotal)}")
          .sx(new CSSPropertiesUtils {
            fontFamily = "\"IBM Plex Mono\", monospace"
            fontSize = "15px"
            fontWeight = "600"
            color = "var(--lc-text)"
          })
      )
    )

    val createForm: ReactElement =
      if (showCreateForm)
        mui.Box.sx(new CSSPropertiesUtils {
          border = "1px solid var(--lc-brand)"
          borderRadius = "8px"
          padding = "16px 24px"
          marginBottom = "16px"
          backgroundColor = "var(--lc-paper)"
        })(
          form(onSubmit := { e =>
            e.preventDefault()
            handleCreate()
          })(
            Container(
              flexDirection = FlexDirection.column,
              child = Fragment(
                Container(
                  flexDirection = FlexDirection.row,
                  alignItems = Container.Alignment.center,
                  child = Fragment(
                    Fragment(formFields(createFormData, createSubmit.isSubmitting, updateCreate)*),
                    mui.Button
                      .normal()("Add")
                      .variant("contained")
                      .disabled(createSubmit.buttonDisabled(createFormData.isValid))
                      .`type`("submit"),
                    mui.Button
                      .normal()("Cancel")
                      .onClick { _ =>
                        createSubmit.reset()
                        setShowCreateForm(false)
                      }
                  )
                ),
                splitsSection(createFormData, createSubmit.isSubmitting, updateCreate),
                submitError(createSubmit)
              )
            )
          )
        )
      else
        Fragment()

    val errorMsg: ReactElement = mui.Typography(error).color("error")

    // ── expense rows ───────────────────────────────────────────────────────────

    val expenseRows: List[ReactElement] = groupedExpenses.flatMap { case (date, dayExpenses) =>
      val dayTotal = dayExpenses.map(_.amount).sum
      val dayTotalStr = s"€${formatAmount(dayTotal)}"
      val dayCountLabel = if (dayExpenses.size == 1) "1 expense" else s"${dayExpenses.size} expenses"

      val dayHeader: ReactElement = mui.Box
        .sx(new CSSPropertiesUtils {
          display = "flex"
          alignItems = "baseline"
          gap = "12px"
          padding = "10px 20px"
          backgroundColor = "var(--lc-tint)"
          borderBottom = "1px solid var(--lc-border)"
        })(
          mui
            .Typography(formatDayLabel(date))
            .sx(new CSSPropertiesUtils {
              fontSize = "12.5px"
              fontWeight = "700"
              letterSpacing = "0.02em"
              color = "var(--lc-text)"
            }),
          mui
            .Typography(dayCountLabel)
            .sx(new CSSPropertiesUtils {
              fontSize = "12px"
              color = "var(--lc-text-2)"
            }),
          mui.Box.sx(new CSSPropertiesUtils { marginLeft = "auto" })(
            mui
              .Typography(dayTotalStr)
              .sx(new CSSPropertiesUtils {
                fontFamily = "\"IBM Plex Mono\", monospace"
                fontSize = "13px"
                color = "var(--lc-text-2)"
                fontWeight = "600"
              })
          )
        )
        .withKey(s"dh-$date")
        .build

      val rows: List[ReactElement] = dayExpenses.map { e =>
        val editingForm = editing.collect { case (id, fd) if id == e.id => fd }
        val isHovered = hoveredId.contains(e.id)
        val firstCat = e.splits.headOption.flatMap(s => props.categories.data.find(_.id == s.categoryId))
        val hasReceipt = e.receiptIds.nonEmpty

        val receiptCell: ReactElement = {
          val iconPath =
            if (hasReceipt)
              "M19.5 3.5 18 2l-1.5 1.5L15 2l-1.5 1.5L12 2l-1.5 1.5L9 2 7.5 3.5 6 2v20l1.5-1.5L9 22l1.5-1.5L12 22l1.5-1.5L15 22l1.5-1.5L18 22l1.5-1.5V2l-1.5 1.5zM18 18H6v-2h12v2zm0-5H6v-2h12v2zm0-5H6V6h12v2z"
            else
              "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"
          val (bgColor, fgColor) =
            if (hasReceipt) ("var(--lc-tint)", "var(--lc-brand)")
            else ("transparent", "var(--lc-text-2)")
          mui.Box
            .sx(new CSSPropertiesUtils {
              width = "30px"
              height = "30px"
              borderRadius = "8px"
              display = "grid"
              placeItems = "center"
              cursor = "pointer"
              backgroundColor = bgColor
              color = fgColor
              border = if (hasReceipt) "1px solid transparent" else "1px dashed var(--lc-border)"
              flexShrink = "0"
            })
            .title(if (hasReceipt) "View receipt" else "Attach receipt")
            .onClick { ev =>
              ev.stopPropagation()
              if (hasReceipt)
                dom.window.open(s"/api/receipts/${e.receiptIds.head}/file", "_blank")
              else
                history.push("/receipts")
            }(svgPath(iconPath))
        }

        val merchantCell: ReactElement = mui.Box.sx(new CSSPropertiesUtils {
          minWidth = "0"
        })(
          mui
            .Typography(e.merchantName.getOrElse("—"))
            .sx(new CSSPropertiesUtils {
              fontSize = "14.5px"
              fontWeight = "700"
              letterSpacing = "-0.01em"
              color = "var(--lc-text)"
              whiteSpace = "nowrap"
              overflow = "hidden"
              textOverflow = "ellipsis"
            }),
          mui.Box.sx(new CSSPropertiesUtils {
            display = "flex"
            alignItems = "center"
            gap = "6px"
            marginTop = "2px"
          })(
            e.notes
              .filter(_.nonEmpty)
              .fold[ReactElement](Fragment()) { n =>
                Fragment(
                  mui
                    .Typography(n)
                    .title(n)
                    .sx(new CSSPropertiesUtils {
                      fontSize = "12.5px"
                      color = "var(--lc-text-2)"
                      whiteSpace = "nowrap"
                      overflow = "hidden"
                      textOverflow = "ellipsis"
                    }),
                  mui
                    .Typography("·")
                    .sx(new CSSPropertiesUtils {
                      fontSize = "12px"
                      color = "var(--lc-text-2)"
                      flexShrink = "0"
                    })
                )
              },
            e.time.fold[ReactElement](Fragment()) { t =>
              mui
                .Typography(t.toString)
                .sx(new CSSPropertiesUtils {
                  fontFamily = "\"IBM Plex Mono\", monospace"
                  fontSize = "12px"
                  color = "var(--lc-text-2)"
                  flexShrink = "0"
                })
            }
          )
        )

        val categoryCell: ReactElement = firstCat.fold[ReactElement](Fragment()) { cat =>
          mui.Chip
            .label(cat.name)
            .size("small")
            .sx(new CSSPropertiesUtils {
              height = "22px"
              fontSize = "12px"
              fontWeight = "600"
              backgroundColor = "var(--lc-tint)"
              color = "var(--lc-brand-dark)"
              justifySelf = "start"
            })
        }

        val amountCell: ReactElement = mui
          .Typography(s"€${formatAmount(e.amount)}")
          .sx(new CSSPropertiesUtils {
            fontFamily = "\"IBM Plex Mono\", monospace"
            fontVariantNumeric = "tabular-nums"
            fontSize = "15px"
            fontWeight = "600"
            color = "var(--lc-error)"
            justifySelf = "end"
            whiteSpace = "nowrap"
          })

        val kebabCell: ReactElement = mui.Button
          .normal()(
            svgPath(
              "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z",
              18
            )
          )
          .size("small")
          .sx(new CSSPropertiesUtils {
            minWidth = "0"
            padding = "4px"
            color = "var(--lc-error)"
            opacity = if (isHovered) "1" else "0"
            transition = "opacity 0.12s"
            justifySelf = "center"
          })
          .title("Delete")
          .onClick { ev =>
            ev.stopPropagation()
            setPendingDeleteId(Some(e.id))
          }

        val displayRow: ReactElement =
          mui.Box
            .sx(new CSSPropertiesUtils {
              display = "grid"
              gridTemplateColumns = "40px minmax(0,1fr) 128px 116px 36px"
              alignItems = "center"
              columnGap = "18px"
              padding = "14px 20px"
              cursor = "pointer"
              width = "100%"
            })
            .onClick { _ =>
              editSubmit.reset()
              setEditing(Some((e.id, ExpenseFormData.fromExpense(e))))
            }
            .onMouseEnter(_ => setHoveredId(Some(e.id)))
            .onMouseLeave(_ => setHoveredId(None))(
              receiptCell,
              merchantCell,
              categoryCell,
              amountCell,
              kebabCell
            )

        def editRow(editForm: ExpenseFormData): ReactElement =
          form(
            onSubmit := { ev =>
              ev.preventDefault(); handleUpdate(e.id, editForm)
            },
            onKeyDown := { ev => if (ev.key == "Escape") cancelEdit() }
          )(
            Container(
              flexDirection = FlexDirection.column,
              child = Fragment(
                Container(
                  flexDirection = FlexDirection.row,
                  alignItems = Container.Alignment.center,
                  child = Fragment(
                    Fragment(
                      formFields(
                        editForm,
                        disabled = editSubmit.isSubmitting,
                        update = updateEdit,
                        autoFocusAmount = true
                      )*
                    ),
                    mui.Button
                      .normal()("Save")
                      .disabled(editSubmit.buttonDisabled(editForm.isValid))
                      .`type`("submit"),
                    mui.Button.normal()("Cancel").onClick(_ => cancelEdit())
                  )
                ),
                splitsSection(editForm, editSubmit.isSubmitting, updateEdit),
                submitError(editSubmit)
              )
            )
          )

        mui.Box
          .sx(new CSSPropertiesUtils {
            borderBottom = "1px solid var(--lc-border)"
          })(editingForm.fold(displayRow)(editRow))
          .withKey(e.id.toString)
          .build
      }

      dayHeader :: rows
    }

    val emptyState: ReactElement =
      if (filteredExpenses.isEmpty)
        mui
          .Typography(if (filterText.trim.nonEmpty) "No expenses matching the search" else "No expenses yet")
          .sx(new CSSPropertiesUtils {
            padding = "24px"
            color = "var(--lc-text-2)"
            fontFamily = "\"IBM Plex Sans\", sans-serif"
          })
      else
        Fragment()

    val expenseList: ReactElement = mui.Box.sx(new CSSPropertiesUtils {
      border = "1px solid var(--lc-border)"
      borderRadius = "14px"
      overflow = "hidden"
      backgroundColor = "var(--lc-paper)"
    })(
      Fragment(expenseRows*),
      emptyState
    )

    val paginationControls: ReactElement =
      if (dateFilter == DateFilter.All && filteredExpenses.size > pageSize)
        mui.Box.sx(new CSSPropertiesUtils {
          display = "flex"
          alignItems = "center"
          justifyContent = "center"
          gap = "16px"
          marginTop = "12px"
        })(
          mui.Button
            .normal()("← Prev")
            .size("small")
            .variant("outlined")
            .disabled(safePage == 0)
            .onClick(_ => setCurrentPage(safePage - 1)),
          mui
            .Typography(s"Page ${safePage + 1} of $totalPages")
            .sx(new CSSPropertiesUtils { fontSize = "13px"; color = "var(--lc-text-2)" }),
          mui.Button
            .normal()("Next →")
            .size("small")
            .variant("outlined")
            .disabled(safePage >= totalPages - 1)
            .onClick(_ => setCurrentPage(safePage + 1))
        )
      else
        Fragment()

    // Legend
    val legend: ReactElement = mui.Box.sx(new CSSPropertiesUtils {
      display = "flex"
      gap = "22px"
      marginTop = "18px"
      color = "var(--lc-text-2)"
      fontSize = "12.5px"
      alignItems = "center"
    })(
      mui.Box.sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "7px" })(
        mui.Box.sx(new CSSPropertiesUtils {
          width = "22px"; height = "22px"; borderRadius = "6px"; display = "grid"; placeItems = "center"
          backgroundColor = "var(--lc-tint)"; color = "var(--lc-brand)"
        })(
          svgPath(
            "M19.5 3.5 18 2l-1.5 1.5L15 2l-1.5 1.5L12 2l-1.5 1.5L9 2 7.5 3.5 6 2v20l1.5-1.5L9 22l1.5-1.5L12 22l1.5-1.5L15 22l1.5-1.5L18 22l1.5-1.5V2l-1.5 1.5zM18 18H6v-2h12v2zm0-5H6v-2h12v2zm0-5H6V6h12v2z",
            14
          )
        ),
        mui
          .Typography("Receipt attached — click to view")
          .sx(new CSSPropertiesUtils { fontSize = "12.5px"; color = "var(--lc-text-2)" })
      ),
      mui.Box.sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "7px" })(
        mui.Box.sx(new CSSPropertiesUtils {
          width = "22px"; height = "22px"; borderRadius = "6px"; display = "grid"; placeItems = "center"
          border = "1px dashed var(--lc-border)"; color = "var(--lc-text-2)"
        })(svgPath("M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z", 13)),
        mui
          .Typography("No receipt — click to attach")
          .sx(new CSSPropertiesUtils { fontSize = "12.5px"; color = "var(--lc-text-2)" })
      )
    )

    val deleteDialog: ReactElement =
      mui
        .Dialog(pendingDeleteId.isDefined)(
          mui.DialogTitle("Delete expense?"),
          mui.DialogContent(
            mui.DialogContentText("This action cannot be undone.")
          ),
          mui.DialogActions(
            mui.Button.normal()("Cancel").onClick(_ => setPendingDeleteId(None)),
            mui.Button
              .normal()("Delete")
              .color("error")
              .onClick { _ =>
                pendingDeleteId.foreach(handleDelete)
                setPendingDeleteId(None)
              }
          )
        )
        .onClose((_, _) => setPendingDeleteId(None))
        .build

    val duplicatesPanel: ReactElement =
      if (showDuplicates && duplicateCandidates.nonEmpty)
        ExpenseDuplicatesPanel(
          ctx = props.ctx,
          candidates = duplicateCandidates,
          categories = props.categories,
          receiptFilenames = props.receiptFilenames,
          onMerge = (c, source) => setPendingMerge(Some(c -> source)),
          onDismiss = c => setDismissed(_ + dismissKey(c)),
          onError = setError,
          selected = selectedKeys,
          onToggleSelected = (c, checked) =>
            setSelectedDuplicates(current => if (checked) current + dismissKey(c) else current - dismissKey(c)),
          onToggleAll = checked =>
            setSelectedDuplicates(
              toggleAllSelection(checked, duplicateCandidates)
            ),
          onBulkMerge = () => setShowBulkMergeDialog(true),
          bulkMerging = bulkMerging
        )
      else Fragment()

    val (dialogTitle, confirmLabel) = pendingMerge.fold(("Merge expenses?", "Merge"))((c, _) => mergeDialogLabels(c))
    val mergeDialog: ReactElement =
      mui
        .Dialog(pendingMerge.isDefined)(
          mui.DialogTitle(dialogTitle),
          mui.DialogContent(
            pendingMerge.fold[ReactElement](Fragment()) { case (c, source) =>
              Fragment(
                ExpenseDuplicatesPanel.columns(c, props.categories),
                ExpenseDuplicatesPanel.receiptButtons(c, props.ctx, props.receiptFilenames, setError),
                mui.DialogContentText(mergeSummary(c, source)).sx(new CSSPropertiesUtils { marginTop = "16px" })
              )
            }
          ),
          mui.DialogActions(
            mui.Button.normal()("Cancel").onClick(_ => setPendingMerge(None)),
            mui.Button
              .normal()(confirmLabel)
              .color("error")
              .onClick { _ =>
                pendingMerge.foreach { case (c, source) => handleMerge(c, source) }
                setPendingMerge(None)
              }
          )
        )
        .onClose((_, _) => setPendingMerge(None))
        .build

    val bulkMergeDialog: ReactElement =
      mui
        .Dialog(showBulkMergeDialog)(
          mui.DialogTitle(s"Merge ${selectedKeys.size} selected?"),
          mui.DialogContent(mui.DialogContentText(bulkMergeSummary(selectedKeys.size))),
          mui.DialogActions(
            mui.Button.normal()("Cancel").onClick(_ => setShowBulkMergeDialog(false)),
            mui.Button
              .normal()("Merge")
              .color("error")
              .onClick { _ =>
                if (!bulkMerging)
                  handleBulkMerge(duplicateCandidates.filter(c => selectedKeys.contains(dismissKey(c))))
                setShowBulkMergeDialog(false)
              }
          )
        )
        .onClose((_, _) => setShowBulkMergeDialog(false))
        .build

    Fragment(
      pageHeader,
      toolbar,
      createForm,
      errorMsg,
      duplicatesPanel,
      expenseList,
      paginationControls,
      legend,
      deleteDialog,
      mergeDialog,
      bulkMergeDialog
    )
  }
}
