package net.wiringbits.components.pages

import net.wiringbits.api.ApiClient
import net.wiringbits.api.models.bankstatements.AiSuggestions
import net.wiringbits.core.SafeHooks
import org.scalajs.dom
import slinky.core.facade.{Hooks, SetStateHookCallback}

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/** Polls a statement's AI suggestions until they resolve, and regenerates them on request. */
object BankStatementSuggestionsPolling {
  import BankStatementReviewLogic.*

  // Statements confirmed before AI suggestions existed stay NULL forever, so polling is capped.
  private val SuggestionsPollMaxAttempts = 20
  private val SuggestionsPollIntervalMs = 3000

  final case class State(polling: Boolean, regenerateDisabled: Boolean, regenerate: () => Unit)

  /** A hook: call it unconditionally from the component body. Nothing is polled until `enabled`, so the attempts are
    * not spent before the statement (and its real suggestions) has loaded.
    */
  def useSuggestionsPolling(
      client: ApiClient,
      stmtId: UUID,
      enabled: Boolean,
      aiSuggestions: Option[AiSuggestions],
      setAiSuggestions: Option[AiSuggestions] => Unit,
      setError: SetStateHookCallback[String],
      showToast: String => Unit
  )(implicit ec: ExecutionContext): State = {
    val (suggestionsPollAttempts, setSuggestionsPollAttempts) = Hooks.useState(0)
    val (regeneratingSuggestions, setRegeneratingSuggestions) = Hooks.useState(false)
    val regenerateSuggestionsDisabled = regeneratingSuggestions ||
      !canRegenerateSuggestions(aiSuggestions, suggestionsPollAttempts >= SuggestionsPollMaxAttempts)
    val pollingSuggestions =
      enabled && suggestionsPending(aiSuggestions) && suggestionsPollAttempts < SuggestionsPollMaxAttempts
    val suggestionsPollKey = s"$stmtId:$pollingSuggestions"
    SafeHooks.useEffect(suggestionsPollKey)(() => {
      if (pollingSuggestions) {
        val timer = dom.window.setInterval(
          () => {
            setSuggestionsPollAttempts(_ + 1)
            client.getBankStatement(stmtId).onComplete {
              case Success(full) => setAiSuggestions(full.aiSuggestions)
              case Failure(_) => ()
            }
          },
          SuggestionsPollIntervalMs
        )
        () => { dom.window.clearInterval(timer); () }
      } else () => ()
    })

    // Setting Pending and resetting the attempts re-enables the poll effect above
    def handleRegenerateSuggestions(): Unit = {
      setRegeneratingSuggestions(true)
      client.regenerateBankStatementAiSuggestions(stmtId).onComplete {
        case Success(resp) =>
          setError("")
          setAiSuggestions(Some(resp.aiSuggestions))
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
