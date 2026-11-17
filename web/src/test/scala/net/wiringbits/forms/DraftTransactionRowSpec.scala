package net.wiringbits.forms

import net.wiringbits.api.models.bankstatements.BankStatementDraft
import net.wiringbits.api.utils.BankStatementDraftLimits
import net.wiringbits.ui.forms.{ExpDraftDescription, ExpLocalDate, ExpSignedAmount}
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.LocalDate

class DraftTransactionRowSpec extends AnyWordSpec {

  val txn = BankStatementDraft.Transaction(
    date = LocalDate.parse("2026-03-04"),
    amount = BigDecimal("-12.34"),
    description = "TEST MERCHANT",
    txnType = Some("purchase"),
    runningBalance = Some(BigDecimal("100.00"))
  )
  val bare = txn.copy(txnType = None, runningBalance = None)

  def rows(n: Int): List[DraftTransactionRow] = List.tabulate(n)(DraftTransactionRow.blank)

  def filled(row: DraftTransactionRow): DraftTransactionRow =
    row.copy(
      date = row.date.updated(ExpLocalDate.validate("2026-03-05")),
      amount = row.amount.updated(ExpSignedAmount.validate("0")),
      description = row.description.updated(ExpDraftDescription.validate("TEST MERCHANT"))
    )

  "seeded" should {
    "round-trip a transaction through resolve" in {
      DraftTransactionRow.resolve(List(DraftTransactionRow.seeded(0, txn))) mustBe Some(List(txn))
    }
    "round-trip a transaction without the optional fields" in {
      DraftTransactionRow.resolve(List(DraftTransactionRow.seeded(0, bare))) mustBe Some(List(bare))
    }
    "keep an over-long description raw so it surfaces as an error" in {
      val long = "x" * (BankStatementDraftLimits.MaxDescriptionLength + 1)
      val row = DraftTransactionRow.seeded(0, txn.copy(description = long))
      row.description.inputValue mustBe long
      row.description.valueOpt mustBe None
    }
    "keep an over-long txnType raw so it surfaces as an error" in {
      val long = "x" * (BankStatementDraftLimits.MaxTxnTypeLength + 1)
      val row = DraftTransactionRow.seeded(0, txn.copy(txnType = Some(long)))
      row.txnType.inputValue mustBe long
      DraftTransactionRow.resolve(List(row)) mustBe None
    }
  }

  "fromTransactions" should {
    "seed one row per transaction with distinct keys" in {
      val result = DraftTransactionRow.fromTransactions(List(txn, bare))
      result.map(_.key).distinct.size mustBe 2
      DraftTransactionRow.resolve(result) mustBe Some(List(txn, bare))
    }
  }

  "blank" should {
    "be unresolvable until the required fields are filled" in {
      val row = DraftTransactionRow.blank(0)
      DraftTransactionRow.resolve(List(row)) mustBe None
      DraftTransactionRow.resolve(List(filled(row))) mustBe Some(
        List(
          BankStatementDraft.Transaction(
            date = LocalDate.parse("2026-03-05"),
            amount = BigDecimal("0"),
            description = "TEST MERCHANT",
            txnType = None,
            runningBalance = None
          )
        )
      )
    }
  }

  "nextKey" should {
    "be 0 for no rows and max + 1 otherwise" in {
      DraftTransactionRow.nextKey(Nil) mustBe 0
      DraftTransactionRow.nextKey(List(DraftTransactionRow.blank(3), DraftTransactionRow.blank(1))) mustBe 4
    }
  }

  "insertAfter" should {
    "insert after the first row" in {
      DraftTransactionRow.insertAfter(rows(3), 0).map(_.key) mustBe List(0, 3, 1, 2)
    }
    "insert after a middle row" in {
      DraftTransactionRow.insertAfter(rows(3), 1).map(_.key) mustBe List(0, 1, 3, 2)
    }
    "insert after the last row" in {
      DraftTransactionRow.insertAfter(rows(3), 2).map(_.key) mustBe List(0, 1, 2, 3)
    }
    "ignore an unknown key" in {
      DraftTransactionRow.insertAfter(rows(3), 42).map(_.key) mustBe List(0, 1, 2)
    }
    "insert below the cap" in {
      val max = BankStatementDraftLimits.MaxTransactions
      DraftTransactionRow.insertAfter(rows(max - 1), 0).size mustBe max
    }
    "be a no-op at the cap" in {
      val max = BankStatementDraftLimits.MaxTransactions
      DraftTransactionRow.insertAfter(rows(max), 0).size mustBe max
    }
  }

  "insertAtTop" should {
    "insert into an empty list" in {
      DraftTransactionRow.insertAtTop(Nil).map(_.key) mustBe List(0)
    }
    "insert above the existing rows" in {
      DraftTransactionRow.insertAtTop(rows(2)).map(_.key) mustBe List(2, 0, 1)
    }
    "be a no-op at the cap" in {
      val max = BankStatementDraftLimits.MaxTransactions
      DraftTransactionRow.insertAtTop(rows(max - 1)).size mustBe max
      DraftTransactionRow.insertAtTop(rows(max)).size mustBe max
    }
  }

  "canInsert" should {
    "be true below the cap and false at it" in {
      val max = BankStatementDraftLimits.MaxTransactions
      DraftTransactionRow.canInsert(rows(max - 1)) mustBe true
      DraftTransactionRow.canInsert(rows(max)) mustBe false
    }
  }

  "remove" should {
    "drop only the row with the key" in {
      DraftTransactionRow.remove(rows(3), 1).map(_.key) mustBe List(0, 2)
    }
  }

  "update" should {
    "change only the row with the key" in {
      val result = DraftTransactionRow.update(rows(2), 1, filled)
      result.map(r => DraftTransactionRow.resolve(List(r)).isDefined) mustBe List(false, true)
    }
  }

  "fields" should {
    "list five fields per row" in {
      DraftTransactionRow.fields(rows(2)).size mustBe 10
    }
  }

  "resolve" should {
    val row = DraftTransactionRow.seeded(0, txn)

    "be None when any row is invalid" in {
      DraftTransactionRow.resolve(List(row, DraftTransactionRow.blank(1))) mustBe None
    }
    "be None on an invalid date" in {
      DraftTransactionRow.resolve(List(row.copy(date = row.date.updated(ExpLocalDate.validate("2026-13-01"))))) mustBe
        None
    }
    "be None on an invalid amount" in {
      DraftTransactionRow.resolve(List(row.copy(amount = row.amount.updated(ExpSignedAmount.validate("abc"))))) mustBe
        None
    }
    "be None on an over-long description" in {
      val long = "x" * (BankStatementDraftLimits.MaxDescriptionLength + 1)
      DraftTransactionRow.resolve(
        List(row.copy(description = row.description.updated(ExpDraftDescription.validate(long))))
      ) mustBe None
    }
    "be None on an invalid running balance" in {
      DraftTransactionRow.resolve(
        List(row.copy(runningBalance = row.runningBalance.updated(ExpSignedAmount.optionalValidator("abc"))))
      ) mustBe None
    }
  }
}
