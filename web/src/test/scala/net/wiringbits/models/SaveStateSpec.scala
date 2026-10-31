package net.wiringbits.models

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class SaveStateSpec extends AnyWordSpec {

  "isSaving" should {
    "be false when idle" in {
      SaveState.Idle.isSaving mustBe false
    }
    "be true while saving" in {
      SaveState.Saving.isSaving mustBe true
    }
    "be false after a failure" in {
      SaveState.Failed("boom").isSaving mustBe false
    }
  }

  "errorOpt" should {
    "be empty when idle" in {
      SaveState.Idle.errorOpt mustBe None
    }
    "be empty while saving" in {
      SaveState.Saving.errorOpt mustBe None
    }
    "return the failure message" in {
      SaveState.Failed("boom").errorOpt mustBe Some("boom")
    }
  }
}
