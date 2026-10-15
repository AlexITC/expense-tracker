package net.wiringbits.webapp.utils.slinkyUtils.compat

import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.useLocation

import scala.scalajs.js

object RouterCompat {
  case class Location(pathname: String, search: String)

  def useTypedLocation(): Location = {
    val loc = useLocation().asInstanceOf[js.Dynamic]
    Location(
      pathname = loc.pathname.asInstanceOf[String],
      search = loc.search.asInstanceOf[String]
    )
  }
}
