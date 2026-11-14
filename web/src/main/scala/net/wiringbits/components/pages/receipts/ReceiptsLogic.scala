package net.wiringbits.components.pages.receipts

import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.api.models.receipts.GetReceipts
import net.wiringbits.api.utils.ImportRuleMatcher

import java.util.UUID

object ReceiptsLogic {

  /** Per-item outcomes of a batch, labelled for display. */
  type BatchResults = List[(String, Either[String, Unit])]

  /** A batch upload/confirm lifecycle. Results are kept while running and after it finishes, so they stay displayed.
    * The counters are explicit (not derived from results) so clearing results mid-run keeps the progress text intact.
    */
  enum BatchState {
    case Idle
    case Running(done: Int, total: Int, failed: Int, override val results: BatchResults)
    case Done(override val results: BatchResults)

    /** Idle has none; Running and Done override this with their own field. */
    def results: BatchResults = Nil

    def isRunning: Boolean = this match {
      case Running(_, _, _, _) => true
      case _ => false
    }

    /** Count and append an item's outcome; only a running batch records anything. */
    def recorded(label: String, outcome: Either[String, Unit]): BatchState = this match {
      case Running(done, total, failed, rs) =>
        Running(
          done = done + 1,
          total = total,
          failed = if (outcome.isLeft) failed + 1 else failed,
          rs :+ (label -> outcome)
        )
      case other => other
    }

    def finished: BatchState = this match {
      case Running(_, _, _, rs) => Done(rs)
      case other => other
    }

    /** Drop displayed results; a running batch keeps its counters. */
    def cleared: BatchState = this match {
      case r: Running => r.copy(results = Nil)
      case _ => Idle
    }
  }

  /** The create-expense form for a receipt being confirmed. `splits` holds (categoryId, categoryInput, amount). */
  final case class ConfirmForm(
      amount: String,
      date: String,
      time: String,
      merchantId: String,
      merchantInput: String,
      notes: String,
      splits: List[(String, String, String)],
      parsedMerchantName: Option[String],
      rowId: Option[UUID]
  )

  object ConfirmForm {

    /** A fresh form prefilled from the receipt; the merchant comes from a tax-id match, else the winning rule. A rule's
      * override category prefills a split for the full amount and its description prefills the notes.
      */
    def fromReceipt(
        r: GetReceipts.Response.Receipt,
        merchants: List[GetMerchants.Response.Merchant],
        categories: List[GetCategories.Response.Category],
        rules: List[ImportRuleMatcher.Rule]
    ): ConfirmForm = {
      val resolved = resolveMerchant(r, merchants, rules)
      val amount = r.amount.map(_.toString).getOrElse("")
      val splits = resolved
        .flatMap(_.categoryId)
        .flatMap(cid => categories.find(_.id == cid))
        .map(c => (c.id.toString, c.name, amount))
        .toList
      ConfirmForm(
        amount = amount,
        date = r.date.map(_.toString).getOrElse(""),
        time = "",
        merchantId = resolved.map(_.merchantId.toString).getOrElse(""),
        merchantInput = resolved.flatMap(m => merchants.find(_.id == m.merchantId)).map(_.name).getOrElse(""),
        notes = resolved.flatMap(_.description).getOrElse(""),
        splits = splits,
        parsedMerchantName = r.merchantName,
        rowId = None
      )
    }
  }

  /** The merchant a receipt resolves to, plus the winning rule's override category and description (both None for a
    * tax-id match, which applies no rule effects).
    */
  final case class ResolvedMerchant(merchantId: UUID, categoryId: Option[UUID], description: Option[String])

  /** The merchant matching the receipt's tax id, else the winning set-merchant rule for its parsed merchant name. */
  def resolveMerchant(
      r: GetReceipts.Response.Receipt,
      merchants: List[GetMerchants.Response.Merchant],
      rules: List[ImportRuleMatcher.Rule]
  ): Option[ResolvedMerchant] =
    r.taxId.flatMap(tid => merchants.find(_.taxId.contains(tid))) match {
      case Some(m) => Some(ResolvedMerchant(m.id, categoryId = None, description = None))
      case None =>
        r.merchantName
          .flatMap(name => ImportRuleMatcher.findReceiptWinner(rules, name, r.amount))
          .flatMap { rule =>
            rule.action.merchantIdOpt.map(mid =>
              ResolvedMerchant(
                mid,
                categoryId = rule.action.overrideCategoryIdOpt,
                description = rule.action.descriptionOpt
              )
            )
          }
    }

  /** The category a batch-confirmed receipt gets: the rule's override category, else the merchant's default. */
  def batchCategoryId(resolved: ResolvedMerchant, merchants: List[GetMerchants.Response.Merchant]): Option[UUID] =
    resolved.categoryId.orElse(merchants.find(_.id == resolved.merchantId).flatMap(_.categoryId))

  /** The notes a batch-confirmed receipt's expense gets: the winning rule's description, if any. */
  def batchNotes(resolved: Option[ResolvedMerchant]): Option[String] =
    resolved.flatMap(_.description)

  /** What the receipts page is doing with the selected receipt. */
  enum ReceiptMode {
    case Viewing(selected: Option[UUID])
    case Confirming(receiptId: UUID, form: ConfirmForm)
    case Linking(receiptId: UUID)

    def selectedId: Option[UUID] = this match {
      case Viewing(s) => s
      case Confirming(id, _) => Some(id)
      case Linking(id) => Some(id)
    }

    /** Edit the form; only a confirming mode has one. */
    def withForm(f: ConfirmForm => ConfirmForm): ReceiptMode = this match {
      case c: Confirming => c.copy(form = f(c.form))
      case other => other
    }

    /** After the receipt's expense is created, go back to viewing it, unless the user already moved on. */
    def confirmed(receiptId: UUID): ReceiptMode = this match {
      case Confirming(id, _) if id == receiptId => Viewing(Some(receiptId))
      case other => other
    }

    /** After the receipt is linked to an expense, go back to viewing it, unless the user already moved on. */
    def linked(receiptId: UUID): ReceiptMode = this match {
      case Linking(id) if id == receiptId => Viewing(Some(receiptId))
      case other => other
    }

    /** Keep the mode while its receipt is visible; otherwise view the first visible receipt. */
    def keptVisible(visibleIds: List[UUID]): ReceiptMode =
      if (selectedId.exists(visibleIds.contains)) this else Viewing(visibleIds.headOption)
  }
}
