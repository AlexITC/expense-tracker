package net.wiringbits.services

import net.wiringbits.core.I18nLang
import org.scalajs.dom

class StorageService {
  def saveLang(lang: I18nLang): Unit = save(key = "lang", value = lang.toString)
  def findLang(): Option[I18nLang] = find("lang").flatMap(I18nLang.from)

  def saveDarkMode(isDark: Boolean): Unit = save(key = "darkMode", value = isDark.toString)
  def findDarkMode(): Boolean = find("darkMode").contains("true")

  private def save(key: String, value: String): Unit = {
    dom.window.localStorage.setItem(key = key, data = value)
  }

  private def find(key: String): Option[String] = {
    Option(dom.window.localStorage.getItem(key))
      .filter(_.nonEmpty)
  }
}
