package net.wiringbits.services

import net.wiringbits.services.TelegramMessageParser.ParsedMessage
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class TelegramMessageParserSpec extends AnyWordSpec {

  "parse" should {

    "parse a valid 2-line message" in {
      val result = TelegramMessageParser.parse("12.50\nStarbucks")
      result mustBe Right(ParsedMessage(BigDecimal("12.50"), "Starbucks", None, None))
    }

    "parse a valid 3-line message with category" in {
      val result = TelegramMessageParser.parse("99.99\nAmazon\nShopping")
      result mustBe Right(ParsedMessage(BigDecimal("99.99"), "Amazon", Some("Shopping"), None))
    }

    "parse a 3-line message with note= prefix, no category" in {
      val result = TelegramMessageParser.parse("10.00\nAmazon\nnote=birthday gift")
      result mustBe Right(ParsedMessage(BigDecimal("10.00"), "Amazon", None, Some("birthday gift")))
    }

    "parse a 4-line message with category and note" in {
      val result = TelegramMessageParser.parse("10.00\nAmazon\nShopping\nbirthday gift")
      result mustBe Right(ParsedMessage(BigDecimal("10.00"), "Amazon", Some("Shopping"), Some("birthday gift")))
    }

    "treat note= with no value as None" in {
      val result = TelegramMessageParser.parse("10.00\nAmazon\nnote=")
      result mustBe Right(ParsedMessage(BigDecimal("10.00"), "Amazon", None, None))
    }

    "reject note= on line 3 combined with a 4th line" in {
      val result = TelegramMessageParser.parse("10.00\nAmazon\nnote=foo\nextra")
      result mustBe a[Left[?, ?]]
    }

    "reject a non-numeric amount" in {
      val result = TelegramMessageParser.parse("abc\nStarbucks")
      result mustBe a[Left[?, ?]]
      result.left.getOrElse("") must include("abc")
    }

    "reject a non-positive amount" in {
      List("0", "-5.00").foreach { amt =>
        withClue(s"input: $amt") {
          val result = TelegramMessageParser.parse(s"$amt\nStarbucks")
          result mustBe a[Left[?, ?]]
        }
      }
    }

    "reject a message with only 1 non-empty line" in {
      val result = TelegramMessageParser.parse("10.00")
      result mustBe a[Left[?, ?]]
      result.left.getOrElse("") must include("line")
    }

    "reject a message with 5 or more lines" in {
      val result = TelegramMessageParser.parse("10.00\nShop\nFood\nNote\nExtra")
      result mustBe a[Left[?, ?]]
      result.left.getOrElse("") must include("line")
    }

    "reject an empty string" in {
      val result = TelegramMessageParser.parse("")
      result mustBe a[Left[?, ?]]
      result.left.getOrElse("") must include("line")
    }

    "trim whitespace around each line" in {
      val result = TelegramMessageParser.parse("  15.00  \n  Costa Coffee  \n  Coffee  ")
      result mustBe Right(ParsedMessage(BigDecimal("15.00"), "Costa Coffee", Some("Coffee"), None))
    }

    "ignore blank lines between content" in {
      val result = TelegramMessageParser.parse("15.00\n\nCosta Coffee")
      result mustBe Right(ParsedMessage(BigDecimal("15.00"), "Costa Coffee", None, None))
    }

    "handle a trailing newline" in {
      val result = TelegramMessageParser.parse("15.00\nCosta Coffee\n")
      result mustBe Right(ParsedMessage(BigDecimal("15.00"), "Costa Coffee", None, None))
    }

    "treat a whitespace-only category line as None" in {
      val result = TelegramMessageParser.parse("5.00\nShop\n   ")
      result mustBe Right(ParsedMessage(BigDecimal("5.00"), "Shop", None, None))
    }

    "reject a merchant name longer than 100 characters" in {
      val longName = "A" * 101
      val result = TelegramMessageParser.parse(s"10.00\n$longName")
      result mustBe a[Left[?, ?]]
      result.left.getOrElse("") must include("100")
    }

    "reject a category name longer than 25 characters" in {
      val longCategory = "B" * 26
      val result = TelegramMessageParser.parse(s"10.00\nShop\n$longCategory")
      result mustBe a[Left[?, ?]]
      result.left.getOrElse("") must include("25")
    }

    "reject a note longer than 500 characters" in {
      val longNote = "C" * 501
      val result = TelegramMessageParser.parse(s"10.00\nShop\nFood\n$longNote")
      result mustBe a[Left[?, ?]]
      result.left.getOrElse("") must include("500")
    }
  }
}
