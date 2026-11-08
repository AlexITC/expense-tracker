package net.wiringbits.components.widgets

import com.olvind.mui.muiIconsMaterial.components as muiIcons
import com.olvind.mui.muiMaterial.components as mui
import com.olvind.mui.muiMaterial.mod.PropTypes.Color
import net.wiringbits.AppContext
import net.wiringbits.core.{I18nHooks, ReactiveHooks}
import net.wiringbits.models.AuthState
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.components.core.widgets.{Container, NavLinkButton, Subtitle, Title}
import net.wiringbits.webapp.utils.slinkyUtils.core.MediaQueryHooks
import slinky.core.facade.{Fragment, Hooks, ReactElement}
import slinky.core.{FunctionalComponent, KeyAddingStage}

object AppBar {
  def apply(ctx: AppContext): KeyAddingStage =
    component(Props(ctx))

  case class Props(ctx: AppContext)

  private val appbarStyling = new CSSPropertiesUtils {
    color = "#FFF"
  }

  private val toolBarStyling = new CSSPropertiesUtils {
    display = "flex"
    alignItems = "center"
    justifyContent = "space-between"
  }

  private val toolbarMobileStyling = new CSSPropertiesUtils {
    display = "flex"
    alignItems = "center"
  }

  private val menuStyling = new CSSPropertiesUtils {
    display = "flex"
  }

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    val texts = I18nHooks.useMessages(props.ctx.$lang)
    val auth = ReactiveHooks.useDistinctValue(props.ctx.$auth)
    val isMobileOrTablet = MediaQueryHooks.useIsMobileOrTablet()
    val (visibleDrawer, setVisibleDrawer) = Hooks.useState(false)

    def onButtonClick(): Unit = {
      if (visibleDrawer) {
        setVisibleDrawer(false)
      }
    }

    val menu = auth match {
      case AuthState.Authenticated(_) =>
        Fragment(
          NavLinkButton(path = "/", text = texts.home, onButtonClick),
          NavLinkButton(path = "/dashboard", text = texts.dashboard, onButtonClick),
          NavLinkButton(path = "/categories", text = texts.categories, onButtonClick),
          NavLinkButton(path = "/merchants", text = texts.merchants, onButtonClick),
          NavLinkButton(path = "/merchant-review", text = texts.merchantReview, onButtonClick),
          NavLinkButton(path = "/import-rules", text = texts.importRules, onButtonClick),
          NavLinkButton(path = "/expenses", text = texts.expenses, onButtonClick),
          NavLinkButton(path = "/refunds", text = texts.refunds, onButtonClick),
          NavLinkButton(path = "/receipts", text = texts.receipts, onButtonClick),
          NavLinkButton(path = "/bank-statements", text = texts.bankStatements, onButtonClick),
          NavLinkButton(path = "/savings", text = texts.savings, onButtonClick),
          NavLinkButton(path = "/about", text = texts.about, onButtonClick),
          NavLinkButton(path = "/me", text = texts.profile, onButtonClick),
          NavLinkButton(path = "/signout", text = texts.signOut, onButtonClick)
        )

      case AuthState.Unauthenticated =>
        Fragment(
          NavLinkButton(path = "/", text = texts.home, onButtonClick),
          NavLinkButton(path = "/about", text = texts.about, onButtonClick),
          NavLinkButton(path = "/signup", text = texts.signUp, onButtonClick),
          NavLinkButton(path = "/signin", text = texts.signIn, onButtonClick)
        )
    }

    if (isMobileOrTablet) {
      val drawerContent = Container(
        minWidth = Some("256px"),
        flex = Some(1),
        margin = Container.EdgeInsets.bottom(32),
        alignItems = Container.Alignment.flexEnd,
        justifyContent = Container.Alignment.spaceBetween,
        child = Fragment(
          mui.AppBar
            .sx(appbarStyling)
            .position("relative")(
              mui.Toolbar
                .sx(toolbarMobileStyling)(
                  Subtitle(texts.appName)
                )
            ),
          Container(
            alignItems = Container.Alignment.flexEnd,
            justifyContent = Container.Alignment.spaceBetween,
            child = menu
          )
        )
      )

      val drawer = mui
        .SwipeableDrawer(
          onOpen = _ => setVisibleDrawer(true),
          onClose = _ => setVisibleDrawer(false)
        )(drawerContent)
        .open(visibleDrawer)

      val toolbar = mui.Toolbar.sx(toolbarMobileStyling)(
        mui.IconButton
          .normal()(muiIcons.Menu())
          .color(Color.inherit)
          .onClick(_ => setVisibleDrawer(true)),
        Subtitle(texts.appName)
      )

      mui.AppBar
        .sx(appbarStyling)
        .position("relative")(toolbar, drawer)
    } else {
      mui.AppBar
        .sx(appbarStyling)
        .position("relative")(
          mui.Toolbar.sx(toolBarStyling)(
            Title(texts.appName),
            mui.Box.sx(menuStyling)(menu)
          )
        )
    }
  }
}
