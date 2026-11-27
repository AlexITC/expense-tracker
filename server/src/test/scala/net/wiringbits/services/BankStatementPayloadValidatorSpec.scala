package net.wiringbits.services

import net.wiringbits.services.BankStatementPayloadValidator.{Header, Row}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

class BankStatementPayloadValidatorSpec extends AnyWordSpec with Matchers {

  private val emptyHeader = Header(None, None, None, None, None, None)
  private val validHeader = Header(
    bankName = Some("Test Bank"),
    productType = Some("debit"),
    currency = Some("EUR"),
    accountLast4 = Some("0000"),
    openingBalance = Some(BigDecimal("100.00")),
    closingBalance = Some(BigDecimal("60"))
  )
  private val validRow = Row("TEST MERCHANT", BigDecimal("-40.00"), Some("purchase"), Some(BigDecimal("60.00")))

  "validateHeader" should {
    "accept valid values" in {
      BankStatementPayloadValidator.validateHeader(validHeader) must be(Right(()))
      BankStatementPayloadValidator.validateHeader(
        validHeader.copy(productType = Some("credit"), currency = Some("USD"))
      ) must be(
        Right(())
      )
    }

    "accept missing values" in {
      BankStatementPayloadValidator.validateHeader(emptyHeader) must be(Right(()))
    }

    "reject an unknown product type" in {
      BankStatementPayloadValidator.validateHeader(emptyHeader.copy(productType = Some("savings"))) must be(
        Left("Product type savings is not one of credit, debit")
      )
    }

    "reject a lowercase currency" in {
      BankStatementPayloadValidator.validateHeader(emptyHeader.copy(currency = Some("eur"))) must be(
        Left("Currency eur is not an ISO 4217 code")
      )
    }

    "reject an unknown currency" in {
      BankStatementPayloadValidator.validateHeader(emptyHeader.copy(currency = Some("ZZZ"))) must be(
        Left("Currency ZZZ is not an ISO 4217 code")
      )
    }

    "reject an opening balance with more than 2 decimal places" in {
      BankStatementPayloadValidator.validateHeader(
        emptyHeader.copy(openingBalance = Some(BigDecimal("100.005")))
      ) must be(
        Left("Opening balance 100.005 has more than 2 decimal places")
      )
    }

    "check lengths before formats" in {
      val v = BankStatementPayloadValidator.validateHeader
      v(emptyHeader.copy(bankName = Some("x" * 101))) must be(Left("Bank name is longer than 100 characters"))
      v(emptyHeader.copy(productType = Some("x" * 21))) must be(Left("Product type is longer than 20 characters"))
      v(emptyHeader.copy(currency = Some("ABCD"))) must be(Left("Currency is longer than 3 characters"))
      v(emptyHeader.copy(accountLast4 = Some("00000"))) must be(Left("Account last 4 is longer than 4 characters"))
    }
  }

  "validateRow" should {
    "accept valid values" in {
      BankStatementPayloadValidator.validateRow(1, validRow) must be(Right(()))
      BankStatementPayloadValidator.validateRow(1, validRow.copy(amount = BigDecimal("9999999999.99"))) must be(
        Right(())
      )
    }

    "accept missing optional values" in {
      BankStatementPayloadValidator.validateRow(1, validRow.copy(txnType = None, runningBalance = None)) must be(
        Right(())
      )
    }

    "accept trailing zeros beyond 2 decimal places" in {
      BankStatementPayloadValidator.validateRow(1, validRow.copy(amount = BigDecimal("12.340"))) must be(Right(()))
      BankStatementPayloadValidator.validateRow(1, validRow.copy(amount = BigDecimal("40.000"))) must be(Right(()))
      BankStatementPayloadValidator.validateRow(1, validRow.copy(amount = BigDecimal("-12.3450"))) must be(
        Left("Row 1: amount -12.3450 has more than 2 decimal places")
      )
    }

    "reject an amount with more than 2 decimal places" in {
      BankStatementPayloadValidator.validateRow(2, validRow.copy(amount = BigDecimal("12.345"))) must be(
        Left("Row 2: amount 12.345 has more than 2 decimal places")
      )
    }

    "reject an amount with more than 10 integer digits" in {
      BankStatementPayloadValidator.validateRow(1, validRow.copy(amount = BigDecimal("10000000000"))) must be(
        Left("Row 1: amount 10000000000 has more than 10 integer digits")
      )
    }

    "reject a running balance with more than 2 decimal places" in {
      BankStatementPayloadValidator.validateRow(1, validRow.copy(runningBalance = Some(BigDecimal("1.001")))) must be(
        Left("Row 1: running balance 1.001 has more than 2 decimal places")
      )
    }

    "reject an unknown type" in {
      BankStatementPayloadValidator.validateRow(1, validRow.copy(txnType = Some("transfer"))) must be(
        Left("Row 1: type transfer is not one of purchase, payment, fee, interest, refund")
      )
    }

    "keep the length messages" in {
      BankStatementPayloadValidator.validateRow(2, validRow.copy(description = "x" * 256)) must be(
        Left("Row 2: description is longer than 255 characters")
      )
      BankStatementPayloadValidator.validateRow(1, validRow.copy(txnType = Some("x" * 21))) must be(
        Left("Row 1: type is longer than 20 characters")
      )
    }
  }

  "validate" should {
    "accept a valid payload" in {
      BankStatementPayloadValidator.validate(validHeader, List(validRow, validRow)) must be(Right(()))
    }

    "report the first error, numbering rows from 1" in {
      val bad = validRow.copy(amount = BigDecimal("1.234"))
      BankStatementPayloadValidator.validate(validHeader, List(validRow, bad, bad.copy(txnType = Some("x")))) must be(
        Left("Row 2: amount 1.234 has more than 2 decimal places")
      )
    }

    "report a header error before row errors" in {
      BankStatementPayloadValidator.validate(
        validHeader.copy(currency = Some("ZZZ")),
        List(validRow.copy(amount = BigDecimal("1.234")))
      ) must be(Left("Currency ZZZ is not an ISO 4217 code"))
    }
  }
}
