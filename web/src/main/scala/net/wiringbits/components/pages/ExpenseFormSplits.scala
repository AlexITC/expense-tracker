package net.wiringbits.components.pages

import net.wiringbits.api.models.expenses.CreateExpense
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.common.ExpenseSplits

import java.util.UUID
import scala.util.Try

/** Builds the category splits a create form sends, shared by the expenses and receipts pages. */
object ExpenseFormSplits {
  def splitsError(amount: BigDecimal, splits: List[BigDecimal]): Option[String] =
    Option.when(!ExpenseSplits.isFullyAllocated(amount, splits))(ExpenseSplits.NotFullyAllocatedError)

  def parseSplits(splits: List[(String, String)]): List[CreateExpense.Split] =
    splits.flatMap { case (catId, amt) =>
      if (catId.nonEmpty && amt.nonEmpty)
        Try(CreateExpense.Split(UUID.fromString(catId), BigDecimal(amt))).toOption
      else None
    }

  def merchantDefaultSplits(
      merchants: List[GetMerchants.Response.Merchant],
      merchantIdStr: String,
      amount: BigDecimal
  ): List[CreateExpense.Split] =
    Try(UUID.fromString(merchantIdStr)).toOption
      .flatMap(id => merchants.find(_.id == id))
      .flatMap(_.categoryId)
      .map(catId => List(CreateExpense.Split(catId, amount)))
      .getOrElse(Nil)

  /** The splits the create form sends: the explicit ones, else the merchant's default category for the full amount.
    * Fails when they don't cover the amount exactly.
    */
  def createSplits(
      amount: BigDecimal,
      formSplits: List[(String, String)],
      merchants: List[GetMerchants.Response.Merchant],
      merchantIdStr: String
  ): Either[String, List[CreateExpense.Split]] = {
    val explicit = parseSplits(formSplits)
    val splits = if (explicit.nonEmpty) explicit else merchantDefaultSplits(merchants, merchantIdStr, amount)
    splitsError(amount, splits.map(_.amount)).toLeft(splits)
  }
}
