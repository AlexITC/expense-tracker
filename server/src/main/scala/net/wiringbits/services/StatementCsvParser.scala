package net.wiringbits.services

import net.wiringbits.api.models.bankstatements.BankStatementDraft
import net.wiringbits.api.utils.BankStatementDraftLimits
import org.apache.commons.csv.{CSVFormat, CSVParser, CSVRecord}

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.time.LocalDate
import java.util.Locale
import scala.annotation.tailrec
import scala.util.matching.Regex
import scala.util.{Try, Using}

/** Turns an uploaded bank statement CSV into draft transactions. Any value that can't be read exactly as written fails
  * the whole file with a message naming the row and column; nothing is guessed or defaulted.
  */
trait StatementCsvParser {
  def parse(bytes: Array[Byte]): Either[String, List[BankStatementDraft.Transaction]]
}

object StatementCsvParser {

  val DateColumn: String = "date"
  val DescriptionColumn: String = "description"
  val AmountColumn: String = "amount"
  val TxnTypeColumn: String = "txn_type"
  val RunningBalanceColumn: String = "running_balance"

  private val RequiredColumns: List[String] = List(DateColumn, DescriptionColumn, AmountColumn)
  private val KnownColumns: List[String] = RequiredColumns ++ List(TxnTypeColumn, RunningBalanceColumn)

  private val DecimalPattern: Regex = BankStatementDraftLimits.DecimalPattern
  private val MaxIntegerDigits: Int = BankStatementDraftLimits.MaxAmountIntegerDigits
  private val MaxFractionDigits: Int = BankStatementDraftLimits.MaxAmountFractionDigits
  private val BoundedDecimalPattern: Regex = BankStatementDraftLimits.BoundedDecimalPattern

  private val Bom: String = "﻿"

  private val Format: CSVFormat = CSVFormat.RFC4180
    .builder()
    .setDelimiter(',')
    .setQuote('"')
    .setIgnoreEmptyLines(true)
    .setIgnoreSurroundingSpaces(false)
    .setTrim(false)
    .get()

  /** One fixed column layout: `date,description,amount[,txn_type][,running_balance]` in any order, header required.
    * Amounts keep the sign as written: negative is money out, positive is money in.
    */
  class FixedSchema extends StatementCsvParser {
    override def parse(bytes: Array[Byte]): Either[String, List[BankStatementDraft.Transaction]] =
      if (bytes.length > BankStatementDraftLimits.MaxCsvBytes)
        Left(s"The CSV file is larger than ${BankStatementDraftLimits.MaxCsvBytes} bytes")
      else decode(bytes).flatMap(text => parseText(stripBom(text)))
  }

  private def decode(bytes: Array[Byte]): Either[String, String] =
    Try {
      StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString
    }.toEither.left.map(_ => "The CSV file is not valid UTF-8 text")

  // Spreadsheet exports commonly prefix a byte order mark; left in place it would corrupt the first header name.
  private def stripBom(text: String): String =
    if (text.startsWith(Bom)) text.substring(Bom.length) else text

  private def parseText(text: String): Either[String, List[BankStatementDraft.Transaction]] =
    Using(CSVParser.parse(text, Format)) { parser =>
      val iterator = parser.iterator()

      // commons-csv reports syntax errors (e.g. an unterminated quote) while advancing to the next record.
      def next(): Either[String, Option[CSVRecord]] =
        Try(if (iterator.hasNext) Some(iterator.next()) else None).toEither.left.map { e =>
          val detail = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
          s"Row ${parser.getRecordNumber + 1}: malformed CSV ($detail)"
        }

      @tailrec
      def loop(
          columns: Map[String, Int],
          count: Int,
          acc: List[BankStatementDraft.Transaction]
      ): Either[String, List[BankStatementDraft.Transaction]] =
        next() match {
          case Left(error) => Left(error)
          case Right(None) if count == 0 => Left("The CSV file has a header row but no transactions")
          case Right(None) => Right(acc.reverse)
          case Right(Some(_)) if count >= BankStatementDraftLimits.MaxTransactions =>
            Left(s"The CSV file has more than ${BankStatementDraftLimits.MaxTransactions} transactions")
          case Right(Some(record)) =>
            parseRow(columns, record) match {
              case Left(error) => Left(error)
              case Right(txn) => loop(columns, count + 1, txn :: acc)
            }
        }

      for {
        headerOpt <- next()
        header <- headerOpt.toRight("The CSV file is empty")
        columns <- parseHeader(header)
        txns <- loop(columns, count = 0, acc = Nil)
      } yield txns
    }.toEither.left.map(e => s"The CSV file could not be read: ${e.getMessage}").flatten

  private def parseHeader(record: CSVRecord): Either[String, Map[String, Int]] = {
    val names = record.values().toList.map(_.toLowerCase(Locale.ROOT))
    val unknown = names.filterNot(KnownColumns.contains)
    val duplicated = names.diff(names.distinct).distinct
    val missing = RequiredColumns.filterNot(names.contains)
    val row = record.getRecordNumber
    val expected = s"expected columns: ${KnownColumns.mkString(", ")}"

    if (unknown.nonEmpty) Left(s"Row $row: unknown column(s) ${quoted(unknown)}; $expected")
    else if (duplicated.nonEmpty) Left(s"Row $row: duplicated column(s) ${quoted(duplicated)}")
    else if (missing.nonEmpty) Left(s"Row $row: missing required column(s) ${quoted(missing)}")
    else Right(names.zipWithIndex.toMap)
  }

  private def parseRow(
      columns: Map[String, Int],
      record: CSVRecord
  ): Either[String, BankStatementDraft.Transaction] = {
    val row = record.getRecordNumber
    def cell(column: String): Option[String] = columns.get(column).map(record.get)
    def fail(column: String, problem: String): Left[String, Nothing] = Left(s"Row $row, column $column: $problem")

    // Required columns are guaranteed by parseHeader, so a missing cell here is a programming error.
    def required(column: String): String =
      cell(column).getOrElse(throw new IllegalStateException(s"Required column $column was not validated"))

    def decimal(column: String, raw: String): Either[String, BigDecimal] =
      if (!DecimalPattern.matches(raw)) fail(column = column, problem = s"\"$raw\" is not a decimal number")
      else if (!BoundedDecimalPattern.matches(raw))
        fail(
          column = column,
          problem = s"must have at most $MaxIntegerDigits integer digits and $MaxFractionDigits decimals"
        )
      else Right(BigDecimal(new java.math.BigDecimal(raw)))

    if (record.size != columns.size)
      Left(s"Row $row: expected ${columns.size} fields but found ${record.size}")
    else
      for {
        date <- {
          val raw = required(DateColumn)
          Try(LocalDate.parse(raw)).toEither.left.map(_ =>
            s"Row $row, column $DateColumn: \"$raw\" is not a date in YYYY-MM-DD format"
          )
        }
        description <- {
          val raw = required(DescriptionColumn)
          if (raw.isBlank) fail(column = DescriptionColumn, problem = "must not be blank")
          else if (raw.length > BankStatementDraftLimits.MaxDescriptionLength)
            fail(
              column = DescriptionColumn,
              problem = s"is longer than ${BankStatementDraftLimits.MaxDescriptionLength} characters"
            )
          else Right(raw)
        }
        amount <- decimal(column = AmountColumn, raw = required(AmountColumn))
        txnType <- cell(TxnTypeColumn).filterNot(_.isBlank) match {
          case Some(raw) if raw.length > BankStatementDraftLimits.MaxTxnTypeLength =>
            fail(
              column = TxnTypeColumn,
              problem = s"is longer than ${BankStatementDraftLimits.MaxTxnTypeLength} characters"
            )
          case other => Right(other)
        }
        runningBalance <- cell(RunningBalanceColumn).filterNot(_.isBlank) match {
          case Some(raw) => decimal(column = RunningBalanceColumn, raw = raw).map(Some(_))
          case None => Right(None)
        }
      } yield BankStatementDraft.Transaction(
        date = date,
        amount = amount,
        description = description,
        txnType = txnType,
        runningBalance = runningBalance
      )
  }

  private def quoted(names: List[String]): String = names.map(n => s"\"$n\"").mkString(", ")
}
