package net.wiringbits.repositories

import anorm.*
import anorm.SqlParser.*
import anorm.postgresql.*
import net.wiringbits.api.models.bankstatements.ExtractionInfo
import net.wiringbits.common.models.{Email, Name}
import net.wiringbits.models.documents.{DocumentKind, DocumentStatus}
import net.wiringbits.models.jobs.{BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.models.*
import play.api.libs.json.{JsValue, Json}

import java.time.{Instant, LocalDate, LocalTime}
import java.util.UUID

package object daos {

  import anorm.{Column, MetaDataItem, TypeDoesNotMatch}
  import org.postgresql.util.PGobject

  implicit val jsValueToStatement: ToStatement[JsValue] = (stmt, idx, value) => {
    val pgObj = new PGobject()
    pgObj.setType("jsonb")
    pgObj.setValue(Json.stringify(value))
    stmt.setObject(idx, pgObj)
  }

  implicit val jsValueOptionToStatement: ToStatement[Option[JsValue]] = (stmt, idx, v) =>
    v match {
      case None => stmt.setNull(idx, java.sql.Types.OTHER)
      case Some(j) => jsValueToStatement.set(stmt, idx, j)
    }

  implicit val jsValueColumn: Column[JsValue] = Column.nonNull { case (value, meta) =>
    val MetaDataItem(qualified, _, _) = meta
    value match {
      case obj: PGobject =>
        try Right(Json.parse(obj.getValue))
        catch {
          case e: Exception =>
            Left(TypeDoesNotMatch(s"Cannot parse JSONB to JsValue for column $qualified: ${e.getMessage}"))
        }
      case str: String =>
        try Right(Json.parse(str))
        catch {
          case e: Exception =>
            Left(TypeDoesNotMatch(s"Cannot parse String to JsValue for column $qualified: ${e.getMessage}"))
        }
      case _ =>
        Left(
          TypeDoesNotMatch(
            s"Cannot convert $value: ${value.asInstanceOf[AnyRef].getClass} to JsValue for column $qualified"
          )
        )
    }
  }

  implicit val extractionInfoColumn: Column[ExtractionInfo] = jsValueColumn.mapResult { js =>
    js.validate[ExtractionInfo].asEither.left.map(e => TypeDoesNotMatch(s"Cannot parse ExtractionInfo: $e"))
  }

  implicit val citextToString: Column[String] = Column.nonNull { case (value, meta) =>
    val MetaDataItem(qualified, _, clazz) = meta
    value match {
      case str: String => Right(str)
      case obj: PGobject if "citext".equalsIgnoreCase(obj.getType) => Right(obj.getValue)
      case _ =>
        Left(
          TypeDoesNotMatch(
            s"Cannot convert $value: ${value.asInstanceOf[AnyRef].getClass} to String for column $qualified, class = $clazz"
          )
        )
    }
  }

  implicit val nameParser: Column[Name] = Column.columnToString.map(Name.trusted)
  implicit val emailParser: Column[Email] = citextToString.map(Email.trusted)

  val userParser: RowParser[User] = {
    Macro.parser[User](
      "user_id",
      "name",
      "email",
      "password",
      "created_at",
      "verified_on"
    )
  }

  val userLogParser: RowParser[UserLog] = {
    Macro.parser[UserLog]("user_log_id", "user_id", "message", "created_at")
  }

  def enumColumn[A](f: String => Option[A]): Column[A] = Column.columnToString.mapResult { string =>
    f(string)
      .toRight(SqlRequestError(new RuntimeException(s"The value $string doesn't exists")))
  }

  implicit val tokenTypeColumn: Column[UserTokenType] = enumColumn(
    UserTokenType.withNameInsensitiveOption
  )

  // TODO: Use Macro.parser, for some reason it doesn't work so we have to parse it manually
  implicit val tokenParser: RowParser[UserToken] = {
    get[UUID]("user_token_id") ~
      str("token") ~
      get[UserTokenType]("token_type") ~
      get[Instant]("created_at") ~
      get[Instant]("expires_at") ~
      get[UUID]("user_id") map { case tokenId ~ token ~ tokenType ~ createdAt ~ expiresAt ~ userId =>
        UserToken(
          id = tokenId,
          tokenType = tokenType,
          token = token,
          createdAt = createdAt,
          expiresAt = expiresAt,
          userId = userId
        )
      }
  }

  implicit val textractJobStatusColumn: Column[TextractJob.Status] =
    Column.columnToString.map(TextractJob.Status.fromString)

  implicit val backgroundJobStatusColumn: Column[BackgroundJobStatus] = enumColumn(
    BackgroundJobStatus.withNameInsensitiveOption
  )

  implicit val backgroundJobTypeColumn: Column[BackgroundJobType] = enumColumn(
    BackgroundJobType.withNameInsensitiveOption
  )

  implicit val backgroundJobParser: RowParser[BackgroundJobData] = {
    Macro.parser[BackgroundJobData](
      "background_job_id",
      "type",
      "payload",
      "status",
      "status_details",
      "error_count",
      "execute_at",
      "created_at",
      "updated_at"
    )
  }

  val categoryParser: RowParser[Category] = {
    Macro.parser[Category]("id", "user_id", "name", "created_at", "updated_at")
  }

  val merchantParser: RowParser[Merchant] = {
    Macro.parser[Merchant](
      "id",
      "user_id",
      "name",
      "notes",
      "category_id",
      "tax_id",
      "reviewed",
      "created_at",
      "updated_at"
    )
  }

  implicit val localTimeToStatement: ToStatement[LocalTime] = (stmt, idx, value) =>
    stmt.setTime(idx, java.sql.Time.valueOf(value))

  implicit val localDateColumn: Column[LocalDate] = Column.nonNull { case (value, meta) =>
    value match {
      case d: java.sql.Date => Right(d.toLocalDate)
      case _ =>
        Left(
          TypeDoesNotMatch(
            s"Cannot convert $value: ${value.asInstanceOf[AnyRef].getClass} to LocalDate for column ${meta.column}"
          )
        )
    }
  }

  implicit val localTimeColumn: Column[LocalTime] = Column.nonNull { case (value, meta) =>
    value match {
      case t: java.sql.Time => Right(t.toLocalTime)
      case _ =>
        Left(
          TypeDoesNotMatch(
            s"Cannot convert $value: ${value.asInstanceOf[AnyRef].getClass} to LocalTime for column ${meta.column}"
          )
        )
    }
  }

  val expenseParser: RowParser[Expense] = {
    Macro.parser[Expense]("id", "user_id", "amount", "date", "time", "merchant_id", "notes", "created_at", "updated_at")
  }

  val expenseCategorySplitParser: RowParser[ExpenseCategorySplit] = {
    Macro.parser[ExpenseCategorySplit]("id", "user_id", "expense_id", "category_id", "amount")
  }

  implicit val documentStatusColumn: Column[DocumentStatus] = enumColumn(
    DocumentStatus.withNameInsensitiveOption
  )

  implicit val documentKindColumn: Column[DocumentKind] =
    Column.columnToString.mapResult { s =>
      DocumentKind
        .withNameOption(s)
        .toRight(SqlRequestError(new RuntimeException(s"Unknown DocumentKind: $s")))
    }

  val documentParser: RowParser[Document] = {
    Macro.parser[Document](
      "id",
      "user_id",
      "sha256",
      "storage_key",
      "kind",
      "filename",
      "status",
      "parser_version",
      "created_at",
      "updated_at"
    )
  }

  val receiptParser: RowParser[Receipt] =
    (get[UUID]("id") ~
      get[UUID]("user_id") ~
      get[UUID]("document_id") ~
      get[Option[BigDecimal]]("amount") ~
      get[Option[LocalDate]]("date") ~
      get[Option[LocalTime]]("time") ~
      get[Option[String]]("merchant_name") ~
      get[Option[String]]("tax_id") ~
      get[Option[UUID]]("merchant_id") ~
      get[Option[UUID]]("expense_id") ~
      get[Option[UUID]]("refund_id") ~
      get[Option[Instant]]("ignored_at") ~
      get[Boolean]("manually_edited") ~
      get[Option[String]]("parser_version") ~
      get[Instant]("created_at") ~
      get[Instant]("updated_at") ~
      str("filename") ~
      get[DocumentStatus]("status")).map {
      case id ~ userId ~ documentId ~ amount ~ date ~ time ~ merchantName ~ taxId ~ merchantId ~ expenseId ~ refundId ~
          ignoredAt ~ manuallyEdited ~ parserVersion ~ createdAt ~ updatedAt ~ filename ~ status =>
        Receipt(
          id,
          userId,
          documentId,
          amount,
          date,
          time,
          merchantName,
          taxId,
          merchantId,
          expenseId,
          refundId,
          ignoredAt,
          manuallyEdited,
          parserVersion,
          createdAt,
          updatedAt,
          filename,
          status
        )
    }

  val refundParser: RowParser[Refund] =
    Macro.parser[Refund](
      "id",
      "user_id",
      "amount",
      "date",
      "merchant_id",
      "category_id",
      "expense_id",
      "notes",
      "created_at",
      "updated_at"
    )

  val linkTokenParser: RowParser[LinkToken] =
    Macro.parser[LinkToken]("telegram_link_token_id", "token", "user_id", "expires_at", "created_at")

  val botUserParser: RowParser[BotUser] =
    Macro.parser[BotUser]("telegram_bot_user_id", "user_id", "telegram_user_id", "created_at")

  val mcpTokenParser: RowParser[McpToken] =
    Macro.parser[McpToken]("id", "user_id", "token_hash", "created_at", "last_used_at")

  val telegramReceiptParser: RowParser[TelegramReceipt] =
    Macro.parser[TelegramReceipt](
      "telegram_receipt_id",
      "receipt_id",
      "chat_id",
      "expense_message_id",
      "created_at",
      "updated_at"
    )

  val textractCacheParser: RowParser[TextractCache] = {
    Macro.parser[TextractCache]("sha256", "api_type", "response", "created_at")
  }

  val savingsGoalParser: RowParser[SavingsGoal] =
    Macro.parser[SavingsGoal]("id", "user_id", "name", "target_amount", "created_at", "updated_at")

  val savingsGoalWithBalanceParser: RowParser[(SavingsGoal, BigDecimal)] =
    (get[UUID]("id") ~
      get[UUID]("user_id") ~
      get[String]("name") ~
      get[Option[BigDecimal]]("target_amount") ~
      get[Instant]("created_at") ~
      get[Instant]("updated_at") ~
      get[BigDecimal]("balance")).map { case id ~ userId ~ name ~ targetAmount ~ createdAt ~ updatedAt ~ balance =>
      (SavingsGoal(id, userId, name, targetAmount, createdAt, updatedAt), balance)
    }

  val savingsAllocationParser: RowParser[SavingsAllocation] =
    (get[UUID]("id") ~
      get[UUID]("user_id") ~
      get[UUID]("goal_id") ~
      get[BigDecimal]("amount") ~
      get[LocalDate]("date") ~
      get[Option[String]]("note") ~
      get[Option[UUID]]("merchant_id") ~
      get[Instant]("created_at") ~
      get[Instant]("updated_at")).map {
      case id ~ userId ~ goalId ~ amount ~ date ~ note ~ merchantId ~ createdAt ~ updatedAt =>
        SavingsAllocation(id, userId, goalId, amount, date, note, merchantId, createdAt, updatedAt)
    }
}
