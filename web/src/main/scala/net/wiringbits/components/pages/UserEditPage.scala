package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.AppContext
import net.wiringbits.api.models.environmentconfig.GetEnvironmentConfig
import net.wiringbits.common.models.{McpApiToken, TelegramLinkToken}
import net.wiringbits.components.widgets.{EditPasswordForm, UserInfo}
import net.wiringbits.core.{I18nHooks, SafeHooks}
import net.wiringbits.models.UserMenuOption.{EditPassword, EditSummary}
import net.wiringbits.models.{User, UserMenuOption}
import net.wiringbits.ui.forms.SubmitHooks
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.components.core.ErrorLabel
import net.wiringbits.webapp.utils.slinkyUtils.components.core.widgets.{CircularLoader, Container, Title}
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.useHistory
import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import slinky.core.facade.{Fragment, Hooks}
import slinky.core.{FunctionalComponent, KeyAddingStage, SyntheticEvent}
import slinky.web.html.{a, form, href, onSubmit, target}

import scala.util.{Failure, Success}

object UserEditPage {
  def apply(ctx: AppContext, user: User): KeyAddingStage =
    component(Props(ctx = ctx, user = user))

  case class Props(ctx: AppContext, user: User)

  enum TokenAction[+T] {
    case Idle
    case Working
    case Loaded(token: T)
    case Failed(message: String)

    def isWorking: Boolean = this == Working

    def tokenOpt: Option[T] = this match {
      case Loaded(token) => Some(token)
      case _ => None
    }

    def errorOpt: Option[String] = this match {
      case Failed(message) => Some(message)
      case _ => None
    }
  }

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    val texts = I18nHooks.useMessages(props.ctx.$lang)
    val (menuOption, setMenuOption) = Hooks.useState[UserMenuOption](UserMenuOption.EditSummary)
    val (telegramAction, setTelegramAction) = Hooks.useState[TokenAction[TelegramLinkToken]](TokenAction.Idle)
    val (mcpAction, setMcpAction) = Hooks.useState[TokenAction[McpApiToken]](TokenAction.Idle)
    val (envConfig, setEnvConfig) = Hooks.useState[Option[GetEnvironmentConfig.Response]](None)
    val (logoutDialogOpen, setLogoutDialogOpen) = Hooks.useState(false)
    val logoutSubmit = SubmitHooks.useSubmit()
    val history = useHistory()

    val mounted = Hooks.useRef(true)
    SafeHooks.useEffect()(() => () => { mounted.current = false })

    SafeHooks.useEffect()(() => {
      props.ctx.api.client.getEnvironmentConfig.onComplete {
        case Success(res) => if (mounted.current) setEnvConfig(Some(res))
        case Failure(_) => ()
      }
    })

    def handleLinkTelegram(): Unit = {
      setTelegramAction(TokenAction.Working)
      props.ctx.api.client.createTelegramLinkToken
        .onComplete {
          case Success(res) =>
            if (mounted.current) setTelegramAction(TokenAction.Loaded(res.token))
          case Failure(ex) =>
            if (mounted.current) setTelegramAction(TokenAction.Failed(ex.getMessage))
        }
    }

    def handleGenerateMcpToken(): Unit = {
      setMcpAction(TokenAction.Working)
      props.ctx.api.client.createMcpToken
        .onComplete {
          case Success(res) =>
            if (mounted.current) setMcpAction(TokenAction.Loaded(res.token))
          case Failure(ex) =>
            if (mounted.current) setMcpAction(TokenAction.Failed(ex.getMessage))
        }
    }

    def handleLogoutEverywhere(e: SyntheticEvent[?, dom.Event]): Unit = {
      e.preventDefault()
      if (!logoutSubmit.isSubmitting) {
        logoutSubmit.begin()
        props.ctx.api.client.logoutEverywhere
          .onComplete {
            case Success(_) =>
              props.ctx.loggedOut()
              history.push("/")
            case Failure(ex) =>
              if (mounted.current) {
                logoutSubmit.fail(ex.getMessage)
                setLogoutDialogOpen(false)
              }
          }
      }
    }

    val header = Container(
      margin = Container.EdgeInsets.bottom(16),
      child = Title(texts.user)
    )

    val tabs = mui.CardContent()(
      mui
        .Tabs()(
          UserMenuOption.values.toSeq.map(x =>
            mui.Tab.normal().label(texts.userMenuOption(x)).withKey(x.toString).build
          )
        )
        .value(UserMenuOption.values.indexOf(menuOption))
        .onChange((_, index) => setMenuOption(UserMenuOption.values(index.toString.toInt)))
    )

    val body = mui.CardContent()(
      menuOption match {
        case EditSummary => UserInfo(props.ctx, props.user)
        case EditPassword => EditPasswordForm(props.ctx, props.user)
      }
    )

    val telegramSection = Container(
      margin = Container.EdgeInsets.top(24),
      child = mui.Card()(
        mui.CardContent()(
          mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "12px" })(
            mui.Typography("Telegram Bot").variant("h6"),
            mui.Button
              .normal()("Link Telegram Account")
              .variant("contained")
              .disabled(telegramAction.isWorking)
              .onClick(_ => handleLinkTelegram()),
            if (telegramAction.isWorking) CircularLoader()
            else Fragment(),
            telegramAction.tokenOpt match {
              case Some(token) =>
                val linkCommand = s"/link ${token.value}"
                Fragment(
                  envConfig.flatMap(_.telegramBotName) match {
                    case Some(botName) =>
                      Fragment(
                        a(href := s"https://t.me/$botName", target := "_blank")(
                          mui.Typography(s"Open @$botName on Telegram").variant("body2")
                        )
                      )
                    case None => Fragment()
                  },
                  mui.TextField
                    .outlined()
                    .label("Command to send in Telegram")
                    .value(linkCommand)
                    .fullWidth(true)
                    .size("small")
                    .disabled(true),
                  mui.TextField
                    .outlined()
                    .label("Telegram Link Token")
                    .value(token.value)
                    .fullWidth(true)
                    .size("small")
                    .disabled(true),
                  mui
                    .Typography("This token expires soon. Use it to link your Telegram account.")
                    .variant("caption"),
                  mui.Button
                    .normal()("Regenerate")
                    .variant("outlined")
                    .disabled(telegramAction.isWorking)
                    .onClick(_ => handleLinkTelegram())
                )
              case None => Fragment()
            },
            telegramAction.errorOpt match {
              case Some(error) => ErrorLabel(error)
              case None => Fragment()
            }
          )
        )
      )
    )

    val mcpSection = Container(
      margin = Container.EdgeInsets.top(24),
      child = mui.Card()(
        mui.CardContent()(
          mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "12px" })(
            mui.Typography("MCP API Token").variant("h6"),
            mui
              .Typography("Lets Claude query your expense data. The token is shown only once — copy it now.")
              .variant("body2"),
            mui.Button
              .normal()("Generate MCP API Token")
              .variant("contained")
              .disabled(mcpAction.isWorking)
              .onClick(_ => handleGenerateMcpToken()),
            if (mcpAction.isWorking) CircularLoader()
            else Fragment(),
            mcpAction.tokenOpt match {
              case Some(token) =>
                Fragment(
                  mui.TextField
                    .outlined()
                    .label("MCP Server URL")
                    .value(s"${props.ctx.api.serverUrl}/mcp")
                    .fullWidth(true)
                    .size("small")
                    .disabled(true),
                  mui.TextField
                    .outlined()
                    .label("MCP API Token")
                    .value(token.value)
                    .fullWidth(true)
                    .size("small")
                    .disabled(true),
                  mui
                    .Typography("Use this as an Authorization: Bearer header. It will not be shown again.")
                    .variant("caption"),
                  mui.Button
                    .normal()("Regenerate")
                    .variant("outlined")
                    .disabled(mcpAction.isWorking)
                    .onClick(_ => handleGenerateMcpToken())
                )
              case None => Fragment()
            },
            mcpAction.errorOpt match {
              case Some(error) => ErrorLabel(error)
              case None => Fragment()
            }
          )
        )
      )
    )

    val sessionsSection = Container(
      margin = Container.EdgeInsets.top(24),
      child = mui.Card()(
        mui.CardContent()(
          mui.Box.sx(new CSSPropertiesUtils { display = "flex"; flexDirection = "column"; gap = "12px" })(
            mui.Typography("Sessions").variant("h6"),
            mui
              .Typography("Sign out of every device and browser where you are logged in, including this one.")
              .variant("body2"),
            mui.Button
              .normal()("Log out of all devices")
              .variant("outlined")
              .color("error")
              .disabled(logoutSubmit.isSubmitting)
              .onClick(_ => setLogoutDialogOpen(true)),
            logoutSubmit.error match {
              case Some(error) => ErrorLabel(error)
              case None => Fragment()
            }
          )
        )
      )
    )

    val logoutEverywhereDialog =
      mui
        .Dialog(logoutDialogOpen)(
          form(onSubmit := (handleLogoutEverywhere(_)))(
            mui.DialogTitle("Log out of all devices?"),
            mui.DialogContent(
              mui.DialogContentText("All your sessions will end, including this one. You will need to sign in again.")
            ),
            mui.DialogActions(
              mui.Button
                .normal()("Cancel")
                .disabled(logoutSubmit.isSubmitting)
                .onClick(_ => setLogoutDialogOpen(false)),
              mui.Button
                .normal()("Log out everywhere")
                .color("error")
                .`type`("submit")
                .disabled(logoutSubmit.isSubmitting)
            )
          )
        )
        .onClose((_, _) => if (!logoutSubmit.isSubmitting) setLogoutDialogOpen(false))

    Fragment(
      header,
      mui.Paper()(
        mui.Card()(
          tabs,
          body
        )
      ),
      telegramSection,
      mcpSection,
      sessionsSection,
      logoutEverywhereDialog
    )
  }

}
