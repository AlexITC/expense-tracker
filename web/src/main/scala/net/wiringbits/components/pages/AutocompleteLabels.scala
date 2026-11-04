package net.wiringbits.components.pages

import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.GetMerchants

import scala.scalajs.js

/** getOptionLabel helpers shared by the merchant/category autocompletes across pages. */
object AutocompleteLabels {
  // Extracted so they can be unit-tested directly (jsdom doesn't open MUI dropdowns).
  // Ascribing opt as Any widens from js.Any: both String and Merchant are subtypes of Any,
  // so the compiler accepts the match cases. Do NOT use js.Dynamic.name: Scala.js case class
  // fields are name-mangled in JS output, so .name returns undefined.
  private[pages] def merchantOptionLabel(opt: js.Any): String =
    (opt: Any) match {
      case s: String => s
      case m: GetMerchants.Response.Merchant => m.name
      case _ => ""
    }

  private[pages] def categoryOptionLabel(opt: js.Any): String =
    (opt: Any) match {
      case s: String => s
      case c: GetCategories.Response.Category => c.name
      case _ => ""
    }
}
