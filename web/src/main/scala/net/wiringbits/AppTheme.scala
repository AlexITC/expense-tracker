package net.wiringbits

import com.olvind.mui.muiMaterial.stylesCreateThemeMod.Theme
import com.olvind.mui.muiMaterial.stylesMod.createTheme
import net.wiringbits.webapp.utils.slinkyUtils.compat.ThemeCompat

object AppTheme {
  val light: Theme = createTheme(
    ThemeCompat.themeOptions(
      ThemeCompat.ThemeColors(
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
    )
  )

  val dark: Theme = createTheme(
    ThemeCompat.themeOptions(
      ThemeCompat.ThemeColors(
        bg = "#0B100F",
        paper = "#141B19",
        border = "#222D2A",
        text = "#E9EEEC",
        text2 = "#93A29D",
        hint = "#617069",
        brand = "#2FCFA6",
        brandDark = "#23A584",
        brandOn = "#06140F",
        tint = "#1A2A24",
        error = "#F87171",
        warmBg = "#181610",
        surface = "#1A2220",
        surface2 = "#2A3530",
        link = "#64B5F6",
        successText = "#66BB6A",
        warningText = "#FCD34D",
        badgeReviewBg = "#2A1500",
        badgeReviewText = "#FFAA40",
        badgeInfoBg = "#0D2040",
        badgeErrorBg = "#2A0808"
      ),
      mode = ThemeCompat.ColorMode.Dark
    )
  )

  // Keep backward-compat alias used by App.scala until we swap it
  val value: Theme = light
}
