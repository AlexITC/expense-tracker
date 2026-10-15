package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.components.widgets.Loader
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.api.models.refunds.GetRefunds
import net.wiringbits.models.User
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.components.core.AsyncComponent
import net.wiringbits.webapp.utils.slinkyUtils.core.GenericHooks
import org.scalajs.dom
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.web.html.{form, onSubmit}

import java.time.YearMonth
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.util.Try

object ReportsPage {
  def apply(ctx: AppContext, user: User): KeyAddingStage = component(Props(ctx, user))
  case class Props(ctx: AppContext, user: User)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    val (timesRefreshing, _) = GenericHooks.useForceRefresh

    mui.Box.sx(new CSSPropertiesUtils { padding = "24px"; maxWidth = "1100px" })(
      AsyncComponent[GetExpenses.Response](
        fetch = () => props.ctx.api.client.getExpenses,
        render = expenses =>
          AsyncComponent[GetRefunds.Response](
            fetch = () => props.ctx.api.client.getRefunds,
            render = refunds =>
              AsyncComponent[GetCategories.Response](
                fetch = () => props.ctx.api.client.getCategories,
                render = categories =>
                  AsyncComponent[GetMerchants.Response](
                    fetch = () => props.ctx.api.client.getMerchants,
                    render = merchants => ReportsContent(props.ctx, expenses, refunds, categories, merchants),
                    progressIndicator = () => Loader(props.ctx),
                    watchedObjects = List(timesRefreshing)
                  ),
                progressIndicator = () => Loader(props.ctx),
                watchedObjects = List(timesRefreshing)
              ),
            progressIndicator = () => Loader(props.ctx),
            watchedObjects = List(timesRefreshing)
          ),
        progressIndicator = () => Loader(props.ctx),
        watchedObjects = List(timesRefreshing)
      )
    )
  }
}

object ReportsContent {
  def apply(
      ctx: AppContext,
      expenses: GetExpenses.Response,
      refunds: GetRefunds.Response,
      categories: GetCategories.Response,
      merchants: GetMerchants.Response
  ): KeyAddingStage = component(Props(ctx, expenses, refunds, categories, merchants))

  case class Props(
      ctx: AppContext,
      expenses: GetExpenses.Response,
      refunds: GetRefunds.Response,
      categories: GetCategories.Response,
      merchants: GetMerchants.Response
  )

  private def defaultFrom: String = YearMonth.now().minusMonths(3).toString
  private def defaultTo: String = YearMonth.now().minusMonths(1).toString

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    val (fromDraft, setFromDraft) = Hooks.useState(defaultFrom)
    val (toDraft, setToDraft) = Hooks.useState(defaultTo)
    val (fromMonth, setFromMonth) = Hooks.useState(defaultFrom)
    val (toMonth, setToMonth) = Hooks.useState(defaultTo)

    def applyFilter(): Unit =
      if (Try(YearMonth.parse(fromDraft)).isSuccess && Try(YearMonth.parse(toDraft)).isSuccess) {
        setFromMonth(fromDraft)
        setToMonth(toDraft)
      }

    val filteredExpenses: List[GetExpenses.Response.Expense] = (for {
      from <- Try(YearMonth.parse(fromMonth)).toOption
      to <- Try(YearMonth.parse(toMonth)).toOption
    } yield props.expenses.data.filter { e =>
      val ym = YearMonth.from(e.date)
      !ym.isBefore(from) && !ym.isAfter(to)
    }).getOrElse(Nil)

    val filteredRefunds: List[GetRefunds.Response.Refund] = (for {
      from <- Try(YearMonth.parse(fromMonth)).toOption
      to <- Try(YearMonth.parse(toMonth)).toOption
    } yield props.refunds.data.filter { r =>
      val ym = YearMonth.from(r.date)
      !ym.isBefore(from) && !ym.isAfter(to)
    }).getOrElse(Nil)

    val debits = filteredExpenses
    val totalSpend: BigDecimal = debits.map(_.amount.abs).sum - filteredRefunds.map(_.amount).sum
    val txCount: Int = debits.size
    val avgTx: BigDecimal =
      if (txCount > 0) (totalSpend / txCount).setScale(2, BigDecimal.RoundingMode.HALF_UP) else BigDecimal(0)
    val biggestExp: Option[GetExpenses.Response.Expense] = if (debits.isEmpty) None else Some(debits.maxBy(_.amount))

    def statCard(label: String, value: String): ReactElement =
      mui.Box.sx(new CSSPropertiesUtils {
        flex = "1"; minWidth = "150px"; padding = "16px"; borderRadius = "8px"
        backgroundColor = "var(--lc-paper)"; border = "1px solid var(--lc-border)"
      })(
        mui
          .Typography(label)
          .sx(new CSSPropertiesUtils {
            fontSize = "11px"; fontWeight = "600"; letterSpacing = "0.06em"
            textTransform = "uppercase"; color = "var(--lc-text-2)"
            fontFamily = "\"IBM Plex Sans\", sans-serif"
          }),
        mui
          .Typography(value)
          .sx(new CSSPropertiesUtils {
            fontSize = "22px"; fontWeight = "700"; color = "var(--lc-text)"
            fontFamily = "\"IBM Plex Mono\", monospace"; marginTop = "4px"
          })
      )

    val biggestLabel: String = biggestExp
      .map(e =>
        s"${e.amount.bigDecimal.stripTrailingZeros.toPlainString}${e.merchantName.map(m => s" · $m").getOrElse("")}"
      )
      .getOrElse("—")

    val summaryRow: ReactElement = mui.Box.sx(new CSSPropertiesUtils {
      display = "flex"; gap = "12px"; marginBottom = "24px"; flexWrap = "wrap"
    })(
      statCard("Total spent", totalSpend.bigDecimal.stripTrailingZeros.toPlainString),
      statCard("Transactions", txCount.toString),
      statCard("Avg / transaction", avgTx.bigDecimal.stripTrailingZeros.toPlainString),
      statCard("Biggest expense", biggestLabel)
    )

    val dateControls: ReactElement = form(onSubmit := { e =>
      e.preventDefault()
      applyFilter()
    })(
      mui.Box.sx(new CSSPropertiesUtils {
        display = "flex"; alignItems = "center"; gap = "16px"; marginBottom = "24px"; flexWrap = "wrap"
      })(
        mui.TextField
          .outlined()
          .label("From month")
          .size("small")
          .`type`("month")
          .value(fromDraft)
          .onChange(e => setFromDraft(e.target.asInstanceOf[dom.HTMLInputElement].value))
          .sx(new CSSPropertiesUtils { minWidth = "160px" }),
        mui.TextField
          .outlined()
          .label("To month")
          .size("small")
          .`type`("month")
          .value(toDraft)
          .onChange(e => setToDraft(e.target.asInstanceOf[dom.HTMLInputElement].value))
          .sx(new CSSPropertiesUtils { minWidth = "160px" }),
        mui.Button.normal()("Apply").`type`("submit").size("small").variant("outlined")
      )
    )

    Fragment(
      mui
        .Typography("Reports")
        .variant("h6")
        .sx(new CSSPropertiesUtils { fontWeight = "700"; color = "var(--lc-text)"; marginBottom = "16px" }),
      dateControls,
      summaryRow,
      MonthlyCategoryReport(filteredExpenses, filteredRefunds, fromMonth, toMonth, props.categories),
      mui.Box.sx(new CSSPropertiesUtils { marginTop = "32px" })(
        CategoryBreakdownReport(filteredExpenses, filteredRefunds, props.categories)
      ),
      mui.Box.sx(new CSSPropertiesUtils { marginTop = "32px" })(
        TopMerchantsReport(filteredExpenses, filteredRefunds, props.merchants, totalSpend)
      ),
      mui.Box.sx(new CSSPropertiesUtils { marginTop = "32px" })(
        LargestExpensesReport(filteredExpenses, props.categories)
      )
    )
  }
}

object MonthlyCategoryReport {
  def apply(
      expenses: List[GetExpenses.Response.Expense],
      refunds: List[GetRefunds.Response.Refund],
      fromMonth: String,
      toMonth: String,
      categories: GetCategories.Response
  ): KeyAddingStage = component(Props(expenses, refunds, fromMonth, toMonth, categories))

  case class Props(
      expenses: List[GetExpenses.Response.Expense],
      refunds: List[GetRefunds.Response.Refund],
      fromMonth: String,
      toMonth: String,
      categories: GetCategories.Response
  )

  private val uncategorisedLabel = "Uncategorised"

  private def monthsBetween(from: String, to: String): List[String] = {
    val start = YearMonth.parse(from)
    val end = YearMonth.parse(to)
    val n = start.until(end, java.time.temporal.ChronoUnit.MONTHS).toInt + 1
    (0 until n).map(i => start.plusMonths(i.toLong).toString).toList
  }

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    val catMap: Map[UUID, String] = props.categories.data.map(c => c.id -> c.name).toMap
    val months = monthsBetween(props.fromMonth, props.toMonth)

    val expenseTotals: Map[(String, String), BigDecimal] = props.expenses
      .flatMap { e =>
        val ym = YearMonth.from(e.date).toString
        if (e.splits.isEmpty)
          List(((ym, uncategorisedLabel), e.amount))
        else
          e.splits.map { s =>
            val catName = catMap.getOrElse(s.categoryId, uncategorisedLabel)
            ((ym, catName), s.amount)
          }
      }
      .groupMapReduce(_._1)(_._2)(_ + _)

    val refundTotals: Map[(String, String), BigDecimal] = props.refunds
      .map { r =>
        val ym = YearMonth.from(r.date).toString
        val catName = r.categoryName.getOrElse(uncategorisedLabel)
        ((ym, catName), r.amount)
      }
      .groupMapReduce(_._1)(_._2)(_ + _)

    val totals: Map[(String, String), BigDecimal] =
      (expenseTotals.keySet ++ refundTotals.keySet).map { k =>
        k -> (expenseTotals.getOrElse(k, BigDecimal(0)) - refundTotals.getOrElse(k, BigDecimal(0)))
      }.toMap

    val usedCategories: List[String] = {
      val names = totals.keys.map(_._2).toSet
      val ordered = props.categories.data.map(_.name).filter(names.contains)
      val withUncategorised = if (names.contains(uncategorisedLabel)) ordered :+ uncategorisedLabel else ordered
      withUncategorised
    }

    def cellTotal(month: String, cat: String): BigDecimal = totals.getOrElse((month, cat), BigDecimal(0))
    def monthRowTotal(month: String): BigDecimal = usedCategories.map(cat => cellTotal(month, cat)).sum
    def catColTotal(cat: String): BigDecimal = months.map(m => cellTotal(m, cat)).sum
    val grandTotal: BigDecimal = usedCategories.map(catColTotal).sum

    val headerRow: ReactElement = mui.TableRow(
      mui.TableCell("Month").sx(new CSSPropertiesUtils { fontWeight = "700"; whiteSpace = "nowrap" }),
      Fragment(
        usedCategories.map { cat =>
          mui
            .TableCell(cat)
            .sx(new CSSPropertiesUtils { fontWeight = "700"; whiteSpace = "nowrap" })
            .withKey(cat)
            .build
        }*
      ),
      mui.TableCell("Total").sx(new CSSPropertiesUtils { fontWeight = "700" })
    )

    val dataRows: Seq[ReactElement] = months.map { month =>
      mui
        .TableRow(
          mui.TableCell(month).sx(new CSSPropertiesUtils { whiteSpace = "nowrap" }),
          Fragment(
            usedCategories.map { cat =>
              val v = cellTotal(month, cat)
              mui
                .TableCell(if (v == BigDecimal(0)) "—" else v.bigDecimal.stripTrailingZeros.toPlainString)
                .withKey(cat)
                .build
            }*
          ),
          mui
            .TableCell(monthRowTotal(month).bigDecimal.stripTrailingZeros.toPlainString)
            .sx(new CSSPropertiesUtils { fontWeight = "600" })
        )
        .withKey(month)
    }

    val totalRow: ReactElement = mui.TableRow(
      mui.TableCell("Total").sx(new CSSPropertiesUtils { fontWeight = "700" }),
      Fragment(
        usedCategories.map { cat =>
          val v = catColTotal(cat)
          mui
            .TableCell(if (v == BigDecimal(0)) "—" else v.bigDecimal.stripTrailingZeros.toPlainString)
            .sx(new CSSPropertiesUtils { fontWeight = "600" })
            .withKey(cat)
            .build
        }*
      ),
      mui
        .TableCell(grandTotal.bigDecimal.stripTrailingZeros.toPlainString)
        .sx(new CSSPropertiesUtils { fontWeight = "700" })
    )

    Fragment(
      mui
        .Typography("Monthly spending by category")
        .variant("subtitle1")
        .sx(new CSSPropertiesUtils { fontWeight = "700"; color = "var(--lc-text)"; marginBottom = "12px" }),
      mui.TableContainer(
        mui.Table.size("small")(
          mui.TableHead(headerRow),
          mui.TableBody(Fragment(dataRows*), totalRow)
        )
      )
    )
  }
}

object CategoryBreakdownReport {
  def apply(
      expenses: List[GetExpenses.Response.Expense],
      refunds: List[GetRefunds.Response.Refund],
      categories: GetCategories.Response
  ): KeyAddingStage = component(Props(expenses, refunds, categories))

  case class Props(
      expenses: List[GetExpenses.Response.Expense],
      refunds: List[GetRefunds.Response.Refund],
      categories: GetCategories.Response
  )

  case class CatRow(name: String, total: BigDecimal, expenseCount: Int)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    val catMap: Map[UUID, String] = props.categories.data.map(c => c.id -> c.name).toMap
    val debits = props.expenses

    val refundByCategory: Map[String, BigDecimal] = props.refunds
      .map(r => (r.categoryName.getOrElse("Uncategorised"), r.amount))
      .groupMapReduce(_._1)(_._2)(_ + _)

    val rows: List[CatRow] = debits
      .flatMap { e =>
        if (e.splits.isEmpty)
          List(("Uncategorised", e.amount.abs, e.id))
        else
          e.splits.map(s => (catMap.getOrElse(s.categoryId, "Uncategorised"), s.amount.abs, e.id))
      }
      .groupBy(_._1)
      .map { case (cat, items) =>
        val net = items.map(_._2).sum - refundByCategory.getOrElse(cat, BigDecimal(0))
        CatRow(cat, net, items.map(_._3).distinct.size)
      }
      .toList
      .sortBy(-_.total)

    val grandTotal: BigDecimal = rows.map(_.total).sum

    Fragment(
      mui
        .Typography("Spending by category")
        .variant("subtitle1")
        .sx(new CSSPropertiesUtils { fontWeight = "700"; color = "var(--lc-text)"; marginBottom = "12px" }),
      if (rows.isEmpty)
        mui.Typography("No expense data.").sx(new CSSPropertiesUtils { color = "var(--lc-text-2)"; fontSize = "14px" })
      else
        mui.TableContainer(
          mui.Table.size("small")(
            mui.TableHead(
              mui.TableRow(
                mui.TableCell("#").sx(new CSSPropertiesUtils { fontWeight = "700" }),
                mui.TableCell("Category").sx(new CSSPropertiesUtils { fontWeight = "700" }),
                mui.TableCell("Total").sx(new CSSPropertiesUtils { fontWeight = "700" }),
                mui.TableCell("% of total").sx(new CSSPropertiesUtils { fontWeight = "700" }),
                mui.TableCell("Expenses").sx(new CSSPropertiesUtils { fontWeight = "700" }),
                mui.TableCell("Avg / expense").sx(new CSSPropertiesUtils { fontWeight = "700" })
              )
            ),
            mui.TableBody(
              Fragment(
                rows.zipWithIndex.map { case (r, i) =>
                  val pct =
                    if (grandTotal > 0) (r.total / grandTotal * 100).setScale(1, BigDecimal.RoundingMode.HALF_UP)
                    else BigDecimal(0)
                  val avg =
                    if (r.expenseCount > 0) (r.total / r.expenseCount).setScale(2, BigDecimal.RoundingMode.HALF_UP)
                    else BigDecimal(0)
                  mui
                    .TableRow(
                      mui.TableCell((i + 1).toString),
                      mui.TableCell(r.name),
                      mui.TableCell(r.total.bigDecimal.stripTrailingZeros.toPlainString),
                      mui.TableCell(s"$pct%"),
                      mui.TableCell(r.expenseCount.toString),
                      mui.TableCell(avg.bigDecimal.stripTrailingZeros.toPlainString)
                    )
                    .withKey(r.name)
                    .build
                }*
              ),
              mui.TableRow(
                mui.TableCell(""),
                mui.TableCell("Total").sx(new CSSPropertiesUtils { fontWeight = "700" }),
                mui
                  .TableCell(grandTotal.bigDecimal.stripTrailingZeros.toPlainString)
                  .sx(new CSSPropertiesUtils { fontWeight = "700" }),
                mui.TableCell("100%").sx(new CSSPropertiesUtils { fontWeight = "700" }),
                mui.TableCell(debits.size.toString).sx(new CSSPropertiesUtils { fontWeight = "700" }),
                mui.TableCell("")
              )
            )
          )
        )
    )
  }
}

object TopMerchantsReport {
  def apply(
      expenses: List[GetExpenses.Response.Expense],
      refunds: List[GetRefunds.Response.Refund],
      merchants: GetMerchants.Response,
      periodTotal: BigDecimal
  ): KeyAddingStage = component(Props(expenses, refunds, merchants, periodTotal))

  case class Props(
      expenses: List[GetExpenses.Response.Expense],
      refunds: List[GetRefunds.Response.Refund],
      merchants: GetMerchants.Response,
      periodTotal: BigDecimal
  )

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    val merchantMap: Map[UUID, String] = props.merchants.data.map(m => m.id -> m.name).toMap

    case class MerchantRow(rank: Int, name: String, total: BigDecimal, count: Int)

    val refundByMerchant: Map[String, BigDecimal] = props.refunds
      .map(r => (r.merchantName.getOrElse("Unknown"), r.amount))
      .groupMapReduce(_._1)(_._2)(_ + _)

    val rows: List[MerchantRow] = props.expenses
      .groupBy(_.merchantId)
      .toList
      .map { case (midOpt, exps) =>
        val name = midOpt.flatMap(merchantMap.get).getOrElse("Unknown")
        val total = exps.map(_.amount.abs).sum - refundByMerchant.getOrElse(name, BigDecimal(0))
        val count = exps.size
        (name, total, count)
      }
      .sortBy(-_._2)
      .take(20)
      .zipWithIndex
      .map { case ((name, total, count), i) => MerchantRow(i + 1, name, total, count) }

    Fragment(
      mui
        .Typography("Top merchants by spend")
        .variant("subtitle1")
        .sx(new CSSPropertiesUtils { fontWeight = "700"; color = "var(--lc-text)"; marginBottom = "12px" }),
      if (rows.isEmpty)
        mui.Typography("No expense data.").sx(new CSSPropertiesUtils { color = "var(--lc-text-2)"; fontSize = "14px" })
      else
        mui.TableContainer(
          mui.Table.size("small")(
            mui.TableHead(
              mui.TableRow(
                mui.TableCell("#").sx(new CSSPropertiesUtils { fontWeight = "700" }),
                mui.TableCell("Merchant").sx(new CSSPropertiesUtils { fontWeight = "700" }),
                mui.TableCell("Total").sx(new CSSPropertiesUtils { fontWeight = "700" }),
                mui.TableCell("% of total").sx(new CSSPropertiesUtils { fontWeight = "700" }),
                mui.TableCell("Count").sx(new CSSPropertiesUtils { fontWeight = "700" }),
                mui.TableCell("Avg / expense").sx(new CSSPropertiesUtils { fontWeight = "700" })
              )
            ),
            mui.TableBody(
              Fragment(
                rows.map { r =>
                  val pct =
                    if (props.periodTotal > 0)
                      (r.total / props.periodTotal * 100).setScale(1, BigDecimal.RoundingMode.HALF_UP)
                    else BigDecimal(0)
                  val avg =
                    if (r.count > 0) (r.total / r.count).setScale(2, BigDecimal.RoundingMode.HALF_UP)
                    else BigDecimal(0)
                  mui
                    .TableRow(
                      mui.TableCell(r.rank.toString),
                      mui.TableCell(r.name),
                      mui.TableCell(r.total.bigDecimal.stripTrailingZeros.toPlainString),
                      mui.TableCell(s"$pct%"),
                      mui.TableCell(r.count.toString),
                      mui.TableCell(avg.bigDecimal.stripTrailingZeros.toPlainString)
                    )
                    .withKey(r.rank.toString)
                    .build
                }*
              )
            )
          )
        )
    )
  }
}

object LargestExpensesReport {
  def apply(
      expenses: List[GetExpenses.Response.Expense],
      categories: GetCategories.Response
  ): KeyAddingStage = component(Props(expenses, categories))

  case class Props(expenses: List[GetExpenses.Response.Expense], categories: GetCategories.Response)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    val catMap: Map[UUID, String] = props.categories.data.map(c => c.id -> c.name).toMap

    val top: List[GetExpenses.Response.Expense] = props.expenses
      .sortBy(-_.amount)
      .take(20)

    Fragment(
      mui
        .Typography("Largest expenses")
        .variant("subtitle1")
        .sx(new CSSPropertiesUtils { fontWeight = "700"; color = "var(--lc-text)"; marginBottom = "12px" }),
      if (top.isEmpty)
        mui.Typography("No expense data.").sx(new CSSPropertiesUtils { color = "var(--lc-text-2)"; fontSize = "14px" })
      else
        mui.TableContainer(
          mui.Table.size("small")(
            mui.TableHead(
              mui.TableRow(
                mui.TableCell("Date").sx(new CSSPropertiesUtils { fontWeight = "700" }),
                mui.TableCell("Merchant").sx(new CSSPropertiesUtils { fontWeight = "700" }),
                mui.TableCell("Notes").sx(new CSSPropertiesUtils { fontWeight = "700" }),
                mui.TableCell("Categories").sx(new CSSPropertiesUtils { fontWeight = "700" }),
                mui.TableCell("Amount").sx(new CSSPropertiesUtils { fontWeight = "700" })
              )
            ),
            mui.TableBody(
              Fragment(
                top.map { e =>
                  val catNames = e.splits.flatMap(s => catMap.get(s.categoryId)).mkString(", ")
                  mui
                    .TableRow(
                      mui.TableCell(e.date.toString),
                      mui.TableCell(e.merchantName.getOrElse("—")),
                      mui.TableCell(e.notes.getOrElse("—")),
                      mui.TableCell(if (catNames.isEmpty) "—" else catNames),
                      mui
                        .TableCell(e.amount.bigDecimal.stripTrailingZeros.toPlainString)
                        .sx(new CSSPropertiesUtils {
                          fontFamily = "\"IBM Plex Mono\", monospace"; color = "var(--lc-error)"
                        })
                    )
                    .withKey(e.id.toString)
                    .build
                }*
              )
            )
          )
        )
    )
  }
}
