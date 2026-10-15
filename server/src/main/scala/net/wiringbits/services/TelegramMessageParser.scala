package net.wiringbits.services

import scala.util.Try

object TelegramMessageParser {

  case class ParsedMessage(
      amount: BigDecimal,
      merchantName: String,
      category: Option[String],
      notes: Option[String]
  )

  private val NotePrefix = "note="

  def parse(text: String): Either[String, ParsedMessage] = {
    val lines = text.split("\n", -1).map(_.trim).filter(_.nonEmpty).toList
    lines match {
      case rawAmount :: merchant :: Nil =>
        build(rawAmount, merchant, None, None)
      case rawAmount :: merchant :: third :: Nil =>
        if (third.startsWith(NotePrefix))
          build(rawAmount, merchant, None, noteValue(third))
        else
          build(rawAmount, merchant, Some(third), None)
      case rawAmount :: merchant :: third :: fourth :: Nil =>
        if (third.startsWith(NotePrefix))
          Left("Use line 3 for category and line 4 for note; 'note=' is only needed when skipping the category")
        else
          build(rawAmount, merchant, Some(third), Some(fourth).filter(_.nonEmpty))
      case parts =>
        Left(s"Expected 2 to 4 lines (amount, merchant, optional category, optional note), got ${parts.length}")
    }
  }

  private def noteValue(line: String): Option[String] =
    Some(line.stripPrefix(NotePrefix).trim).filter(_.nonEmpty)

  private def build(
      rawAmount: String,
      merchant: String,
      category: Option[String],
      notes: Option[String]
  ): Either[String, ParsedMessage] =
    if (merchant.isEmpty)
      Left("Merchant name must not be empty")
    else if (merchant.length > 100)
      Left("Merchant name must be 100 characters or less")
    else if (category.exists(_.length > 25))
      Left("Category name must be 25 characters or less")
    else if (notes.exists(_.length > 500))
      Left("Note must be 500 characters or less")
    else
      Try(BigDecimal(rawAmount)).toOption match {
        case None => Left(s"Amount '$rawAmount' is not a valid number")
        case Some(amount) if amount <= 0 => Left(s"Amount must be positive, got $amount")
        case Some(amount) => Right(ParsedMessage(amount, merchant, category, notes))
      }
}
