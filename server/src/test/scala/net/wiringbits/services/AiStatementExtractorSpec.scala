package net.wiringbits.services

import net.wiringbits.api.models.bankstatementtemplates.ProductType
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

class AiStatementExtractorSpec extends AnyWordSpec with Matchers {

  private val model = "test-model"

  private def ok(json: String) =
    AiStatementExtractor.parseAndVerify(json, model) match {
      case Right(r) => r
      case Left(e) => fail(s"expected a parsed result, got error: $e")
    }

  "parseAndVerify" should {
    "reconcile a running-balance (debit) statement via the per-row chain" in {
      val json =
        """{"metadata":{"productType":"debit","openingBalance":100.00,"closingBalance":60.00},
          | "transactions":[
          |   {"date":"2026-01-01","amount":-40.00,"description":"A","runningBalance":60.00}
          | ]}""".stripMargin
      val r = ok(json)
      r.reconciled must be(true)
      r.discrepancy must be(None)
      r.transactions must have size 1
      r.metadata.productType must be(Some(ProductType.Debit))
    }

    "reconcile a credit statement by opening/closing, sign-agnostic (no per-row balance)" in {
      val json =
        """{"metadata":{"productType":"credit","openingBalance":1484.73,"closingBalance":1689.69},
          | "transactions":[{"date":"2026-01-01","amount":-204.96,"description":"net"}]}""".stripMargin
      ok(json).reconciled must be(true)
    }

    "flag a discrepancy when the transactions do not add up" in {
      val json =
        """{"metadata":{"productType":"debit","openingBalance":100.00,"closingBalance":60.00},
          | "transactions":[{"date":"2026-01-01","amount":-30.00,"description":"short"}]}""".stripMargin
      val r = ok(json)
      r.reconciled must be(false)
      r.discrepancy.isDefined must be(true)
    }

    "detect a broken running-balance chain (a missing/misread row)" in {
      val json =
        """{"metadata":{"productType":"debit","openingBalance":100.00,"closingBalance":30.00},
          | "transactions":[
          |   {"date":"2026-01-01","amount":-40.00,"description":"A","runningBalance":60.00},
          |   {"date":"2026-01-02","amount":-20.00,"description":"B","runningBalance":30.00}
          | ]}""".stripMargin
      // chain: 100-40=60 ok, 60-20=40 != printed 30 -> broken
      ok(json).reconciled must be(false)
    }

    "strip markdown fences around the JSON" in {
      val json =
        "```json\n{\"metadata\":{},\"transactions\":[{\"date\":\"2026-01-01\",\"amount\":-5.00,\"description\":\"x\"}]}\n```"
      AiStatementExtractor.parseAndVerify(json, model).isRight must be(true)
    }

    "collapse runs of whitespace in descriptions, keeping continuation line breaks" in {
      val json =
        """{"metadata":{},"transactions":[
          |  {"date":"2026-01-01","amount":-5.00,"description":"TOTSA      APODACA NL\nPeso   Mexicano  Cambio"}
          |]}""".stripMargin
      ok(json).transactions.head.description must be("TOTSA APODACA NL\nPeso Mexicano Cambio")
    }

    "return Left when there are no transactions" in {
      AiStatementExtractor.parseAndVerify("""{"metadata":{},"transactions":[]}""", model).isLeft must be(true)
    }

    "return Left on invalid JSON" in {
      AiStatementExtractor.parseAndVerify("not json at all", model).isLeft must be(true)
    }

    "expose the numeric residual (off-by) when it does not reconcile" in {
      val json =
        """{"metadata":{"productType":"credit","openingBalance":0.00,"closingBalance":20.00},
          | "transactions":[{"date":"2026-01-01","amount":30.00,"description":"x"}]}""".stripMargin
      val r = ok(json)
      r.reconciled must be(false)
      r.residual must be(Some(BigDecimal("10.00")))
    }
  }

  "bestOf" should {
    val emptyMeta = AiStatementExtractor.Meta(None, None, None, None, None, None, None, None)
    def result(reconciled: Boolean, residual: Option[BigDecimal]) =
      AiStatementExtractor.ExtractionResult(reconciled, None, model, Nil, emptyMeta, residual)

    "prefer a reconciled result over any non-reconciled one" in {
      val reconciled = result(reconciled = true, Some(BigDecimal(0)))
      val closer = result(reconciled = false, Some(BigDecimal("1.00")))
      AiStatementExtractor.bestOf(List(closer, reconciled)) must be(Some(reconciled))
    }

    "pick the smallest residual among non-reconciled results" in {
      val far = result(reconciled = false, Some(BigDecimal("100.00")))
      val near = result(reconciled = false, Some(BigDecimal("3.00")))
      val noGap = result(reconciled = false, None)
      AiStatementExtractor.bestOf(List(far, near, noGap)) must be(Some(near))
    }

    "return None for an empty list" in {
      AiStatementExtractor.bestOf(Nil) must be(None)
    }
  }
}
