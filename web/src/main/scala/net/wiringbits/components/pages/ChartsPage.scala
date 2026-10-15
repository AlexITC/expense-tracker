package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.components.widgets.Loader
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.api.models.refunds.GetRefunds
import net.wiringbits.models.User
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.components.core.AsyncComponent
import org.scalajs.dom
import slinky.core.ExternalComponent
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.web.html.{form, onSubmit}

import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.util.Try

@js.native
@JSImport("@mui/x-charts", "BarChart")
object BarChartComponent extends js.Object

object BarChartWrapper extends ExternalComponent {
  case class Props(
      series: js.Array[js.Object],
      xAxis: js.UndefOr[js.Array[js.Object]] = js.undefined,
      width: js.UndefOr[Double] = js.undefined,
      height: js.UndefOr[Double] = js.undefined,
      skipAnimation: js.UndefOr[Boolean] = js.undefined
  )
  override val component: js.Object = BarChartComponent
}

@js.native
@JSImport("@mui/x-charts", "PieChart")
object PieChartComponent extends js.Object

object PieChartWrapper extends ExternalComponent {
  case class Props(
      series: js.Array[js.Object],
      width: js.UndefOr[Double] = js.undefined,
      height: js.UndefOr[Double] = js.undefined,
      skipAnimation: js.UndefOr[Boolean] = js.undefined,
      slotProps: js.UndefOr[js.Object] = js.undefined
  )
  override val component: js.Object = PieChartComponent
}

@js.native
@JSImport("@mui/x-charts", "LineChart")
object LineChartComponent extends js.Object

object LineChartWrapper extends ExternalComponent {
  case class Props(
      series: js.Array[js.Object],
      xAxis: js.UndefOr[js.Array[js.Object]] = js.undefined,
      width: js.UndefOr[Double] = js.undefined,
      height: js.UndefOr[Double] = js.undefined,
      skipAnimation: js.UndefOr[Boolean] = js.undefined
  )
  override val component: js.Object = LineChartComponent
}

object ChartsPage {
  def apply(ctx: AppContext, user: User): KeyAddingStage = component(Props(ctx, user))
  case class Props(ctx: AppContext, user: User)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    implicit val ec: ExecutionContext = props.ctx.executionContext
    AsyncComponent[GetExpenses.Response](
      fetch = () => props.ctx.api.client.getExpenses,
      render = expenses =>
        AsyncComponent[GetRefunds.Response](
          fetch = () => props.ctx.api.client.getRefunds,
          render = refunds =>
            AsyncComponent[GetCategories.Response](
              fetch = () => props.ctx.api.client.getCategories,
              render = categories => ChartsContent(props.ctx, expenses, refunds, categories),
              progressIndicator = () => Loader(props.ctx)
            ),
          progressIndicator = () => Loader(props.ctx)
        ),
      progressIndicator = () => Loader(props.ctx)
    )
  }
}

object ChartsData {
  private val monthFmt = DateTimeFormatter.ofPattern("yyyy-MM")

  case class MonthlyChart(labels: Seq[String], values: Seq[Double])
  case class MerchantChart(labels: Seq[String], values: Seq[Double])
  case class StackedMonthlyChart(labels: Seq[String], series: Seq[(String, Seq[Double])])
  case class PieSlice(label: String, value: Double)

  private def monthsInRange(from: YearMonth, to: YearMonth): Seq[YearMonth] = {
    val n = from.until(to, ChronoUnit.MONTHS).toInt + 1
    (0 until n).map(i => from.plusMonths(i.toLong))
  }

  private def debitsInRange(
      expenses: GetExpenses.Response,
      from: YearMonth,
      to: YearMonth
  ): List[GetExpenses.Response.Expense] =
    expenses.data.filter { e =>
      val ym = YearMonth.of(e.date.getYear, e.date.getMonth)
      e.amount < 0 && !ym.isBefore(from) && !ym.isAfter(to)
    }

  private def refundsInRange(
      refunds: GetRefunds.Response,
      from: YearMonth,
      to: YearMonth
  ): List[GetRefunds.Response.Refund] =
    refunds.data.filter { r =>
      val ym = YearMonth.of(r.date.getYear, r.date.getMonth)
      !ym.isBefore(from) && !ym.isAfter(to)
    }

  def monthly(
      expenses: GetExpenses.Response,
      refunds: GetRefunds.Response,
      from: YearMonth = YearMonth.now().minusMonths(5),
      to: YearMonth = YearMonth.now()
  ): MonthlyChart = {
    val months = monthsInRange(from, to)
    val debits = debitsInRange(expenses, from, to)
    val refs = refundsInRange(refunds, from, to)
    val totals = months.map { ym =>
      val expTotal = debits
        .filter(e => YearMonth.of(e.date.getYear, e.date.getMonth) == ym)
        .foldLeft(BigDecimal(0))((acc, e) => acc + e.amount.abs)
      val refTotal = refs
        .filter(r => YearMonth.of(r.date.getYear, r.date.getMonth) == ym)
        .foldLeft(BigDecimal(0))((acc, r) => acc + r.amount)
      (expTotal - refTotal).toDouble
    }
    MonthlyChart(labels = months.map(_.format(monthFmt)), values = totals)
  }

  def topMerchants(
      expenses: GetExpenses.Response,
      refunds: GetRefunds.Response,
      n: Int = 10,
      from: YearMonth = YearMonth.now().minusMonths(5),
      to: YearMonth = YearMonth.now()
  ): MerchantChart = {
    val debits = debitsInRange(expenses, from, to)
    val refs = refundsInRange(refunds, from, to)
    val refByMerchant = refs
      .groupMapReduce(r => r.merchantName.getOrElse("Unknown"))(_.amount)(_ + _)
    val top = debits
      .groupBy(e => e.merchantName.getOrElse("Unknown"))
      .view
      .mapValues { es =>
        val name = es.headOption.flatMap(_.merchantName).getOrElse("Unknown")
        val net = es.foldLeft(BigDecimal(0))((acc, e) => acc + e.amount.abs) -
          refByMerchant.getOrElse(name, BigDecimal(0))
        net.toDouble
      }
      .toList
      .sortBy(-_._2)
      .take(n)
    MerchantChart(labels = top.map(_._1), values = top.map(_._2))
  }

  def stackedMonthly(
      expenses: GetExpenses.Response,
      refunds: GetRefunds.Response,
      categories: GetCategories.Response,
      from: YearMonth = YearMonth.now().minusMonths(5),
      to: YearMonth = YearMonth.now(),
      maxCats: Int = 7
  ): StackedMonthlyChart = {
    val months = monthsInRange(from, to)
    val debits = debitsInRange(expenses, from, to)
    val refs = refundsInRange(refunds, from, to)
    val catMap = categories.data.map(c => c.id -> c.name).toMap

    val expCellTotals: Map[(YearMonth, String), BigDecimal] = debits
      .flatMap { e =>
        val ym = YearMonth.of(e.date.getYear, e.date.getMonth)
        if (e.splits.isEmpty)
          List(((ym, "Uncategorised"), e.amount.abs))
        else
          e.splits.map(s => ((ym, catMap.getOrElse(s.categoryId, "Uncategorised")), s.amount.abs))
      }
      .groupMapReduce(_._1)(_._2)(_ + _)

    val refCellTotals: Map[(YearMonth, String), BigDecimal] = refs
      .map { r =>
        val ym = YearMonth.of(r.date.getYear, r.date.getMonth)
        val cat = r.categoryName.getOrElse("Uncategorised")
        ((ym, cat), r.amount)
      }
      .groupMapReduce(_._1)(_._2)(_ + _)

    val cellTotals: Map[(YearMonth, String), BigDecimal] =
      (expCellTotals.keySet ++ refCellTotals.keySet).map { k =>
        k -> (expCellTotals.getOrElse(k, BigDecimal(0)) - refCellTotals.getOrElse(k, BigDecimal(0)))
      }.toMap

    val catTotals: List[(String, BigDecimal)] = cellTotals
      .groupMapReduce(_._1._2)(_._2)(_ + _)
      .toList
      .sortBy(-_._2)

    val (topCats, rest) = catTotals.splitAt(maxCats)
    val topNames = topCats.map(_._1)
    val allCats = if (rest.nonEmpty) topNames :+ "Other" else topNames

    val series: Seq[(String, Seq[Double])] = allCats.map { cat =>
      val values = months.map { ym =>
        if (cat == "Other")
          rest.map(r => cellTotals.getOrElse((ym, r._1), BigDecimal(0))).sum.toDouble
        else
          cellTotals.getOrElse((ym, cat), BigDecimal(0)).toDouble
      }
      (cat, values)
    }

    StackedMonthlyChart(labels = months.map(_.format(monthFmt)), series = series)
  }

  def categoryBreakdown(
      expenses: GetExpenses.Response,
      refunds: GetRefunds.Response,
      categories: GetCategories.Response,
      from: YearMonth = YearMonth.now().minusMonths(5),
      to: YearMonth = YearMonth.now(),
      maxSlices: Int = 5
  ): Seq[PieSlice] = {
    val catMap = categories.data.map(c => c.id -> c.name).toMap
    val refs = refundsInRange(refunds, from, to)
    val refByCategory = refs
      .groupMapReduce(r => r.categoryName.getOrElse("Uncategorised"))(_.amount)(_ + _)

    val all = debitsInRange(expenses, from, to)
      .flatMap { e =>
        if (e.splits.isEmpty)
          List(("Uncategorised", e.amount.abs))
        else
          e.splits.map(s => (catMap.getOrElse(s.categoryId, "Uncategorised"), s.amount.abs))
      }
      .groupMapReduce(_._1)(_._2)(_ + _)
      .map { case (cat, total) => (cat, total - refByCategory.getOrElse(cat, BigDecimal(0))) }
      .toList
      .sortBy(-_._2)
    val (top, rest) = all.splitAt(maxSlices)
    val slices = top.map { case (label, value) => PieSlice(label, value.toDouble) }
    if (rest.nonEmpty) slices :+ PieSlice("Other", rest.map(_._2).sum.toDouble)
    else slices
  }

  def cumulativeMonthly(
      expenses: GetExpenses.Response,
      refunds: GetRefunds.Response,
      from: YearMonth = YearMonth.now().minusMonths(5),
      to: YearMonth = YearMonth.now()
  ): Seq[Double] =
    monthly(expenses, refunds, from, to).values.scanLeft(0.0)(_ + _).tail
}

object ChartsContent {
  def apply(
      ctx: AppContext,
      expenses: GetExpenses.Response,
      refunds: GetRefunds.Response,
      categories: GetCategories.Response
  ): KeyAddingStage = component(Props(ctx, expenses, refunds, categories))

  case class Props(
      ctx: AppContext,
      expenses: GetExpenses.Response,
      refunds: GetRefunds.Response,
      categories: GetCategories.Response
  )

  private def defaultFrom: String = YearMonth.now().minusMonths(5).toString
  private def defaultTo: String = YearMonth.now().toString

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

    def applyRange(f: YearMonth, t: YearMonth): Unit = {
      setFromDraft(f.toString)
      setToDraft(t.toString)
      setFromMonth(f.toString)
      setToMonth(t.toString)
    }

    val allTimeFrom: YearMonth = (
      props.expenses.data.map(e => YearMonth.of(e.date.getYear, e.date.getMonth)) ++
        props.refunds.data.map(r => YearMonth.of(r.date.getYear, r.date.getMonth))
    ).minOption.getOrElse(YearMonth.now().minusMonths(11))

    val from: YearMonth = Try(YearMonth.parse(fromMonth)).getOrElse(YearMonth.now().minusMonths(5))
    val to: YearMonth = Try(YearMonth.parse(toMonth)).getOrElse(YearMonth.now())

    val stacked = ChartsData.stackedMonthly(props.expenses, props.refunds, props.categories, from, to)
    val pieSlices = ChartsData.categoryBreakdown(props.expenses, props.refunds, props.categories, from, to)
    val cumul = ChartsData.cumulativeMonthly(props.expenses, props.refunds, from, to)
    val monthly = ChartsData.monthly(props.expenses, props.refunds, from, to)
    val merchants = ChartsData.topMerchants(props.expenses, props.refunds, from = from, to = to)

    val totalSpend: Double = pieSlices.map(_.value).sum
    val txCount: Int = props.expenses.data.count { e =>
      val ym = YearMonth.of(e.date.getYear, e.date.getMonth)
      !ym.isBefore(from) && !ym.isAfter(to)
    }
    val nonZeroMonths: Int = monthly.values.count(_ > 0)
    val avgPerMonth: Double = if (nonZeroMonths > 0) totalSpend / nonZeroMonths else 0.0
    val topCatLabel: String = pieSlices.headOption
      .map { s =>
        val pct = if (totalSpend > 0) (s.value / totalSpend * 100).toInt else 0
        s"${s.label} ($pct%)"
      }
      .getOrElse("—")

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

    def sectionTitle(text: String): ReactElement =
      mui
        .Typography(text)
        .variant("subtitle1")
        .sx(new CSSPropertiesUtils {
          fontWeight = "700"; color = "var(--lc-text)"
          fontFamily = "\"IBM Plex Sans\", sans-serif"; marginBottom = "12px"
        })

    def emptyState(text: String): ReactElement =
      mui
        .Typography(text)
        .sx(new CSSPropertiesUtils { color = "var(--lc-text-2)"; fontSize = "14px" })

    val summaryRow: ReactElement = mui.Box.sx(new CSSPropertiesUtils {
      display = "flex"; gap = "12px"; marginBottom = "24px"; flexWrap = "wrap"
    })(
      statCard("Total spent", f"$$${totalSpend}%.2f"),
      statCard("Avg / month", f"$$${avgPerMonth}%.2f"),
      statCard("Transactions", txCount.toString),
      statCard("Top category", topCatLabel)
    )

    val dateControls: ReactElement = form(onSubmit := { e =>
      e.preventDefault()
      applyFilter()
    })(
      mui.Box.sx(new CSSPropertiesUtils {
        display = "flex"; alignItems = "center"; gap = "8px"; marginBottom = "8px"; flexWrap = "wrap"
      })(
        mui.Button
          .normal()("1M")
          .size("small")
          .variant("outlined")
          .`type`("button")
          .onClick(_ => applyRange(YearMonth.now(), YearMonth.now())),
        mui.Button
          .normal()("3M")
          .size("small")
          .variant("outlined")
          .`type`("button")
          .onClick(_ => applyRange(YearMonth.now().minusMonths(2), YearMonth.now())),
        mui.Button
          .normal()("1Y")
          .size("small")
          .variant("outlined")
          .`type`("button")
          .onClick(_ => applyRange(YearMonth.now().minusMonths(11), YearMonth.now())),
        mui.Button
          .normal()("All time")
          .size("small")
          .variant("outlined")
          .`type`("button")
          .onClick(_ => applyRange(allTimeFrom, YearMonth.now()))
      ),
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

    val stackedSeries: js.Array[js.Object] = js.Array(
      stacked.series.map { case (label, values) =>
        js.Dynamic
          .literal(data = js.Array(values*), label = label, stack = "total")
          .asInstanceOf[js.Object]
      }*
    )
    val stackedXAxis: js.Array[js.Object] = js.Array(
      js.Dynamic.literal(scaleType = "band", data = js.Array(stacked.labels*)).asInstanceOf[js.Object]
    )

    val pieColors = Seq("#02B2AF", "#2E96FF", "#B800D8", "#60009B", "#2731C8", "#03008D", "#83848F")

    val pieSeries: js.Array[js.Object] = js.Array(
      js.Dynamic
        .literal(
          data = js.Array(
            pieSlices.map(s => js.Dynamic.literal(value = s.value).asInstanceOf[js.Object])*
          ),
          innerRadius = 55,
          outerRadius = 105,
          paddingAngle = 2,
          cornerRadius = 4
        )
        .asInstanceOf[js.Object]
    )

    val pieHiddenLegend: js.Object =
      js.Dynamic.literal(legend = js.Dynamic.literal(hidden = true)).asInstanceOf[js.Object]

    def customPieLegend: ReactElement =
      mui.Box.sx(new CSSPropertiesUtils {
        display = "flex"; flexDirection = "column"; gap = "0px"; minWidth = "160px"
      })(
        Fragment(
          pieSlices.zipWithIndex.map { case (s, i) =>
            val pct = if (totalSpend > 0) f"${s.value / totalSpend * 100}%.1f%%" else "—"
            val dotColor = pieColors.lift(i).getOrElse("#888")
            (mui.Box
              .sx(new CSSPropertiesUtils {
                display = "flex"; alignItems = "center"; justifyContent = "space-between"
                padding = "6px 0"; borderBottom = "1px solid var(--lc-border)"; gap = "12px"
              })(
                mui.Box.sx(new CSSPropertiesUtils { display = "flex"; alignItems = "center"; gap = "8px" })(
                  mui.Box.sx(new CSSPropertiesUtils {
                    width = "10px"; height = "10px"; borderRadius = "50%"; backgroundColor = dotColor; flexShrink = "0"
                  }),
                  mui.Typography(s.label).sx(new CSSPropertiesUtils { fontSize = "13px"; color = "var(--lc-text)" })
                ),
                mui.Box.sx(new CSSPropertiesUtils { display = "flex"; gap = "10px"; alignItems = "center" })(
                  mui
                    .Typography(f"$$${s.value}%.2f")
                    .sx(new CSSPropertiesUtils {
                      fontSize = "13px"; fontFamily = "\"IBM Plex Mono\", monospace"; color = "var(--lc-text)"
                    }),
                  mui
                    .Typography(pct)
                    .sx(new CSSPropertiesUtils {
                      fontSize = "12px"; color = "var(--lc-text-2)"; minWidth = "42px"; textAlign = "right"
                    })
                )
              )
              .withKey(s.label): ReactElement)
          }*
        )
      )

    val lineSeries: js.Array[js.Object] = js.Array(
      js.Dynamic
        .literal(data = js.Array(cumul*), label = "Cumulative spend")
        .asInstanceOf[js.Object]
    )
    val lineXAxis: js.Array[js.Object] = js.Array(
      js.Dynamic.literal(scaleType = "band", data = js.Array(monthly.labels*)).asInstanceOf[js.Object]
    )

    val merchantSeries: js.Array[js.Object] = js.Array(
      js.Dynamic.literal(data = js.Array(merchants.values*)).asInstanceOf[js.Object]
    )
    val merchantXAxis: js.Array[js.Object] = js.Array(
      js.Dynamic.literal(scaleType = "band", data = js.Array(merchants.labels*)).asInstanceOf[js.Object]
    )

    Fragment(
      mui.Box.sx(new CSSPropertiesUtils { padding = "24px"; maxWidth = "980px" })(
        dateControls,
        summaryRow,
        sectionTitle("Monthly spending by category"),
        if (stacked.series.isEmpty) emptyState("No expense data in this period.")
        else
          BarChartWrapper(
            BarChartWrapper.Props(
              series = stackedSeries,
              xAxis = stackedXAxis,
              width = 900,
              height = 350,
              skipAnimation = true
            )
          ),
        mui.Box.sx(new CSSPropertiesUtils {
          display = "flex"; gap = "24px"; marginTop = "32px"; flexWrap = "wrap"
        })(
          mui.Box.sx(new CSSPropertiesUtils { flex = "1"; minWidth = "320px" })(
            sectionTitle("Spending breakdown by category"),
            if (pieSlices.isEmpty) emptyState("No expense data.")
            else
              mui.Box.sx(new CSSPropertiesUtils {
                display = "flex"; alignItems = "center"; gap = "16px"; flexWrap = "wrap"
              })(
                PieChartWrapper(
                  PieChartWrapper.Props(
                    series = pieSeries,
                    width = 250,
                    height = 250,
                    skipAnimation = true,
                    slotProps = pieHiddenLegend
                  )
                ),
                customPieLegend
              )
          ),
          mui.Box.sx(new CSSPropertiesUtils { flex = "1"; minWidth = "320px" })(
            sectionTitle("Cumulative spend"),
            if (cumul.forall(_ == 0.0)) emptyState("No expense data.")
            else
              LineChartWrapper(
                LineChartWrapper.Props(
                  series = lineSeries,
                  xAxis = lineXAxis,
                  width = 430,
                  height = 300,
                  skipAnimation = true
                )
              )
          )
        ),
        mui.Box.sx(new CSSPropertiesUtils { marginTop = "32px" })(
          sectionTitle("Top merchants by spending"),
          if (merchants.labels.isEmpty) emptyState("No expense data.")
          else
            BarChartWrapper(
              BarChartWrapper.Props(
                series = merchantSeries,
                xAxis = merchantXAxis,
                width = 900,
                height = 300,
                skipAnimation = true
              )
            )
        )
      )
    )
  }
}
