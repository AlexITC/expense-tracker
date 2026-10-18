package net.wiringbits.api.models.bankstatements

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.*

import java.util.UUID

class AiSuggestionsSpec extends AnyWordSpec with Matchers {

  private val expected = List(
    AiSuggestionsStatus.Pending -> "Pending",
    AiSuggestionsStatus.Done -> "Done",
    AiSuggestionsStatus.Failed -> "Failed"
  )

  "values" should {
    "contain every status" in {
      AiSuggestionsStatus.values.toSet must be(expected.map(_._1).toSet)
    }
  }

  "status format" should {
    "write each value as exactly its entry name and read it back" in {
      expected.foreach { case (status, name) =>
        Json.toJson[AiSuggestionsStatus](status) must be(JsString(name))
        JsString(name).validate[AiSuggestionsStatus] must be(JsSuccess(status))
      }
    }

    "reject an unknown string" in {
      JsString("done").validate[AiSuggestionsStatus] mustBe a[JsError]
    }
  }

  "format" should {
    "round-trip suggestions, including missing ids" in {
      val suggestions = AiSuggestions(
        status = AiSuggestionsStatus.Done,
        model = "eu.amazon.nova-lite-v1:0",
        rows = List(
          AiSuggestions.RowSuggestion(UUID.randomUUID(), Some(UUID.randomUUID()), Some(UUID.randomUUID())),
          AiSuggestions.RowSuggestion(UUID.randomUUID(), None, None)
        )
      )

      Json.toJson(suggestions).validate[AiSuggestions] must be(JsSuccess(suggestions))
    }

    "reject an unknown status" in {
      val json = Json.obj("status" -> "Unknown", "model" -> "m", "rows" -> Json.arr())

      json.validate[AiSuggestions] mustBe a[JsError]
    }
  }
}
