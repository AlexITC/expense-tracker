package net.wiringbits.components.pages

import net.wiringbits.api.models.expenses.CreateExpense
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.common.ExpenseSplits

import java.util.UUID

/** Builds the category splits a create form sends, shared by the expenses and receipts pages. */
object ExpenseFormSplits {
  def splitsError(amount: BigDecimal, splits: List[BigDecimal]): Option[String] =
    Option.when(!ExpenseSplits.isFullyAllocated(amount, splits))(ExpenseSplits.NotFullyAllocatedError)

  def merchantDefaultSplits(
      merchants: List[GetMerchants.Response.Merchant],
      merchantId: Option[UUID],
      amount: BigDecimal
  ): List[CreateExpense.Split] =
    merchantId
      .flatMap(id => merchants.find(_.id == id))
      .flatMap(_.categoryId)
      .map(catId => List(CreateExpense.Split(catId, amount)))
      .getOrElse(Nil)

  /** The splits a create form sends: rows with no category are dropped, then the explicit splits are sent, else the
    * merchant's default category for the full amount. Fails when they don't cover the amount exactly.
    */
  def createSplits(
      amount: BigDecimal,
      splits: List[(Option[UUID], BigDecimal)],
      merchants: List[GetMerchants.Response.Merchant],
      merchantId: Option[UUID]
  ): Either[String, List[CreateExpense.Split]] = {
    val explicit = splits.collect { case (Some(catId), amt) => CreateExpense.Split(catId, amt) }
    val result = if (explicit.nonEmpty) explicit else merchantDefaultSplits(merchants, merchantId, amount)
    splitsError(amount, result.map(_.amount)).toLeft(result)
  }
}
