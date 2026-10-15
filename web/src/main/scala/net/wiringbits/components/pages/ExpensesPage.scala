package net.wiringbits.components.pages

import com.olvind.mui.csstype.mod.Property.FlexDirection
import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.components.widgets.Loader
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.expenses.{CreateExpense, GetExpenses, UpdateExpense}
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.models.User
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.components.core.AsyncComponent
import net.wiringbits.webapp.utils.slinkyUtils.components.core.widgets.Container
import net.wiringbits.webapp.utils.slinkyUtils.core.GenericHooks
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.useHistory
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.web.html.{form, onKeyDown, onSubmit}

import java.time.{DayOfWeek, LocalDate, LocalTime}
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

object ExpensesPage {
  def apply(ctx: AppContext, user: User): KeyAddingStage =
    component(Props(ctx = ctx, user = user))

  case class Props(ctx: AppContext, user: User)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val (timesRefreshing, forceRefresh) = GenericHooks.useForceRefresh

    AsyncComponent[GetExpenses.Response](
      fetch = () => props.ctx.api.client.getExpenses,
      render = expenses =>
        AsyncComponent[GetMerchants.Response](
          fetch = () => props.ctx.api.client.getMerchants,
          render = merchants =>
            AsyncComponent[GetCategories.Response](
              fetch = () => props.ctx.api.client.getCategories,
              render = categories => ExpenseList(props.ctx, expenses, merchants, categories, () => forceRefresh()),
              progressIndicator = () => Loader(props.ctx),
              watchedObjects = List(timesRefreshing)
            ),
          progressIndicator = () => Loader(props.ctx),
          watchedObjects = List(timesRefreshing)
        ),
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
      onRefresh: () => Unit
  ): KeyAddingStage =
    component(Props(ctx, expenses, merchants, categories, onRefresh))

  case class Props(
      ctx: AppContext,
      expenses: GetExpenses.Response,
      merchants: GetMerchants.Response,
      categories: GetCategories.Response,
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

    val (newAmount, setNewAmount) = Hooks.useState("")
    val (newDate, setNewDate) = Hooks.useState("")
    val (newTime, setNewTime) = Hooks.useState("")
    val (newMerchantId, setNewMerchantId) = Hooks.useState("")
    val (newNotes, setNewNotes) = Hooks.useState("")
    val (newSplits, setNewSplits) = Hooks.useState[List[(String, String)]](Nil)

    val (error, setError) = Hooks.useState("")
    val (showCreateForm, setShowCreateForm) = Hooks.useState(false)

    val (filterText, setFilterText) = Hooks.useState("")
    val (dateFilter, setDateFilter) = Hooks.useState(DateFilter.Month)
    val (currentPage, setCurrentPage) = Hooks.useState(0)

    val (deletedIds, setDeletedIds) = Hooks.useState[Set[UUID]](Set.empty)
    val (pendingDeleteId, setPendingDeleteId) = Hooks.useState[Option[UUID]](None)
    val (editingId, setEditingId) = Hooks.useState[Option[UUID]](None)
    val (editAmount, setEditAmount) = Hooks.useState("")
    val (editDate, setEditDate) = Hooks.useState("")
    val (editTime, setEditTime) = Hooks.useState("")
    val (editMerchantId, setEditMerchantId) = Hooks.useState("")
    val (editNotes, setEditNotes) = Hooks.useState("")
    val (editSplits, setEditSplits) = Hooks.useState[List[(String, String)]](Nil)
    val (hoveredId, setHoveredId) = Hooks.useState[Option[UUID]](None)

    def parseSplits(splits: List[(String, String)]): List[CreateExpense.Split] =
      splits.flatMap { case (catId, amt) =>
        if (catId.nonEmpty && amt.nonEmpty)
          Try(CreateExpense.Split(UUID.fromString(catId), BigDecimal(amt))).toOption
        else None
      }

    def toUpdateSplits(splits: List[(String, String)]): List[UpdateExpense.Split] =
      splits.flatMap { case (catId, amt) =>
        if (catId.nonEmpty && amt.nonEmpty)
          Try(UpdateExpense.Split(UUID.fromString(catId), BigDecimal(amt))).toOption
        else None
      }

    def merchantDefaultSplits(merchantIdStr: String, amount: BigDecimal): List[CreateExpense.Split] =
      Try(UUID.fromString(merchantIdStr)).toOption
        .flatMap(id => props.merchants.data.find(_.id == id))
        .flatMap(_.categoryId)
        .map(catId => List(CreateExpense.Split(catId, amount)))
        .getOrElse(Nil)

    def handleCreate(): Unit = {
      (Try(BigDecimal(newAmount)).toOption, Try(LocalDate.parse(newDate)).toOption) match {
        case (None, _) => setError("Invalid amount")
        case (_, None) => setError("Invalid date (use YYYY-MM-DD)")
        case (Some(amount), Some(date)) =>
          val time = if (newTime.isEmpty) None else Try(LocalTime.parse(newTime)).toOption
          val merchantId = if (newMerchantId.isEmpty) None else Try(UUID.fromString(newMerchantId)).toOption
          val notes = Option(newNotes.trim).filter(_.nonEmpty)
          val explicit = parseSplits(newSplits)
          val splits = if (explicit.nonEmpty) explicit else merchantDefaultSplits(newMerchantId, amount)
          props.ctx.api.client
            .createExpense(CreateExpense.Request(amount, date, time, merchantId, notes, splits))
            .onComplete {
              case Success(_) =>
                setNewAmount(""); setNewDate(""); setNewTime(""); setNewMerchantId("")
                setNewNotes(""); setNewSplits(Nil); setError("")
                setShowCreateForm(false)
                props.onRefresh()
              case Failure(ex) => setError(ex.getMessage)
            }
      }
    }

    def handleDelete(expenseId: UUID): Unit =
      props.ctx.api.client
        .deleteExpense(expenseId)
        .onComplete {
          case Success(_) => setDeletedIds(deletedIds + expenseId)
          case Failure(ex) => setError(ex.getMessage)
        }

    def handleUpdate(expenseId: UUID): Unit = {
      (Try(BigDecimal(editAmount)).toOption, Try(LocalDate.parse(editDate)).toOption) match {
        case (None, _) => setError("Invalid amount")
        case (_, None) => setError("Invalid date (use YYYY-MM-DD)")
        case (Some(amount), Some(date)) =>
          val time = if (editTime.isEmpty) None else Try(LocalTime.parse(editTime)).toOption
          val merchantId = if (editMerchantId.isEmpty) None else Try(UUID.fromString(editMerchantId)).toOption
          val notes = Option(editNotes.trim).filter(_.nonEmpty)
          props.ctx.api.client
            .updateExpense(
              expenseId,
              UpdateExpense.Request(amount, date, time, merchantId, notes, toUpdateSplits(editSplits))
            )
            .onComplete {
              case Success(_) =>
                setEditingId(None); setEditAmount(""); setEditDate(""); setEditTime("")
                setEditMerchantId(""); setEditNotes(""); setEditSplits(Nil); setError("")
                props.onRefresh()
              case Failure(ex) => setError(ex.getMessage)
            }
      }
    }

    def splitRemainder(amountStr: String, splits: List[(String, String)]): BigDecimal = {
      val total = Try(BigDecimal(amountStr)).getOrElse(BigDecimal(0))
      val used = splits.flatMap { case (_, amt) => Try(BigDecimal(amt)).toOption }.sum
      total - used
    }

    def merchantOptions(selectedId: String, setId: String => Unit): ReactElement = {
      val none = mui.MenuItem.normal()("(none)").value("").withKey("none").build
      val items = none +: props.merchants.data.map(m =>
        mui.MenuItem.normal()(m.name).value(m.id.toString).withKey(m.id.toString).build
      )
      mui.TextField
        .outlined()
        .select(true)
        .label("Merchant (optional)")
        .value(selectedId)
        .onChange(e => setId(e.target.asInstanceOf[dom.HTMLInputElement].value))
        .sx(new CSSPropertiesUtils { minWidth = "160px" })(items*)
    }

    def splitRow(
        catId: String,
        amt: String,
        i: Int,
        splits: List[(String, String)],
        setSplits: List[(String, String)] => Unit
    ): ReactElement = {
      val catNone = mui.MenuItem.normal()("(none)").value("").withKey("none").build
      val catItems = catNone +: props.categories.data.map(c =>
        mui.MenuItem.normal()(c.name).value(c.id.toString).withKey(c.id.toString).build
      )
      mui.ListItem
        .normal()(
          Container(
            flexDirection = FlexDirection.row,
            alignItems = Container.Alignment.center,
            child = Fragment(
              mui.TextField
                .outlined()
                .select(true)
                .label("Category")
                .value(catId)
                .onChange(e =>
                  setSplits(splits.zipWithIndex.map { case ((c, a), j) =>
                    if (j == i) (e.target.asInstanceOf[dom.HTMLInputElement].value, a) else (c, a)
                  })
                )(catItems*),
              mui.TextField
                .outlined()
                .label("Amount")
                .value(amt)
                .onChange(e =>
                  setSplits(splits.zipWithIndex.map { case ((c, a), j) =>
                    if (j == i) (c, e.target.asInstanceOf[dom.HTMLInputElement].value) else (c, a)
                  })
                ),
              mui.Button
                .normal()("Remove")
                .onClick(_ => setSplits(splits.zipWithIndex.filter(_._2 != i).map(_._1)))
            )
          )
        )
        .withKey(i.toString)
        .build
    }

    def splitsSection(
        splits: List[(String, String)],
        setSplits: List[(String, String)] => Unit,
        amountStr: String
    ): ReactElement = {
      val rows = splits.zipWithIndex.map { case ((catId, amt), i) => splitRow(catId, amt, i, splits, setSplits) }
      val rem = splitRemainder(amountStr, splits)
      Container(
        flexDirection = FlexDirection.column,
        child = Fragment(
          mui.List(rows),
          Container(
            flexDirection = FlexDirection.row,
            alignItems = Container.Alignment.center,
            child = Fragment(
              mui.Button.normal()("Add split").onClick(_ => setSplits(splits :+ ("", ""))),
              mui.Typography(s"Uncategorized: $rem").sx(remainderStyling)
            )
          )
        )
      )
    }

    // ── computed data ──────────────────────────────────────────────────────────

    val sortedExpenses: List[GetExpenses.Response.Expense] = sortExpenses(props.expenses.data)
    val visibleExpenses: List[GetExpenses.Response.Expense] = applyDeletedIds(sortedExpenses, deletedIds)
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
      if (isFilterActive(dateFilter, filterText))
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
                    mui.TextField
                      .outlined()
                      .label("Amount")
                      .value(newAmount)
                      .onChange(e => setNewAmount(e.target.asInstanceOf[dom.HTMLInputElement].value)),
                    mui.TextField
                      .outlined()
                      .label("Date (YYYY-MM-DD)")
                      .value(newDate)
                      .onChange(e => setNewDate(e.target.asInstanceOf[dom.HTMLInputElement].value)),
                    mui.TextField
                      .outlined()
                      .label("Time (HH:MM, optional)")
                      .value(newTime)
                      .onChange(e => setNewTime(e.target.asInstanceOf[dom.HTMLInputElement].value)),
                    merchantOptions(newMerchantId, setNewMerchantId),
                    mui.TextField
                      .outlined()
                      .label("Notes (optional)")
                      .value(newNotes)
                      .onChange(e => setNewNotes(e.target.asInstanceOf[dom.HTMLInputElement].value)),
                    mui.Button.normal()("Add").variant("contained").`type`("submit"),
                    mui.Button.normal()("Cancel").onClick(_ => setShowCreateForm(false))
                  )
                ),
                splitsSection(newSplits, setNewSplits, newAmount)
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
        val isEditing = editingId.contains(e.id)
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
              setEditingId(Some(e.id))
              setEditAmount(e.amount.toString)
              setEditDate(e.date.toString)
              setEditTime(e.time.map(_.toString).getOrElse(""))
              setEditMerchantId(e.merchantId.map(_.toString).getOrElse(""))
              setEditNotes(e.notes.getOrElse(""))
              setEditSplits(e.splits.map(s => (s.categoryId.toString, s.amount.toString)))
            }
            .onMouseEnter(_ => setHoveredId(Some(e.id)))
            .onMouseLeave(_ => setHoveredId(None))(
              receiptCell,
              merchantCell,
              categoryCell,
              amountCell,
              kebabCell
            )

        val editRow: ReactElement =
          form(
            onSubmit := { ev =>
              ev.preventDefault(); handleUpdate(e.id)
            },
            onKeyDown := { ev => if (ev.key == "Escape") setEditingId(None) }
          )(
            Container(
              flexDirection = FlexDirection.column,
              child = Fragment(
                Container(
                  flexDirection = FlexDirection.row,
                  alignItems = Container.Alignment.center,
                  child = Fragment(
                    mui.TextField
                      .outlined()
                      .autoFocus(true)
                      .label("Amount")
                      .value(editAmount)
                      .onChange(e => setEditAmount(e.target.asInstanceOf[dom.HTMLInputElement].value)),
                    mui.TextField
                      .outlined()
                      .label("Date (YYYY-MM-DD)")
                      .value(editDate)
                      .onChange(e => setEditDate(e.target.asInstanceOf[dom.HTMLInputElement].value)),
                    mui.TextField
                      .outlined()
                      .label("Time (HH:MM, optional)")
                      .value(editTime)
                      .onChange(e => setEditTime(e.target.asInstanceOf[dom.HTMLInputElement].value)),
                    merchantOptions(editMerchantId, setEditMerchantId),
                    mui.TextField
                      .outlined()
                      .label("Notes (optional)")
                      .value(editNotes)
                      .onChange(e => setEditNotes(e.target.asInstanceOf[dom.HTMLInputElement].value)),
                    mui.Button.normal()("Save").`type`("submit"),
                    mui.Button.normal()("Cancel").onClick(_ => setEditingId(None))
                  )
                ),
                splitsSection(editSplits, setEditSplits, editAmount)
              )
            )
          )

        mui.Box
          .sx(new CSSPropertiesUtils {
            borderBottom = "1px solid var(--lc-border)"
          })(if (isEditing) editRow else displayRow)
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

    Fragment(
      pageHeader,
      toolbar,
      createForm,
      errorMsg,
      expenseList,
      paginationControls,
      legend,
      deleteDialog
    )
  }
}
