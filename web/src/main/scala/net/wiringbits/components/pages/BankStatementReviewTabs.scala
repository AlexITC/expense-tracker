package net.wiringbits.components.pages

import com.olvind.mui.muiMaterial.components as mui
import net.wiringbits.webapp.utils.slinkyUtils.Utils.CSSPropertiesUtils
import net.wiringbits.webapp.utils.slinkyUtils.compat.MuiCompat
import slinky.core.{FunctionalComponent, KeyAddingStage}

/** The row-filter tabs of the review page, each labelled with its row count. */
object BankStatementReviewTabs {
  def apply(props: Props): KeyAddingStage = component(props)

  case class Props(
      rowFilter: String,
      countReady: Int,
      countUnprocessed: Int,
      countIgnored: Int,
      countLinked: Int,
      countAll: Int,
      onChange: String => Unit
  )

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    mui.Tabs
      .value(props.rowFilter)
      .onChange((_, v) => props.onChange(MuiCompat.tabValue(v)))
      .sx(new CSSPropertiesUtils { marginTop = "8px"; minHeight = "42px" })(
        mui.Tab.normal().value("ready").label(s"Ready (${props.countReady})").build,
        mui.Tab.normal().value("unprocessed").label(s"Unprocessed (${props.countUnprocessed})").build,
        mui.Tab.normal().value("ignored").label(s"Ignored (${props.countIgnored})").build,
        mui.Tab.normal().value("linked").label(s"Linked (${props.countLinked})").build,
        mui.Tab.normal().value("all").label(s"All (${props.countAll})").build
      )
  }
}
