package net.wiringbits.services

import net.wiringbits.api.models.bankstatements.BankStatementDraft.Transaction
import net.wiringbits.api.utils.BankStatementDraftLimits
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.time.LocalDate

class StatementCsvParserSpec extends AnyWordSpec {

  private val parser = new StatementCsvParser.FixedSchema

  private def parseText(text: String) = parser.parse(text.getBytes(StandardCharsets.UTF_8))

  private def txn(date: String, amount: String, description: String) =
    Transaction(
      date = LocalDate.parse(date),
      amount = BigDecimal(amount),
      description = description,
      txnType = None,
      runningBalance = None
    )

  private def rows(n: Int): String =
    (1 to n).map(i => s"2026-01-01,TEST MERCHANT $i,-12.34").mkString("\n")

  private def mustFailWith(result: Either[String, List[Transaction]], fragment: String) =
    result match {
      case Left(error) => error must include(fragment)
      case Right(txns) => fail(s"Expected a failure containing '$fragment' but parsed $txns")
    }

  "parse" should {

    "parse a valid file keeping amount signs as written" in {
      val csv =
        """date,description,amount
          |2026-01-02,TEST MERCHANT,-12.34
          |2026-01-03,TEST REFUND,+5
          |2026-01-04,TEST DEPOSIT,100.50""".stripMargin
      parseText(csv) mustBe Right(
        List(
          txn("2026-01-02", "-12.34", "TEST MERCHANT"),
          txn("2026-01-03", "5", "TEST REFUND"),
          txn("2026-01-04", "100.50", "TEST DEPOSIT")
        )
      )
    }

    "parse quoted fields with embedded commas, quotes and newlines" in {
      val csv = "date,description,amount\n2026-01-02,\"TEST, \"\"QUOTED\"\"\nMERCHANT\",-12.34\n"
      parseText(csv) mustBe Right(List(txn("2026-01-02", "-12.34", "TEST, \"QUOTED\"\nMERCHANT")))
    }

    "keep surrounding whitespace in descriptions" in {
      val csv = "date,description,amount\n2026-01-02, TEST MERCHANT ,-12.34"
      parseText(csv) mustBe Right(List(txn("2026-01-02", "-12.34", " TEST MERCHANT ")))
    }

    "parse optional columns when present, in any column order" in {
      val csv =
        """running_balance,txn_type,amount,description,date
          |87.66,purchase,-12.34,TEST MERCHANT,2026-01-02""".stripMargin
      parseText(csv) mustBe Right(
        List(
          txn("2026-01-02", "-12.34", "TEST MERCHANT").copy(txnType = Some("purchase"), runningBalance = Some(87.66))
        )
      )
    }

    "leave optional values as None when the columns are blank" in {
      val csv = "date,description,amount,txn_type,running_balance\n2026-01-02,TEST MERCHANT,-12.34,,"
      parseText(csv) mustBe Right(List(txn("2026-01-02", "-12.34", "TEST MERCHANT")))
    }

    "leave optional values as None when the columns are absent" in {
      parseText("date,description,amount\n" + rows(1)) mustBe Right(
        List(txn("2026-01-01", "-12.34", "TEST MERCHANT 1"))
      )
    }

    "match headers case-insensitively" in {
      val csv = "DATE,Description,AMOUNT,Txn_Type,RUNNING_BALANCE\n2026-01-02,TEST MERCHANT,-12.34,fee,1.00"
      parseText(csv) mustBe Right(
        List(txn("2026-01-02", "-12.34", "TEST MERCHANT").copy(txnType = Some("fee"), runningBalance = Some(1)))
      )
    }

    "accept both CRLF and LF line endings" in {
      val lf = "date,description,amount\n2026-01-02,TEST MERCHANT,-12.34\n"
      val crlf = "date,description,amount\r\n2026-01-02,TEST MERCHANT,-12.34\r\n"
      val expected = Right(List(txn("2026-01-02", "-12.34", "TEST MERCHANT")))
      parseText(lf) mustBe expected
      parseText(crlf) mustBe expected
    }

    "leave optional values as None when the columns are whitespace-only" in {
      val csv = "date,description,amount,txn_type,running_balance\n2026-01-02,TEST MERCHANT,-12.34,   ,   "
      parseText(csv) mustBe Right(List(txn("2026-01-02", "-12.34", "TEST MERCHANT")))
    }

    // Row numbers are commons-csv record numbers, which do not count ignored empty lines.
    "report the record-based row number after ignored empty lines" in {
      val csv = "date,description,amount\n\n\n2026-01-02,TEST MERCHANT,-12.34\n\n2026-01-03,TEST MERCHANT,abc"
      parseText(csv) mustBe Left("Row 3, column amount: \"abc\" is not a decimal number")
    }

    "accept amounts and running balances at the precision limit" in {
      val csv = "date,description,amount,running_balance\n2026-01-02,TEST MERCHANT,-9999999999.99,9999999999.99"
      parseText(csv) mustBe Right(
        List(
          txn("2026-01-02", "-9999999999.99", "TEST MERCHANT").copy(runningBalance = Some(BigDecimal("9999999999.99")))
        )
      )
    }

    "reject amounts and running balances over the precision limit" in {
      List("12345678901", "-12345678901.00", "1.005").foreach { raw =>
        mustFailWith(
          parseText(s"date,description,amount\n2026-01-02,TEST MERCHANT,$raw"),
          "Row 2, column amount: must have at most 10 integer digits and 2 decimals"
        )
        mustFailWith(
          parseText(s"date,description,amount,running_balance\n2026-01-02,TEST MERCHANT,-12.34,$raw"),
          "Row 2, column running_balance: must have at most 10 integer digits and 2 decimals"
        )
      }
    }

    "reject a huge digit string without parsing it" in {
      mustFailWith(
        parseText(s"date,description,amount\n2026-01-02,TEST MERCHANT,${"9" * 100000}"),
        "Row 2, column amount: must have at most 10 integer digits"
      )
    }

    "ignore empty lines" in {
      val csv = "date,description,amount\n\n2026-01-02,TEST MERCHANT,-12.34\n\n"
      parseText(csv) mustBe Right(List(txn("2026-01-02", "-12.34", "TEST MERCHANT")))
    }

    // Spreadsheet exports commonly start with a UTF-8 BOM; a single leading one is stripped so the first header
    // still reads as "date".
    "strip a single leading BOM" in {
      parseText("﻿date,description,amount\n2026-01-02,TEST MERCHANT,-12.34") mustBe Right(
        List(txn("2026-01-02", "-12.34", "TEST MERCHANT"))
      )
    }

    // Only one BOM is stripped; a second one stays part of the first header name and is rejected as unknown.
    "reject a second leading BOM" in {
      mustFailWith(parseText("﻿﻿date,description,amount\n2026-01-02,TEST MERCHANT,-12.34"), "unknown column")
    }

    "reject invalid UTF-8" in {
      val bytes = "date,description,amount\n2026-01-02,TEST ".getBytes(StandardCharsets.UTF_8) ++
        Array(0xc3.toByte, 0x28.toByte) ++ ",-12.34".getBytes(StandardCharsets.UTF_8)
      mustFailWith(parser.parse(bytes), "not valid UTF-8")
    }

    "accept a file of exactly MaxCsvBytes" in {
      val base = "date,description,amount\n2026-01-02,TEST MERCHANT,-12.34\n"
      val padding = "\n" * (BankStatementDraftLimits.MaxCsvBytes - base.length)
      val bytes = (base + padding).getBytes(StandardCharsets.UTF_8)
      bytes.length mustBe BankStatementDraftLimits.MaxCsvBytes
      parser.parse(bytes) mustBe Right(List(txn("2026-01-02", "-12.34", "TEST MERCHANT")))
    }

    "reject a file one byte over MaxCsvBytes" in {
      val base = "date,description,amount\n2026-01-02,TEST MERCHANT,-12.34\n"
      val padding = "\n" * (BankStatementDraftLimits.MaxCsvBytes - base.length + 1)
      mustFailWith(parseText(base + padding), "larger than")
    }

    "reject an empty file" in {
      mustFailWith(parseText(""), "empty")
      mustFailWith(parseText("\n\n"), "empty")
    }

    "reject a header-only file" in {
      mustFailWith(parseText("date,description,amount\n"), "no transactions")
    }

    "accept exactly MaxTransactions rows" in {
      val result = parseText("date,description,amount\n" + rows(BankStatementDraftLimits.MaxTransactions))
      result.map(_.size) mustBe Right(BankStatementDraftLimits.MaxTransactions)
    }

    "reject more than MaxTransactions rows" in {
      mustFailWith(
        parseText("date,description,amount\n" + rows(BankStatementDraftLimits.MaxTransactions + 1)),
        s"more than ${BankStatementDraftLimits.MaxTransactions} transactions"
      )
    }

    "reject unknown columns" in {
      mustFailWith(
        parseText("date,description,amount,notes\n2026-01-02,TEST MERCHANT,-12.34,x"),
        "Row 1: unknown column(s) \"notes\""
      )
    }

    "reject header names with surrounding whitespace" in {
      mustFailWith(
        parseText("date, description,amount\n2026-01-02,TEST MERCHANT,-12.34"),
        "unknown column(s) \" description\""
      )
    }

    "reject duplicated columns regardless of case" in {
      mustFailWith(
        parseText("date,description,amount,Amount\n2026-01-02,TEST MERCHANT,-12.34,-12.34"),
        "duplicated column(s) \"amount\""
      )
    }

    "reject missing required columns" in {
      mustFailWith(parseText("date,description\n2026-01-02,TEST MERCHANT"), "missing required column(s) \"amount\"")
    }

    "reject a row with fewer fields than the header" in {
      mustFailWith(
        parseText("date,description,amount\n2026-01-02,TEST MERCHANT"),
        "Row 2: expected 3 fields but found 2"
      )
    }

    "reject a row with more fields than the header" in {
      mustFailWith(
        parseText("date,description,amount\n2026-01-02,TEST MERCHANT,-12.34\n2026-01-03,TEST MERCHANT,-1,extra"),
        "Row 3: expected 3 fields but found 4"
      )
    }

    "reject an unterminated quote naming the row" in {
      mustFailWith(
        parseText("date,description,amount\n2026-01-02,TEST MERCHANT,-12.34\n2026-01-03,\"TEST MERCHANT,-12.34\n"),
        "Row 3: malformed CSV"
      )
    }

    "reject dates that are not strict ISO" in {
      List("2026-02-30", "02/01/2026", "2026-1-2", " 2026-01-02", "").foreach { raw =>
        mustFailWith(
          parseText(s"date,description,amount\n$raw,TEST MERCHANT,-12.34"),
          s"Row 2, column date: \"$raw\" is not a date"
        )
      }
    }

    "reject amounts that are not plain decimals" in {
      List("abc", "1,234.00", "1e3", "12.", ".5", "$12.34", " -12.34", "").foreach { raw =>
        mustFailWith(
          parseText(s"date,description,amount\n2026-01-02,TEST MERCHANT,\"$raw\""),
          s"Row 2, column amount: \"$raw\" is not a decimal number"
        )
      }
    }

    "reject a non-decimal running balance" in {
      mustFailWith(
        parseText("date,description,amount,running_balance\n2026-01-02,TEST MERCHANT,-12.34,abc"),
        "Row 2, column running_balance: \"abc\" is not a decimal number"
      )
    }

    "reject a blank description" in {
      mustFailWith(
        parseText("date,description,amount\n2026-01-02,   ,-12.34"),
        "Row 2, column description: must not be blank"
      )
      mustFailWith(
        parseText("date,description,amount\n2026-01-02,,-12.34"),
        "Row 2, column description: must not be blank"
      )
    }

    "accept a description at the length limit" in {
      val description = "D" * BankStatementDraftLimits.MaxDescriptionLength
      parseText(s"date,description,amount\n2026-01-02,$description,-12.34") mustBe Right(
        List(txn("2026-01-02", "-12.34", description))
      )
    }

    "reject a description over the length limit" in {
      val description = "D" * (BankStatementDraftLimits.MaxDescriptionLength + 1)
      mustFailWith(
        parseText(s"date,description,amount\n2026-01-02,$description,-12.34"),
        s"Row 2, column description: is longer than ${BankStatementDraftLimits.MaxDescriptionLength} characters"
      )
    }

    "reject an unknown txn_type" in {
      mustFailWith(
        parseText("date,description,amount,txn_type\n2026-01-02,TEST MERCHANT,-12.34,Purchase"),
        s"Row 2, column txn_type: \"Purchase\" is not one of ${BankStatementDraftLimits.TxnTypes.mkString(", ")}"
      )
    }

    "reject a txn_type over the length limit" in {
      val txnType = "t" * (BankStatementDraftLimits.MaxTxnTypeLength + 1)
      mustFailWith(
        parseText(s"date,description,amount,txn_type\n2026-01-02,TEST MERCHANT,-12.34,$txnType"),
        s"Row 2, column txn_type: is longer than ${BankStatementDraftLimits.MaxTxnTypeLength} characters"
      )
    }
  }
}
