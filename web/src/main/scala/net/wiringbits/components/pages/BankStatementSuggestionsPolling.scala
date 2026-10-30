package net.wiringbits.components.pages

import net.wiringbits.api.ApiClient
import net.wiringbits.api.models.bankstatements.GetBankStatement
import org.scalajs.dom
import slinky.core.facade.{Hooks, SetStateHookCallback}

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.util.{Failure, Success}

/** Polls a statement's AI suggestions until they resolve, and regenerates them on request. */
object BankStatementSuggestionsPolling {
  import BankStatementReviewLogic.*

  // Statements confirmed before AI suggestions existed stay NULL forever, so polling is capped.
  private val SuggestionsPollMaxAttempts = 20
  private val SuggestionsPollIntervalMs = 3000

  final case class State(polling: Boolean, regenerateDisabled: Boolean, regenerate: () => Unit)

  /** A hook: call it unconditionally from the component body. */
  def useSuggestionsPolling(
      client: ApiClient,
      statement: GetBankStatement.Response,
      setStatement: SetStateHookCallback[GetBankStatement.Response],
      setError: SetStateHookCallback[String],
      showToast: String => Unit
  )(implicit ec: ExecutionContext): State = {
    val stmtId = statement.id
    val (suggestionsPollAttempts, setSuggestionsPollAttempts) = Hooks.useState(0)
    val (regeneratingSuggestions, setRegeneratingSuggestions) = Hooks.useState(false)
    val regenerateSuggestionsDisabled = regeneratingSuggestions ||
      !canRegenerateSuggestions(statement.aiSuggestions, suggestionsPollAttempts >= SuggestionsPollMaxAttempts)
    val pollingSuggestions =
      suggestionsPending(statement.aiSuggestions) && suggestionsPollAttempts < SuggestionsPollMaxAttempts
    val suggestionsPollKey = s"$stmtId:$pollingSuggestions"
    Hooks.useEffect(
      () => {
        if (pollingSuggestions) {
          val timer = dom.window.setInterval(
            () => {
              setSuggestionsPollAttempts(_ + 1)
              client.getBankStatement(stmtId).onComplete {
                case Success(full) => setStatement(cur => cur.copy(aiSuggestions = full.aiSuggestions))
                case Failure(_) => ()
              }
            },
            SuggestionsPollIntervalMs
          )
          () => { dom.window.clearInterval(timer); () }
        } else () => ()
      },
      js.Array(suggestionsPollKey)
    )

    // Setting Pending and resetting the attempts re-enables the poll effect above
    def handleRegenerateSuggestions(): Unit = {
      setRegeneratingSuggestions(true)
      client.regenerateBankStatementAiSuggestions(stmtId).onComplete {
        case Success(resp) =>
          setError("")
          setStatement(cur => cur.copy(aiSuggestions = Some(resp.aiSuggestions)))
          setSuggestionsPollAttempts(0)
          setRegeneratingSuggestions(false)
          showToast("Regenerating AI suggestions…")
        case Failure(ex) =>
          setError(ex.getMessage)
          setRegeneratingSuggestions(false)
      }
    }

    State(
      polling = pollingSuggestions,
      regenerateDisabled = regenerateSuggestionsDisabled,
      regenerate = () => handleRegenerateSuggestions()
    )
  }
}
