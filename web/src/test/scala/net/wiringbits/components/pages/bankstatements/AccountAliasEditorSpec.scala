package net.wiringbits.components.pages.bankstatements

import net.wiringbits.api.models.bankstatements.SetAccountAlias
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class AccountAliasEditorSpec extends AnyWordSpec {

  "normalize" should {
    "trim surrounding whitespace" in {
      AccountAliasEditor.normalize("  Travel card  ") mustBe "Travel card"
    }

    "turn a blank draft into an empty nickname" in {
      AccountAliasEditor.normalize("   ") mustBe ""
    }
  }

  "canSave" should {
    "accept a nickname at the limit" in {
      AccountAliasEditor.canSave("a" * SetAccountAlias.MaxAliasLength) mustBe true
    }

    "reject a nickname over the limit" in {
      AccountAliasEditor.canSave("a" * (SetAccountAlias.MaxAliasLength + 1)) mustBe false
    }

    "measure the trimmed nickname" in {
      AccountAliasEditor.canSave(s"  ${"a" * SetAccountAlias.MaxAliasLength}  ") mustBe true
    }

    "accept an empty nickname, which clears it" in {
      AccountAliasEditor.canSave("") mustBe true
    }
  }
}
