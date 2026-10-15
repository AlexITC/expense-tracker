package net.wiringbits.webapp.utils.slinkyUtils.compat

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import scala.scalajs.js

class ThemeCompatSpec extends AnyWordSpec {

  private val sampleColors = ThemeCompat.ThemeColors(
    bg = "#FBFAF8",
    paper = "#FFFFFF",
    border = "#ECEAE4",
    text = "#14181A",
    text2 = "#5A6166",
    hint = "#9A958B",
    brand = "#0E6B4B",
    brandDark = "#0A5239",
    brandOn = "#FFFFFF",
    tint = "#EEF4F0",
    error = "#C0392B",
    warmBg = "#F1EEE7",
    surface = "#F5F5F5",
    surface2 = "#E0E0E0",
    link = "#1565c0",
    successText = "#2e7d32",
    warningText = "#B45309",
    badgeReviewBg = "#FFF3E0",
    badgeReviewText = "#E65100",
    badgeInfoBg = "#E3F2FD",
    badgeErrorBg = "#FFEBEE"
  )

  "themeOptions (light)" should {
    val dyn = ThemeCompat.themeOptions(sampleColors).asInstanceOf[js.Dynamic]

    "omit palette.mode" in {
      js.isUndefined(dyn.palette.mode) must be(true)
    }

    "omit MuiPaper component override" in {
      js.isUndefined(dyn.components.MuiPaper) must be(true)
    }

    "thread --lc-bg from input color" in {
      dyn.components.MuiCssBaseline.styleOverrides.body
        .selectDynamic("--lc-bg")
        .asInstanceOf[String] must be("#FBFAF8")
    }

    "set palette.primary.main from brand color" in {
      dyn.palette.primary.main.asInstanceOf[String] must be("#0E6B4B")
    }
  }

  "themeOptions (dark)" should {
    val dyn = ThemeCompat.themeOptions(sampleColors, ThemeCompat.ColorMode.Dark).asInstanceOf[js.Dynamic]

    "set palette.mode to dark" in {
      dyn.palette.mode.asInstanceOf[String] must be("dark")
    }

    "include MuiPaper backgroundImage override" in {
      dyn.components.MuiPaper.styleOverrides.root.backgroundImage.asInstanceOf[String] must be("none")
    }

    "still thread --lc-bg from input color" in {
      dyn.components.MuiCssBaseline.styleOverrides.body
        .selectDynamic("--lc-bg")
        .asInstanceOf[String] must be("#FBFAF8")
    }
  }
}
