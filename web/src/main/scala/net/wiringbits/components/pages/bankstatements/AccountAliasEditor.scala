package net.wiringbits.components.pages.bankstatements

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.api.models.bankstatements.SetAccountAlias
import net.wiringbits.core.SafeHooks
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import slinky.core.facade.{Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.web.html.{div, form, onSubmit, ref, style}

import scala.concurrent.Future
import scala.scalajs.js
import scala.util.{Failure, Success}

/** Shows an account's nickname and lets the user change it. The nickname belongs to the account, so it applies to every
  * statement of it; `onSave` receives the trimmed nickname, where an empty one clears it.
  */
object AccountAliasEditor {
  def apply(props: Props): KeyAddingStage = component(props)

  case class Props(currentAlias: String, onSave: String => Future[Unit])

  enum EditState {
    case Closed
    case Editing(draft: String, error: Option[String])
    case Saving(draft: String)
  }

  /** The value sent to the server: surrounding whitespace is never part of a nickname. */
  def normalize(draft: String): String = draft.trim

  /** Whether the draft fits the server's limit; the server still validates it. */
  def canSave(draft: String): Boolean = normalize(draft).length <= SetAccountAlias.MaxAliasLength

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    val (state, setState) = Hooks.useState[EditState](EditState.Closed)
    val wrapperRef = Hooks.useRef[js.UndefOr[dom.html.Div]](js.undefined)

    val focusKey = state match {
      case EditState.Editing(_, _) => "editing"
      case EditState.Saving(_) => "saving"
      case EditState.Closed => "closed"
    }
    SafeHooks.useEffect(focusKey)(() =>
      if (focusKey == "editing")
        wrapperRef.current.toOption
          .flatMap(wrapper => Option(wrapper.querySelector("input")))
          .foreach(_.asInstanceOf[dom.html.Input].focus())
    )

    def save(draft: String): Unit =
      if (canSave(draft)) {
        setState(EditState.Saving(draft))
        props.onSave(normalize(draft)).onComplete {
          case Success(_) => setState(EditState.Closed)
          case Failure(ex) => setState(EditState.Editing(draft, Some(ex.getMessage)))
        }
      }

    def editor(draft: String, error: Option[String], saving: Boolean): ReactElement = {
      val count = s"${normalize(draft).length}/${SetAccountAlias.MaxAliasLength}"
      val helper = error.getOrElse(s"Applies to every statement of this card, not just this one · $count")
      div(ref := wrapperRef, style := js.Dynamic.literal(display = "inline-flex"))(
        form(onSubmit := { e =>
          e.preventDefault()
          save(draft)
        })(
          mui.Box.sx(new CSSPropertiesUtils { display = "flex"; alignItems = "flex-start"; gap = "8px" })(
            mui.TextField
              .outlined()
              .label("Nickname")
              .size("small")
              .value(draft)
              .disabled(saving)
              .error(error.nonEmpty || !canSave(draft))
              .helperText(helper)
              .onChange(e => setState(EditState.Editing(e.target.asInstanceOf[dom.HTMLInputElement].value, None)))
              .sx(new CSSPropertiesUtils { minWidth = "260px" }),
            mui.Button
              .normal()(if (saving) "Saving…" else "Save")
              .size("small")
              .variant("contained")
              .`type`("submit")
              .disabled(saving || !canSave(draft)),
            mui.Button
              .normal()("Cancel")
              .size("small")
              .variant("outlined")
              .disabled(saving)
              .onClick(_ => setState(EditState.Closed))
          )
        )
      )
    }

    state match {
      case EditState.Closed =>
        mui.Box.sx(new CSSPropertiesUtils { display = "inline-flex"; alignItems = "center"; gap = "8px" })(
          mui
            .Typography(if (props.currentAlias.isEmpty) "No nickname" else s"Nickname: ${props.currentAlias}")
            .sx(new CSSPropertiesUtils { fontSize = "13px"; color = "var(--lc-text-2)" }),
          mui.Button
            .normal()("Edit nickname")
            .size("small")
            .variant("text")
            .onClick(_ => setState(EditState.Editing(props.currentAlias, None)))
        )
      case EditState.Editing(draft, error) => editor(draft, error, saving = false)
      case EditState.Saving(draft) => editor(draft, None, saving = true)
    }
  }
}
