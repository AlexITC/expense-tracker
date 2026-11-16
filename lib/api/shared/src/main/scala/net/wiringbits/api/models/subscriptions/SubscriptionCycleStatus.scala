package net.wiringbits.api.models.subscriptions

import play.api.libs.json.*
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

/** The reconciliation outcome of one expected charge date. Each variant carries exactly its own data.
  *
  *   - Matched: one charge paid the cycle, `late` when it happened after the due date.
  *   - Retried: several charges were attempted for the cycle, ordered by date.
  *   - Missing: nothing was found and the tolerance window has closed.
  *   - Upcoming: nothing was found yet and the tolerance window is still open.
  *
  * The JSON form is a tagged object: `{ "kind": "matched", "charge": {...}, "late": false }`, `{ "kind": "retried",
  * "attempts": [...] }`, `{ "kind": "missing" }` or `{ "kind": "upcoming" }`.
  */
enum SubscriptionCycleStatus {
  case Matched(charge: SubscriptionCharge, late: Boolean)
  case Retried(attempts: List[SubscriptionCharge])
  case Missing
  case Upcoming
}

object SubscriptionCycleStatus {
  private val MatchedKind = "matched"
  private val RetriedKind = "retried"
  private val MissingKind = "missing"
  private val UpcomingKind = "upcoming"

  implicit val format: Format[SubscriptionCycleStatus] = Format(
    Reads { js =>
      (js \ "kind").validate[String].flatMap {
        case MatchedKind =>
          for {
            charge <- (js \ "charge").validate[SubscriptionCharge]
            late <- (js \ "late").validate[Boolean]
          } yield Matched(charge = charge, late = late)
        case RetriedKind => (js \ "attempts").validate[List[SubscriptionCharge]].map(Retried(_))
        case MissingKind => JsSuccess(Missing)
        case UpcomingKind => JsSuccess(Upcoming)
        case other => JsError(s"Unknown SubscriptionCycleStatus: $other")
      }
    },
    Writes {
      case Matched(charge, late) => Json.obj("kind" -> MatchedKind, "charge" -> charge, "late" -> late)
      case Retried(attempts) => Json.obj("kind" -> RetriedKind, "attempts" -> attempts)
      case Missing => Json.obj("kind" -> MissingKind)
      case Upcoming => Json.obj("kind" -> UpcomingKind)
    }
  )

  implicit val schema: Schema[SubscriptionCycleStatus] = Schema.derived[SubscriptionCycleStatus]
}
