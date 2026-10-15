package net.wiringbits.components.pages

import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.api.models.refunds.GetRefunds
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate, YearMonth}
import java.util.UUID

class ChartsPageSpec extends AnyWordSpec {

  private val noRefunds = GetRefunds.Response(data = Nil)

  private def expense(
      amount: BigDecimal,
      date: LocalDate,
      merchantName: Option[String] = None
  ): GetExpenses.Response.Expense =
    GetExpenses.Response.Expense(
      id = UUID.randomUUID(),
      amount = amount,
      date = date,
      time = None,
      merchantId = None,
      merchantName = merchantName,
      notes = None,
      splits = Nil,
      createdAt = Instant.now()
    )

  "ChartsData.monthly" should {
    "return 6 labels and 6 values" in {
      val response = GetExpenses.Response(data = Nil)
      val result = ChartsData.monthly(response, noRefunds)
      result.labels.length mustBe 6
      result.values.length mustBe 6
    }

    "include only the last 6 months" in {
      val response = GetExpenses.Response(data = Nil)
      val result = ChartsData.monthly(response, noRefunds)
      val now = YearMonth.now()
      result.labels.head mustBe now.minusMonths(5).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"))
      result.labels.last mustBe now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"))
    }

    "sum absolute values of debits in each month" in {
      val thisMonth = LocalDate.now().withDayOfMonth(1)
      val response = GetExpenses.Response(
        data = List(
          expense(BigDecimal("-10.00"), thisMonth),
          expense(BigDecimal("-20.00"), thisMonth),
          expense(BigDecimal("50.00"), thisMonth) // credit — excluded
        )
      )
      val result = ChartsData.monthly(response, noRefunds)
      result.values.last mustBe 30.0
    }

    "produce zeros for months with no expenses" in {
      val response = GetExpenses.Response(data = Nil)
      val result = ChartsData.monthly(response, noRefunds)
      result.values.forall(_ == 0.0) mustBe true
    }
  }

  "ChartsData.topMerchants" should {
    "return empty lists when there are no debits" in {
      val response = GetExpenses.Response(
        data = List(expense(BigDecimal("100.00"), LocalDate.now()))
      )
      val result = ChartsData.topMerchants(response, noRefunds)
      result.labels mustBe Seq.empty
      result.values mustBe Seq.empty
    }

    "rank merchants by total spend descending" in {
      val d = LocalDate.now().minusMonths(1)
      val response = GetExpenses.Response(
        data = List(
          expense(BigDecimal("-5.00"), d, Some("Cheap")),
          expense(BigDecimal("-50.00"), d, Some("Expensive")),
          expense(BigDecimal("-20.00"), d, Some("Mid"))
        )
      )
      val result = ChartsData.topMerchants(response, noRefunds)
      result.labels mustBe Seq("Expensive", "Mid", "Cheap")
      result.values mustBe Seq(50.0, 20.0, 5.0)
    }

    "label expenses without a merchant as Unknown" in {
      val d = LocalDate.now().minusMonths(1)
      val response = GetExpenses.Response(
        data = List(expense(BigDecimal("-15.00"), d, None))
      )
      val result = ChartsData.topMerchants(response, noRefunds)
      result.labels mustBe Seq("Unknown")
    }

    "cap results at n (default 10)" in {
      val d = LocalDate.now().minusMonths(1)
      val expenses = (1 to 15).toList.map(i => expense(BigDecimal(s"-${i}.00"), d, Some(s"Merchant$i")))
      val result = ChartsData.topMerchants(GetExpenses.Response(data = expenses), noRefunds)
      result.labels.length mustBe 10
    }
  }
}
