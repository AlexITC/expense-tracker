package net.wiringbits.services

import net.wiringbits.models.documents.DocumentKind
import play.api.libs.json.*

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import scala.util.Try

object DocumentParser {
  val currentVersion = "parser-v4"

  case class LineItem(
      line_index: Int,
      description: Option[String],
      quantity: Option[BigDecimal],
      unit_price: Option[BigDecimal],
      price: Option[BigDecimal],
      product_code: Option[String],
      raw_row: String
  )
  object LineItem {
    // Write null explicitly for all Option fields so output matches fixture format
    implicit val format: Format[LineItem] = Format(
      Json.reads[LineItem],
      OWrites { li =>
        Json.obj(
          "line_index" -> li.line_index,
          "description" -> li.description,
          "quantity" -> li.quantity,
          "unit_price" -> li.unit_price,
          "price" -> li.price,
          "product_code" -> li.product_code,
          "raw_row" -> li.raw_row
        )
      }
    )
  }

  case class ParsedData(
      vendor_name: Option[String],
      tax_id: Option[String],
      transaction_date: Option[String],
      transaction_time: Option[String],
      ticket_id: Option[String],
      amount: Option[BigDecimal],
      currency: Option[String],
      fx_rate: Option[BigDecimal],
      base_amount: Option[BigDecimal],
      subtotal: Option[BigDecimal],
      tax_amount: Option[BigDecimal],
      ticket_subtype: Option[String],
      line_items: List[LineItem]
  )
  object ParsedData {
    // Write null explicitly for all Option fields so output matches fixture format
    implicit val format: Format[ParsedData] = Format(
      Json.reads[ParsedData],
      OWrites { pd =>
        Json.obj(
          "vendor_name" -> pd.vendor_name,
          "tax_id" -> pd.tax_id,
          "transaction_date" -> pd.transaction_date,
          "transaction_time" -> pd.transaction_time,
          "ticket_id" -> pd.ticket_id,
          "amount" -> pd.amount,
          "currency" -> pd.currency,
          "fx_rate" -> pd.fx_rate,
          "base_amount" -> pd.base_amount,
          "subtotal" -> pd.subtotal,
          "tax_amount" -> pd.tax_amount,
          "ticket_subtype" -> pd.ticket_subtype,
          "line_items" -> pd.line_items
        )
      }
    )
  }

  sealed trait ParseFailure
  case class UnsupportedKind(kind: String) extends ParseFailure
  case class ParseError(reason: String) extends ParseFailure

  def parse(response: JsValue, kind: DocumentKind): Either[ParseFailure, ParsedData] =
    kind match {
      case DocumentKind.Receipt | DocumentKind.Invoice => Right(extractFromTextract(response))
      case _ => Left(UnsupportedKind(kind.entryName))
    }

  /** Rebuilds plain text from a cached AnalyzeExpense response, which has no LINE blocks: one "label: value" line per
    * field (just the value when Textract found no label), summary fields first and then line items, in document order.
    */
  def reconstructText(response: JsValue): String = {
    val docs = (response \ "ExpenseDocuments").asOpt[JsArray].map(_.value.toList).getOrElse(Nil)
    val summaryFields = docs.flatMap(d => (d \ "SummaryFields").asOpt[JsArray].map(_.value.toList).getOrElse(Nil))
    val lineItemFields = for {
      doc <- docs
      group <- (doc \ "LineItemGroups").asOpt[JsArray].map(_.value.toList).getOrElse(Nil)
      item <- (group \ "LineItems").asOpt[JsArray].map(_.value.toList).getOrElse(Nil)
      field <- (item \ "LineItemExpenseFields").asOpt[JsArray].map(_.value.toList).getOrElse(Nil)
    } yield field
    (summaryFields ++ lineItemFields)
      .flatMap { f =>
        val label = fieldLabel(f).trim
        val value = fieldValue(f).trim
        if (value.isEmpty) None
        else if (label.isEmpty) Some(value)
        else Some(s"$label: $value")
      }
      .mkString("\n")
  }

  private val monthMap: Map[String, String] = Map(
    "jan" -> "01",
    "feb" -> "02",
    "mar" -> "03",
    "apr" -> "04",
    "may" -> "05",
    "jun" -> "06",
    "jul" -> "07",
    "aug" -> "08",
    "sep" -> "09",
    "oct" -> "10",
    "nov" -> "11",
    "dec" -> "12",
    "ene" -> "01",
    "abr" -> "04",
    "ago" -> "08",
    "dic" -> "12"
  )

  private def fieldType(f: JsValue): String = (f \ "Type" \ "Text").asOpt[String].getOrElse("")
  private def fieldTypeConf(f: JsValue): Double = (f \ "Type" \ "Confidence").asOpt[Double].getOrElse(0.0)
  private def fieldValue(f: JsValue): String = (f \ "ValueDetection" \ "Text").asOpt[String].getOrElse("")
  private def fieldValueConf(f: JsValue): Double = (f \ "ValueDetection" \ "Confidence").asOpt[Double].getOrElse(0.0)
  private def fieldLabel(f: JsValue): String = (f \ "LabelDetection" \ "Text").asOpt[String].getOrElse("")

  private def bestField(fields: List[JsValue], types: String*): Option[JsValue] =
    fields.filter(f => types.contains(fieldType(f))).maxByOption(fieldTypeConf)

  private def extractVendorName(fields: List[JsValue]): Option[String] =
    bestField(fields, "VENDOR_NAME", "NAME")
      .map(f => fieldValue(f).replace("\n", " ").trim)
      .filter(_.nonEmpty)

  private def normalizedLabel(f: JsValue): String =
    fieldLabel(f).replaceAll("[^a-zA-Z]", "").toLowerCase

  private val taxIdLabelKeywords = Seq("nif", "cif", "rfc", "comercio")

  // Spanish company CIF prefixes: A-H, J-N, P-S, U-W — excludes X/Y/Z (foreign NIE) and personal NIF letters
  private val companyCifPattern = "[A-HJ-W]-?\\d{7,8}[A-Z0-9]?"

  private def extractTaxId(fields: List[JsValue]): Option[String] =
    bestField(fields, "TAX_PAYER_ID")
      .map(f => fieldValue(f).trim)
      .filter(_.nonEmpty)
      .orElse {
        // OTHER field with NIF/CIF/RFC/Comercio label (dots stripped for matching N.I.F., C.I.F.)
        fields
          .filter(f => fieldType(f) == "OTHER")
          .find(f => taxIdLabelKeywords.exists(normalizedLabel(f).contains))
          .map(f => fieldValue(f).trim)
          .filter(_.nonEmpty)
      }
      .orElse {
        // Search ALL VENDOR_ADDRESS fields (not just highest-confidence) for tax ID patterns
        val addressTexts = fields.filter(f => fieldType(f) == "VENDOR_ADDRESS").map(fieldValue)
        // Pattern: "NIF A12345678" or "N.I.F. A-28017895" (handles dots)
        addressTexts
          .flatMap { text =>
            "(?i)N\\.?I\\.?F\\.?\\s+([A-HJ-W][A-Z0-9-]{6,11})".r
              .findFirstMatchIn(text)
              .map(_.group(1).trim)
              .filter(_.nonEmpty)
          }
          .headOption
          .orElse {
            // Standalone company CIF line (e.g. "B-10438638" on its own line)
            addressTexts.flatMap { text =>
              text
                .split("\n")
                .view
                .map(_.trim)
                .find(line => line.matches(companyCifPattern))
                .filter(_.nonEmpty)
            }.headOption
          }
          .orElse {
            // Inline company CIF pattern (e.g. "A-28017895 Registro Mercantil")
            addressTexts.flatMap { text =>
              companyCifPattern.r.findFirstMatchIn(text).map(_.matched.trim).filter(_.nonEmpty)
            }.headOption
          }
      }

  private val dateFormats: List[DateTimeFormatter] = List(
    "dd/MM/yyyy",
    "dd/MM/yy",
    "d/M/yyyy",
    "d/M/yy",
    "yyyy-MM-dd",
    "dd-MM-yyyy",
    "dd-MM-yy",
    "d-M-yyyy",
    "d-M-yy",
    "dd.MM.yyyy",
    "dd.MM.yy",
    "d.M.yyyy",
    "d.M.yy"
  ).map(DateTimeFormatter.ofPattern)

  private def parseDate(raw: String): Option[String] = {
    val s = raw.trim
    if (s.isEmpty) None
    else
      dateFormats
        .flatMap(fmt => Try(LocalDate.parse(s, fmt)).toOption)
        .headOption
        .map(_.toString)
        .orElse {
          // Leading numeric date followed by time/junk, e.g. "28/08/2026 13: 36" or "26/06/2026 - 15:42:01" (dd/MM/yyyy)
          "(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{2,4})".r.findFirstMatchIn(s).flatMap { m =>
            val day = m.group(1).toInt
            val mo = m.group(2).toInt
            val yr = m.group(3)
            val year = if (yr.length == 2) "20" + yr else yr
            if (mo >= 1 && mo <= 12 && day >= 1 && day <= 31) Some(f"$year-$mo%02d-$day%02d") else None
          }
        }
        .orElse {
          // "13MAY26" or "13may26"
          val r1 = "(?i)(\\d{1,2})([a-z]{3})(\\d{2,4})".r
          r1.findFirstMatchIn(s.replaceAll("\\s", "")).flatMap { m =>
            monthMap.get(m.group(2).toLowerCase).map { mo =>
              val yr = m.group(3)
              val year = if (yr.length == 2) "20" + yr else yr
              f"$year-$mo-${m.group(1).toInt}%02d"
            }
          }
        }
        .orElse {
          // "07/feb/26" style
          val r2 = "(?i)(\\d{1,2})[/\\-.](\\w{3})[/\\-.](\\d{2,4})".r
          r2.findFirstMatchIn(s).flatMap { m =>
            monthMap.get(m.group(2).toLowerCase).map { mo =>
              val yr = m.group(3)
              val year = if (yr.length == 2) "20" + yr else yr
              f"$year-$mo-${m.group(1).toInt}%02d"
            }
          }
        }
        .orElse {
          // "2 sept 2026" / "02 Sep 2026" — day, month word, 4-digit year
          val r3 = "(?i)(\\d{1,2})\\s+([a-z]{3,})\\.?\\s+(\\d{4})".r
          r3.findFirstMatchIn(s).flatMap { m =>
            monthMap.get(m.group(2).take(3).toLowerCase).map(mo => f"${m.group(3)}-$mo-${m.group(1).toInt}%02d")
          }
        }
        .orElse {
          // "Sep 2, 2026" / "Wed, Sep 2, 2026" — month word, day, 4-digit year
          val r4 = "(?i)([a-z]{3,})\\.?\\s+(\\d{1,2}),?\\s+(\\d{4})".r
          r4.findFirstMatchIn(s).flatMap { m =>
            monthMap.get(m.group(1).take(3).toLowerCase).map(mo => f"${m.group(3)}-$mo-${m.group(2).toInt}%02d")
          }
        }
  }

  // Like selectBestAmount: across all ExpenseDocuments pick the highest-confidence date field that parses.
  private def selectBestDate(fields: List[JsValue], types: String*): Option[String] =
    fields
      .filter(f => types.contains(fieldType(f)))
      .flatMap(f => parseDate(fieldValue(f)).map(d => (d, fieldValueConf(f))))
      .maxByOption(_._2)
      .map(_._1)

  private def extractDate(fields: List[JsValue]): Option[String] =
    selectBestDate(fields, "INVOICE_RECEIPT_DATE", "ORDER_DATE")
      .orElse {
        fields
          .filter(f => fieldType(f) == "OTHER")
          .flatMap { f =>
            val label = fieldLabel(f).toLowerCase.trim
            val value = fieldValue(f)
            if (Seq("fecha", "date", "intervencion", "dia de").exists(label.contains))
              parseDate(value).toList
            else
              monthMap
                .get(label)
                .flatMap { mo =>
                  // e.g. label="DIC", value="20, 2025" → 2025-12-20
                  "(\\d{1,2})[,\\s]+(\\d{4})".r.findFirstMatchIn(value).map { m =>
                    f"${m.group(2)}-$mo-${m.group(1).toInt}%02d"
                  }
                }
                .toList
          }
          .headOption
      }

  private def extractTransactionTime(fields: List[JsValue]): Option[String] = {
    val timeKeywords = Seq("hora", "time", "hour")
    fields
      .filter(f => fieldType(f) == "OTHER")
      .find(f => timeKeywords.exists(normalizedLabel(f).contains))
      .flatMap { f =>
        "(\\d{1,2})\\s*:\\s*(\\d{2})".r.findFirstMatchIn(fieldValue(f)).map { m =>
          f"${m.group(1).toInt}%02d:${m.group(2)}:00"
        }
      }
  }

  private def extractReceiptId(fields: List[JsValue]): Option[String] =
    bestField(fields, "INVOICE_RECEIPT_ID").map(f => fieldValue(f).trim).filter(_.nonEmpty)

  // Returns Some(0) for a blank TOTAL field (receipt found, amount unreadable)
  private def parseAmount(raw: String): Option[BigDecimal] = {
    val s = raw.trim
    if (s.isEmpty) Some(BigDecimal(0))
    else {
      val v0 = s
        .replaceAll("(?i)\\bBUROS\\b|\\bEUROS\\b|\\bEUR\\b|\\bUSD\\b", "")
        .replaceAll("(?<=\\d)[A-Za-z]+(?=\\d)", "")
        .replaceAll("[€$£*]", "")
        .replaceAll("\n.*", "")
        .trim
        .replaceAll("[A-Za-z\\s]+$", "")
        .trim
      // Strip OCR context chars like leading "(" or "X" and trailing ")" — only when digits are present
      val v0c =
        if (v0.isEmpty || !v0.exists(_.isDigit)) v0
        else v0.replaceAll("^[^\\d-]+", "").replaceAll("[^\\d,.]+$", "").trim
      if (v0c.isEmpty) Some(BigDecimal(0))
      else {
        val v1 =
          if (v0c.matches("\\d{1,3}(\\.\\d{3})+,\\d{1,2}"))
            v0c.replace(".", "").replace(",", ".")
          else
            v0c.replace(",", ".")
        Try(BigDecimal(v1)).toOption
      }
    }
  }

  private def lineItemSum(groups: List[JsValue]): Option[BigDecimal] = {
    val prices = for {
      g <- groups
      li <- (g \ "LineItems").asOpt[JsArray].map(_.value.toList).getOrElse(Nil)
      f <- (li \ "LineItemExpenseFields").asOpt[JsArray].map(_.value.toList).getOrElse(Nil)
      if (f \ "Type" \ "Text").asOpt[String].contains("PRICE")
      a <- parseAmount((f \ "ValueDetection" \ "Text").asOpt[String].getOrElse(""))
    } yield a
    if (prices.isEmpty) None else Some(prices.sum)
  }

  // How many value-detection confidence points below the top a candidate may be and still count as "most confident".
  private val AmountConfidenceMargin = 1.0

  // Textract types amounts unreliably (it tags subtotals and taxes as TOTAL), but the human-readable LabelDetection next
  // to a number marks the amount actually charged after discounts/gift cards — the value that reconciles with the bank.
  // Keywords are Spanish (receipts are from Spain); matching is lowercase substring.
  private val chargeLabelKeywords = Seq("a pagar", "cargo en cuenta", "cargo a cuenta", "a cobrar", "cobrado")

  private def labelOf(f: JsValue): String = fieldLabel(f).toLowerCase

  // Parsed, positive amount candidates of the given types, each with its value-detection confidence and lowercase label.
  private def amountCandidates(fields: List[JsValue], types: String*): List[(BigDecimal, Double, String)] =
    fields
      .filter(f => types.contains(fieldType(f)))
      .flatMap(f => parseAmount(fieldValue(f)).filter(_ > 0).map(a => (a, fieldValueConf(f), labelOf(f))))

  // AnalyzeExpense (async) splits one receipt into many ExpenseDocuments, so a type like TOTAL appears several times
  // (grand total, tax, partial payments, OCR noise). Among the most-confident detections the grand total is the largest
  // — tax/subtotals/partial payments are smaller — so we take the max value within a small margin of the top
  // value-detection confidence, staying anchored to the highest-confidence detections rather than the largest number.
  private def pickAmount(candidates: List[(BigDecimal, Double, String)]): Option[BigDecimal] =
    candidates match {
      case Nil => None
      case cs =>
        val maxConf = cs.map(_._2).max
        Some(cs.filter(_._2 >= maxConf - AmountConfidenceMargin).map(_._1).max)
    }

  private def selectBestAmount(fields: List[JsValue], types: String*): Option[BigDecimal] =
    pickAmount(amountCandidates(fields, types*))

  private def extractAmountAndCurrency(
      fields: List[JsValue],
      groups: List[JsValue]
  ): (Option[BigDecimal], Option[String]) = {
    // 1. An amount explicitly labeled as the charge (post-discount / gift-card) — e.g. "TOTAL A PAGAR", "CARGO EN CUENTA".
    val charged = amountCandidates(fields, "TOTAL", "AMOUNT_PAID", "AMOUNT_DUE")
      .filter(c => chargeLabelKeywords.exists(c._3.contains))
    // 2. A value detected as BOTH a total and an amount-paid/due is almost certainly the real grand total; this rejects a
    //    tax or subtotal that Textract mistyped as TOTAL without relying on fragile label keywords.
    val totals = amountCandidates(fields, "TOTAL")
    val paidValues = amountCandidates(fields, "AMOUNT_PAID", "AMOUNT_DUE").map(_._1).toSet
    val corroborated = totals.filter(c => paidValues.contains(c._1))
    val amount = pickAmount(charged)
      .orElse(pickAmount(corroborated))
      .orElse(pickAmount(totals))
      .orElse(selectBestAmount(fields, "AMOUNT_PAID"))
      .orElse(lineItemSum(groups))
      .orElse(Some(BigDecimal(0)))
    val currency = fields
      .filter(f => fieldType(f) == "TOTAL")
      .flatMap(f => (f \ "Currency" \ "Code").asOpt[String].filter(_.nonEmpty))
      .headOption
      .orElse(Some("EUR"))
    (amount, currency)
  }

  private def extractPositiveAmount(fields: List[JsValue], typeName: String): Option[BigDecimal] =
    selectBestAmount(fields, typeName)

  private def extractLineItems(groups: List[JsValue]): List[LineItem] = {
    val allItems = groups.flatMap { g =>
      (g \ "LineItems").asOpt[JsArray].map(_.value.toList).getOrElse(Nil)
    }
    allItems.zipWithIndex.map { case (li, index) =>
      val itemFields = (li \ "LineItemExpenseFields").asOpt[JsArray].map(_.value.toList).getOrElse(Nil)
      def getFieldVal(typ: String): Option[String] =
        itemFields
          .find(f => (f \ "Type" \ "Text").asOpt[String].contains(typ))
          .map(f => (f \ "ValueDetection" \ "Text").asOpt[String].getOrElse("").trim)
          .filter(_.nonEmpty)
      def getFieldAmt(typ: String): Option[BigDecimal] =
        itemFields
          .find(f => (f \ "Type" \ "Text").asOpt[String].contains(typ))
          .flatMap(f => parseAmount((f \ "ValueDetection" \ "Text").asOpt[String].getOrElse("")))
      LineItem(
        line_index = index,
        description = getFieldVal("ITEM"),
        quantity = getFieldAmt("QUANTITY"),
        unit_price = getFieldAmt("UNIT_PRICE"),
        price = getFieldAmt("PRICE"),
        product_code = getFieldVal("PRODUCT_CODE"),
        raw_row = getFieldVal("EXPENSE_ROW").getOrElse("")
      )
    }
  }

  private def extractFromTextract(response: JsValue): ParsedData = {
    // AnalyzeExpense (async) returns multiple ExpenseDocuments for a single receipt and spreads the summary/line-item
    // fields across them, so we must aggregate every document rather than reading only the first.
    val docs = (response \ "ExpenseDocuments").asOpt[JsArray].map(_.value.toList).getOrElse(Nil)
    val summaryFields = docs.flatMap(d => (d \ "SummaryFields").asOpt[JsArray].map(_.value.toList).getOrElse(Nil))
    val lineItemGroups = docs.flatMap(d => (d \ "LineItemGroups").asOpt[JsArray].map(_.value.toList).getOrElse(Nil))
    val lineItems = extractLineItems(lineItemGroups)
    val (amount, currency) = extractAmountAndCurrency(summaryFields, lineItemGroups)
    ParsedData(
      vendor_name = extractVendorName(summaryFields),
      tax_id = extractTaxId(summaryFields),
      transaction_date = extractDate(summaryFields),
      transaction_time = extractTransactionTime(summaryFields),
      ticket_id = extractReceiptId(summaryFields),
      amount = amount,
      currency = currency,
      fx_rate = amount.map(_ => BigDecimal("1.0")),
      base_amount = amount,
      subtotal = extractPositiveAmount(summaryFields, "SUBTOTAL"),
      tax_amount = extractPositiveAmount(summaryFields, "TAX"),
      ticket_subtype = Some(if (lineItems.nonEmpty) "itemized" else "summary"),
      line_items = lineItems
    )
  }
}
