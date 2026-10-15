package net.wiringbits.webapp.utils.slinkyUtils.compat

import com.olvind.mui.muiMaterial.stylesCreateThemeMod.ThemeOptions

import scala.scalajs.js

object ThemeCompat {

  sealed trait ColorMode
  object ColorMode {
    case object Light extends ColorMode
    case object Dark extends ColorMode
  }

  case class ThemeColors(
      bg: String,
      paper: String,
      border: String,
      text: String,
      text2: String,
      hint: String,
      brand: String,
      brandDark: String,
      brandOn: String,
      tint: String,
      error: String,
      warmBg: String,
      surface: String,
      surface2: String,
      link: String,
      successText: String,
      warningText: String,
      badgeReviewBg: String,
      badgeReviewText: String,
      badgeInfoBg: String,
      badgeErrorBg: String
  )

  def themeOptions(c: ThemeColors, mode: ColorMode = ColorMode.Light): ThemeOptions = {
    val bodyStyles = js.Dynamic.literal(backgroundColor = c.bg)
    bodyStyles.updateDynamic("--lc-bg")(c.bg)
    bodyStyles.updateDynamic("--lc-paper")(c.paper)
    bodyStyles.updateDynamic("--lc-border")(c.border)
    bodyStyles.updateDynamic("--lc-text")(c.text)
    bodyStyles.updateDynamic("--lc-text-2")(c.text2)
    bodyStyles.updateDynamic("--lc-hint")(c.hint)
    bodyStyles.updateDynamic("--lc-brand")(c.brand)
    bodyStyles.updateDynamic("--lc-brand-dark")(c.brandDark)
    bodyStyles.updateDynamic("--lc-brand-on")(c.brandOn)
    bodyStyles.updateDynamic("--lc-tint")(c.tint)
    bodyStyles.updateDynamic("--lc-error")(c.error)
    bodyStyles.updateDynamic("--lc-warm-bg")(c.warmBg)
    bodyStyles.updateDynamic("--lc-surface")(c.surface)
    bodyStyles.updateDynamic("--lc-surface-2")(c.surface2)
    bodyStyles.updateDynamic("--lc-link")(c.link)
    bodyStyles.updateDynamic("--lc-success-text")(c.successText)
    bodyStyles.updateDynamic("--lc-warning-text")(c.warningText)
    bodyStyles.updateDynamic("--lc-badge-review-bg")(c.badgeReviewBg)
    bodyStyles.updateDynamic("--lc-badge-review-text")(c.badgeReviewText)
    bodyStyles.updateDynamic("--lc-badge-info-bg")(c.badgeInfoBg)
    bodyStyles.updateDynamic("--lc-badge-error-bg")(c.badgeErrorBg)

    val palette = js.Dynamic.literal(
      primary = js.Dynamic.literal(main = c.brand, dark = c.brandDark, contrastText = c.brandOn),
      error = js.Dynamic.literal(main = c.error),
      background = js.Dynamic.literal(default = c.bg, paper = c.paper),
      text = js.Dynamic.literal(primary = c.text, secondary = c.text2),
      divider = c.border
    )
    mode match {
      case ColorMode.Dark => palette.updateDynamic("mode")("dark")
      case ColorMode.Light => ()
    }

    val components = js.Dynamic.literal(
      MuiCssBaseline = js.Dynamic.literal(
        styleOverrides = js.Dynamic.literal(body = bodyStyles)
      ),
      MuiButton = js.Dynamic.literal(
        defaultProps = js.Dynamic.literal(disableElevation = true)
      )
    )
    mode match {
      case ColorMode.Dark =>
        components.updateDynamic("MuiPaper")(
          js.Dynamic.literal(
            styleOverrides = js.Dynamic.literal(
              root = js.Dynamic.literal(backgroundImage = "none")
            )
          )
        )
      case ColorMode.Light => ()
    }

    js.Dynamic
      .literal(
        palette = palette,
        shape = js.Dynamic.literal(borderRadius = 8),
        typography = js.Dynamic.literal(
          fontFamily = "\"IBM Plex Sans\", sans-serif",
          button = js.Dynamic.literal(textTransform = "none", fontWeight = 600)
        ),
        components = components
      )
      .asInstanceOf[ThemeOptions]
  }
}
