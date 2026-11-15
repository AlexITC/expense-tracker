package net.wiringbits.forms

import net.wiringbits.ui.forms.{ExpOptionalId, PositiveBigDecimal}
import net.wiringbits.webapp.utils.slinkyUtils.forms.FormField

import java.util.UUID

/** One category split row of an expense form: an optional category select and a required positive amount. `key` is a
  * stable row identity (not edited by the user) used for unique field names and the React key, so removing a row
  * doesn't shift the others' identities.
  */
case class ExpenseSplitRow(
    key: Int,
    category: FormField[Option[UUID]],
    amount: FormField[PositiveBigDecimal]
)

object ExpenseSplitRow {
  private def categoryField(prefix: String, key: Int, value: Option[String]): FormField[Option[UUID]] =
    new FormField(
      label = "Category",
      name = s"$prefix-split-$key-category",
      required = false,
      value = Some(ExpOptionalId.validate(value.getOrElse("")))
    )

  private def amountField(prefix: String, key: Int, value: Option[String]): FormField[PositiveBigDecimal] =
    new FormField(
      label = "Amount",
      name = s"$prefix-split-$key-amount",
      required = true,
      value = value.map(PositiveBigDecimal.validate)
    )

  def blank(prefix: String, key: Int): ExpenseSplitRow =
    ExpenseSplitRow(
      key = key,
      category = categoryField(prefix = prefix, key = key, value = None),
      amount = amountField(prefix = prefix, key = key, value = None)
    )

  def seeded(prefix: String, key: Int, categoryId: String, amount: String): ExpenseSplitRow =
    ExpenseSplitRow(
      key = key,
      category = categoryField(prefix = prefix, key = key, value = Some(categoryId)),
      amount = amountField(prefix = prefix, key = key, value = Some(amount))
    )

  def nextKey(rows: List[ExpenseSplitRow]): Int = rows.map(_.key).maxOption.fold(0)(_ + 1)
}
