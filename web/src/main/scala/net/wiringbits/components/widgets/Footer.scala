package net.wiringbits.components.widgets

import com.olvind.mui.csstype.mod.Property.FlexDirection
import com.olvind.mui.muiMaterial.components as mui
import com.olvind.mui.muiMaterial.mod.PropTypes.Color
import net.wiringbits.AppContext
import net.wiringbits.core.I18nHooks
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.components.core.widgets.Container
import net.wiringbits.webapp.utils.slinkyUtils.core.MediaQueryHooks
import slinky.core.facade.Fragment
import slinky.core.{FunctionalComponent, KeyAddingStage}

object Footer {
  def apply(ctx: AppContext): KeyAddingStage =
    component(Props(ctx = ctx))

  case class Props(ctx: AppContext)

  private val styling = new CSSPropertiesUtils {
    color = "#FFF"
    backgroundColor = "#222"
    borderRadius = 0
  }

  private val margin = 16

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    val texts = I18nHooks.useMessages(props.ctx.$lang)
    val isMobileOrTablet = MediaQueryHooks.useIsMobileOrTablet()

    val appName = Container(
      margin = Container.EdgeInsets.bottom(margin),
      child = mui.Typography(texts.appName).variant("h4").color(Color.inherit)
    )
    val appDescription =
      mui.Typography(texts.description).variant("body2").color(Color.inherit)

    def title(text: String) =
      Container(
        margin = Container.EdgeInsets.bottom(margin),
        child = mui.Typography(text).variant("h5").color(Color.inherit)
      )

    def subtitle(text: String) =
      mui.Typography(text).variant("subtitle2").color(Color.inherit)

    def link(text: String, url: String) =
      mui
        .Link(
          mui.Typography(text).variant("body2").color(Color.inherit)
        )
        .href(url)
        .color(Color.inherit)

    val copyright = Container(
      margin = Container.EdgeInsets.vertical(margin),
      alignItems = Container.Alignment.center,
      child = mui.Typography(texts.appNameCopyright).color(Color.inherit)
    )

    val projects = Container(
      flex = Some(1),
      child = Fragment(
        title("Projects"),
        link(text = "CollabUML", url = "https://collabuml.com"),
        link(text = "The Stakenet Block Explorer", url = "https://xsnexplorer.io/"),
        link(text = "The Stakenet Orderbook", url = "https://orderbook.stakenet.io/XSN_BTC"),
        link(text = "Pull Request Attention", url = "https://prattention.com"),
        link(text = "CazaDescuentos", url = "https://cazadescuentos.net"),
        link(text = "safer.chat", url = "https://safer.chat"),
        link(text = "Crypto Coin Alerts", url = "https://github.com/AlexITC/crypto-coin-alerts")
      )
    )

    val contact = Container(
      flex = Some(1),
      child = Fragment(
        title(texts.contact),
        Container(
          child = Fragment(
            subtitle(texts.contact),
            link(text = props.ctx.contactEmail.string, url = s"mailto:${props.ctx.contactEmail.string}")
          )
        ),
        Container(
          margin = Container.EdgeInsets.top(margin / 2),
          child = Fragment(
            subtitle(texts.phone),
            mui.Typography(props.ctx.contactPhone).variant("body2").color(Color.inherit)
          )
        )
      )
    )

    val body = if (isMobileOrTablet) {
      Container(
        padding = Container.EdgeInsets.all(margin),
        child = Fragment(
          appName,
          appDescription,
          Container(margin = Container.EdgeInsets.top(margin), child = projects),
          Container(margin = Container.EdgeInsets.top(margin), child = contact)
        )
      )
    } else {

      Container(
        padding = Container.EdgeInsets.all(margin),
        flexDirection = FlexDirection.row,
        child = Fragment(
          Container(
            margin = Container.EdgeInsets.right(margin / 2),
            flex = Some(1),
            child = Fragment(appName, appDescription)
          ),
          Container(
            flex = Some(1),
            margin = Container.EdgeInsets.left(margin / 2),
            flexDirection = FlexDirection.row,
            child = Fragment(
              projects,
              contact
            )
          )
        )
      )
    }

    mui
      .Paper()(
        Fragment(
          body,
          copyright
        )
      )
      .sx(styling)
  }
}
