package net.wiringbits.services

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsObject, Json}

class DocumentParserSpec extends AnyWordSpec {

  private def loadJson(path: String) = {
    val stream = getClass.getClassLoader.getResourceAsStream(path)
    Json.parse(stream)
  }

  "DocumentParser.parse" should {
    // Fixture-driven case. The real receipt dataset was removed before open-sourcing (PII); a single
    // synthetic textract/ticket pair keeps this path covered. Re-add pairs under fixtures/textract +
    // fixtures/tickets (indexed 1..N) and widen this loop to restore the full dataset.
    for (n <- 1 to 1) {
      s"parse ticket $n" in {
        val textract = loadJson(s"fixtures/textract/$n.json")
        val fixture = loadJson(s"fixtures/tickets/$n.json")

        val result = DocumentParser.parse(textract, "receipt")
        result mustBe a[Right[?, ?]]

        val parsed = Json.toJson(result.toOption.get).as[JsObject]
        val expected = fixture.as[JsObject]

        parsed mustBe expected
      }
    }
  }

  private def field(ftype: String, value: String, confidence: Double): JsObject =
    Json.obj(
      "Type" -> Json.obj("Text" -> ftype),
      "ValueDetection" -> Json.obj("Text" -> value, "Confidence" -> confidence)
    )

  private def labeledField(ftype: String, label: String, value: String, confidence: Double): JsObject =
    field(ftype, value, confidence) ++ Json.obj("LabelDetection" -> Json.obj("Text" -> label))

  // Builds an AnalyzeExpense response where each inner sequence is one ExpenseDocument's SummaryFields.
  private def response(documents: Seq[JsObject]*): JsObject =
    Json.obj(
      "ExpenseDocuments" -> documents.map(fields => Json.obj("SummaryFields" -> fields, "LineItemGroups" -> Json.arr()))
    )

  private def parsed(response: JsObject) = DocumentParser.parse(response, "receipt").toOption.get

  "DocumentParser.parse (async multi-document handling)" should {
    "aggregate fields across all ExpenseDocuments, not just the first" in {
      // The real total lives in a later document; the first only carries a partial amount.
      val res = response(
        Seq(field("TOTAL", "14,98", 59.7)),
        Seq(field("INVOICE_RECEIPT_DATE", "14/09/26", 100.0)),
        Seq(field("TOTAL", "170,64", 99.9))
      )
      parsed(res).amount mustBe Some(BigDecimal("170.64"))
      parsed(res).transaction_date mustBe Some("2026-09-14")
    }

    "pick the largest value among the most-confident TOTAL detections" in {
      // A smaller value (21,64) is equally confident; the grand total is the largest of the high-confidence ones.
      val res = response(
        Seq(field("TOTAL", "21,64", 100.0), field("TOTAL", "170,64", 99.9), field("TOTAL", "9,98", 55.0))
      )
      parsed(res).amount mustBe Some(BigDecimal("170.64"))
    }

    "fall back to AMOUNT_PAID when no TOTAL is present" in {
      val res = response(Seq(field("AMOUNT_PAID", "42,50", 98.0)))
      parsed(res).amount mustBe Some(BigDecimal("42.50"))
    }
  }

  "DocumentParser.parse (amount disambiguation)" should {
    "prefer the amount labeled as the charge over the printed total (discount / gift card)" in {
      // Printed total is 36,89 but 29,41 was charged to the card after a gift card — the value that reconciles.
      val res = response(
        Seq(
          labeledField("TOTAL", "TOTAL COMPRA", "36,89", 96.5),
          labeledField("AMOUNT_PAID", "AMEX CARGO EN CUENTA", "29,41", 62.9)
        )
      )
      parsed(res).amount mustBe Some(BigDecimal("29.41"))
    }

    "prefer the amount labeled TOTAL A PAGAR over a higher-confidence subtotal" in {
      val res = response(
        Seq(
          labeledField("TOTAL", "SUBTOTAL", "223,57", 99.9),
          labeledField("TOTAL", "TOTAL A PAGAR", "191,84", 99.8),
          labeledField("TOTAL", "TOTAL VENTAJAS", "55,25", 97.9)
        )
      )
      parsed(res).amount mustBe Some(BigDecimal("191.84"))
    }

    "reject a tax mistyped as TOTAL by trusting the value echoed as amount paid" in {
      // 1,73 is the (higher-confidence) tax; 9,99 is the real total, echoed as AMOUNT_PAID.
      val res = response(
        Seq(
          labeledField("TOTAL", "TOTAL IMPUESTOS", "1,73", 99.1),
          labeledField("TOTAL", "TOTAL", "9,99", 75.9),
          labeledField("AMOUNT_PAID", "ENTREGA", "9,99", 81.1)
        )
      )
      parsed(res).amount mustBe Some(BigDecimal("9.99"))
    }
  }

  "DocumentParser.parse (date formats)" should {
    "parse a numeric date followed by a time" in {
      val res = response(Seq(field("INVOICE_RECEIPT_DATE", "28/08/2026 13: 36", 70.9)))
      parsed(res).transaction_date mustBe Some("2026-08-28")
    }

    "parse a day-first textual month date" in {
      val res = response(Seq(field("INVOICE_RECEIPT_DATE", "2 sept 2026", 98.9)))
      parsed(res).transaction_date mustBe Some("2026-09-02")
    }

    "parse a month-first textual date with a weekday prefix" in {
      val res = response(Seq(field("INVOICE_RECEIPT_DATE", "Wed, Sep 2, 2026", 93.6)))
      parsed(res).transaction_date mustBe Some("2026-09-02")
    }

    "choose the highest-confidence date when several are present" in {
      val res = response(
        Seq(field("INVOICE_RECEIPT_DATE", "01/01/2020", 40.0), field("INVOICE_RECEIPT_DATE", "14/09/2026", 99.0))
      )
      parsed(res).transaction_date mustBe Some("2026-09-14")
    }
  }

  "reconstructText" should {
    "emit labeled summary fields, then line items, in document order" in {
      // Synthetic AnalyzeExpense response split across two ExpenseDocuments, as the async API returns it.
      val response = Json.parse(
        """{
          |  "ExpenseDocuments": [
          |    {
          |      "SummaryFields": [
          |        {
          |          "Type": { "Text": "VENDOR_NAME", "Confidence": 99.0 },
          |          "ValueDetection": { "Text": "EXAMPLE STORE", "Confidence": 98.0 }
          |        },
          |        {
          |          "Type": { "Text": "TOTAL", "Confidence": 99.0 },
          |          "LabelDetection": { "Text": "TOTAL" },
          |          "ValueDetection": { "Text": "3.50", "Confidence": 97.0 }
          |        }
          |      ],
          |      "LineItemGroups": [
          |        {
          |          "LineItemGroupIndex": 1,
          |          "LineItems": [
          |            {
          |              "LineItemExpenseFields": [
          |                {
          |                  "Type": { "Text": "ITEM", "Confidence": 95.0 },
          |                  "ValueDetection": { "Text": "BREAD", "Confidence": 95.0 }
          |                },
          |                {
          |                  "Type": { "Text": "PRICE", "Confidence": 95.0 },
          |                  "LabelDetection": { "Text": "EUR" },
          |                  "ValueDetection": { "Text": "1.50", "Confidence": 95.0 }
          |                }
          |              ]
          |            },
          |            {
          |              "LineItemExpenseFields": [
          |                {
          |                  "Type": { "Text": "ITEM", "Confidence": 95.0 },
          |                  "ValueDetection": { "Text": "MILK", "Confidence": 95.0 }
          |                }
          |              ]
          |            }
          |          ]
          |        }
          |      ]
          |    },
          |    {
          |      "SummaryFields": [
          |        {
          |          "Type": { "Text": "INVOICE_RECEIPT_DATE", "Confidence": 90.0 },
          |          "LabelDetection": { "Text": "Fecha" },
          |          "ValueDetection": { "Text": "01/02/2026", "Confidence": 90.0 }
          |        }
          |      ],
          |      "LineItemGroups": []
          |    }
          |  ]
          |}""".stripMargin
      )

      DocumentParser.reconstructText(response) mustBe
        List("EXAMPLE STORE", "TOTAL: 3.50", "Fecha: 01/02/2026", "BREAD", "EUR: 1.50", "MILK").mkString("\n")
    }

    "skip fields without a value" in {
      val response = Json.parse(
        """{
          |  "ExpenseDocuments": [
          |    {
          |      "SummaryFields": [
          |        {
          |          "Type": { "Text": "OTHER", "Confidence": 50.0 },
          |          "LabelDetection": { "Text": "Mesa" }
          |        },
          |        {
          |          "Type": { "Text": "TOTAL", "Confidence": 99.0 },
          |          "ValueDetection": { "Text": " 9.99 ", "Confidence": 97.0 }
          |        }
          |      ]
          |    }
          |  ]
          |}""".stripMargin
      )

      DocumentParser.reconstructText(response) mustBe "9.99"
    }

    "give empty text for a response without expense documents" in {
      val response = Json.parse(
        """{
          |  "ExpenseDocuments": []
          |}""".stripMargin
      )

      DocumentParser.reconstructText(response) mustBe ""
    }
  }
}
