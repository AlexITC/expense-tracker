package net.wiringbits.components.pages

import net.wiringbits.components.pages.BankStatementReviewDrawer.{DrawerForm, activeForm}
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

class BankStatementReviewDrawerSpec extends AnyWordSpec {

  private val creating = UUID.randomUUID()
  private val refund = UUID.randomUUID()
  private val linking = UUID.randomUUID()
  private val rule = UUID.randomUUID()

  "activeForm" should {
    "return None when no form is open" in {
      activeForm(None, None, None, None) must be(None)
    }

    "prefer the expense form over all others" in {
      activeForm(Some(creating), Some(refund), Some(linking), Some(rule)) must be(
        Some(creating -> DrawerForm.CreateExpense)
      )
    }

    "prefer the refund form over linking and rule" in {
      activeForm(None, Some(refund), Some(linking), Some(rule)) must be(Some(refund -> DrawerForm.CreateRefund))
    }

    "prefer the link picker over the rule form" in {
      activeForm(None, None, Some(linking), Some(rule)) must be(Some(linking -> DrawerForm.LinkExpense))
    }

    "return the rule form when it is the only one open" in {
      activeForm(None, None, None, Some(rule)) must be(Some(rule -> DrawerForm.CreateRule))
    }
  }
}
