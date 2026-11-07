package net.wiringbits.api.models.bankstatements

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.*

class ConfirmWarningSpec extends AnyWordSpec with Matchers {

  "kind format" should {
    "write each value as its entry name and read it back" in {
      ConfirmWarningKind.values.foreach { kind =>
        Json.toJson[ConfirmWarningKind](kind) must be(JsString(kind.entryName))
        JsString(kind.entryName).validate[ConfirmWarningKind] must be(JsSuccess(kind))
      }
    }

    "reject an unknown string" in {
      JsString("overlap").validate[ConfirmWarningKind] mustBe a[JsError]
    }
  }

  "format" should {
    "round-trip a warning" in {
      val warning = ConfirmWarning(ConfirmWarningKind.Overlap, "overlaps")
      Json.toJson(warning).validate[ConfirmWarning] must be(JsSuccess(warning))
    }
  }
}
