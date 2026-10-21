package net.wiringbits.repositories.daos

import anorm.*
import anorm.SqlParser.*
import net.wiringbits.api.models.bankstatements.{AiSuggestions, ExtractionInfo}
import net.wiringbits.repositories.models.{BankStatement, BankStatementView}

import play.api.libs.json.Json

import java.sql.Connection
import java.time.{Instant, LocalDate}
import java.util.UUID

object BankStatementDAO {

  // The metadata columns, shared by every SELECT that reads a bank_statement.
  private val metadataColumns =
    "bank_name, product_type, currency, account_last4, period_start, period_end"

  val parser: RowParser[BankStatement] =
    (get[UUID]("id") ~
      get[UUID]("document_id") ~
      get[UUID]("user_id") ~
      str("bank_name") ~
      str("product_type") ~
      str("currency") ~
      str("account_last4") ~
      get[LocalDate]("period_start") ~
      get[LocalDate]("period_end") ~
      get[Instant]("created_at") ~
      get[Instant]("updated_at")).map {
      case id ~ documentId ~ userId ~ bankName ~ productType ~ currency ~ accountLast4 ~
          periodStart ~ periodEnd ~ createdAt ~ updatedAt =>
        BankStatement(
          id,
          documentId,
          userId,
          bankName,
          productType,
          currency,
          accountLast4,
          periodStart,
          periodEnd,
          createdAt,
          updatedAt
        )
    }

  private val viewParser: RowParser[BankStatementView] =
    (get[UUID]("id") ~
      get[UUID]("document_id") ~
      get[UUID]("user_id") ~
      str("bank_name") ~
      str("product_type") ~
      str("currency") ~
      str("account_last4") ~
      get[LocalDate]("period_start") ~
      get[LocalDate]("period_end") ~
      get[Instant]("created_at") ~
      get[Instant]("updated_at") ~
      str("filename") ~
      get[Int]("total_rows") ~
      get[Int]("processed_rows") ~
      get[Option[ExtractionInfo]]("extraction_info") ~
      get[Option[AiSuggestions]]("ai_suggestions")).map {
      case id ~ documentId ~ userId ~ bankName ~ productType ~ currency ~ accountLast4 ~
          periodStart ~ periodEnd ~ createdAt ~ updatedAt ~ filename ~ totalRows ~ processedRows ~
          extractionInfo ~ aiSuggestions =>
        BankStatementView(
          id,
          documentId,
          userId,
          bankName,
          productType,
          currency,
          accountLast4,
          periodStart,
          periodEnd,
          createdAt,
          updatedAt,
          filename,
          totalRows,
          processedRows,
          extractionInfo,
          aiSuggestions
        )
    }

  def create(stmt: BankStatement.Create, metadata: BankStatement.Metadata, extractionInfo: Option[ExtractionInfo])(
      implicit conn: Connection
  ): Unit = {
    val _ = SQL"""
      INSERT INTO bank_statement (
        id, document_id, user_id,
        bank_name, product_type, currency, account_last4, period_start, period_end,
        created_at, updated_at, extraction_info
      )
      VALUES (
        ${stmt.id.toString}::UUID,
        ${stmt.documentId.toString}::UUID,
        ${stmt.userId.toString}::UUID,
        ${metadata.bankName},
        ${metadata.productType},
        ${metadata.currency},
        ${metadata.accountLast4},
        ${metadata.periodStart},
        ${metadata.periodEnd},
        ${stmt.createdAt},
        ${stmt.updatedAt},
        ${extractionInfo.map(Json.toJson(_))}
      )
      """.execute()
  }

  def find(id: UUID, userId: UUID)(implicit conn: Connection): Option[BankStatementView] =
    SQL"""
      SELECT bs.id, bs.document_id, bs.user_id, bs.bank_name, bs.product_type, bs.currency,
             bs.account_last4, bs.period_start, bs.period_end, bs.created_at, bs.updated_at,
             bs.extraction_info, bs.ai_suggestions, d.filename,
             COALESCE(rc.total_rows, 0) AS total_rows,
             COALESCE(rc.processed_rows, 0) AS processed_rows
      FROM bank_statement bs
      JOIN document d ON bs.document_id = d.id
      LEFT JOIN (
        SELECT bank_statement_id,
               COUNT(*) AS total_rows,
               COUNT(*) FILTER (WHERE expense_id IS NOT NULL OR refund_id IS NOT NULL OR ignored_at IS NOT NULL) AS processed_rows
        FROM bank_statement_row
        WHERE bank_statement_id = ${id.toString}::UUID
        GROUP BY bank_statement_id
      ) rc ON rc.bank_statement_id = bs.id
      WHERE bs.id = ${id.toString}::UUID
        AND bs.user_id = ${userId.toString}::UUID
      """.as(viewParser.singleOpt)

  def all(userId: UUID)(implicit conn: Connection): List[BankStatementView] =
    SQL"""
      SELECT bs.id, bs.document_id, bs.user_id, bs.bank_name, bs.product_type, bs.currency,
             bs.account_last4, bs.period_start, bs.period_end, bs.created_at, bs.updated_at,
             bs.extraction_info, bs.ai_suggestions, d.filename,
             COALESCE(rc.total_rows, 0) AS total_rows,
             COALESCE(rc.processed_rows, 0) AS processed_rows
      FROM bank_statement bs
      JOIN document d ON bs.document_id = d.id
      LEFT JOIN (
        SELECT bank_statement_id,
               COUNT(*) AS total_rows,
               COUNT(*) FILTER (WHERE expense_id IS NOT NULL OR refund_id IS NOT NULL OR ignored_at IS NOT NULL) AS processed_rows
        FROM bank_statement_row
        GROUP BY bank_statement_id
      ) rc ON rc.bank_statement_id = bs.id
      WHERE bs.user_id = ${userId.toString}::UUID
      ORDER BY bs.created_at DESC, bs.id
      """.as(viewParser.*)

  def setAiSuggestions(id: UUID, userId: UUID, suggestions: AiSuggestions, updatedAt: Instant)(implicit
      conn: Connection
  ): Unit = {
    val updated = SQL"""
      UPDATE bank_statement
      SET ai_suggestions = ${Json.toJson(suggestions)},
          updated_at = $updatedAt
      WHERE id = ${id.toString}::UUID
        AND user_id = ${userId.toString}::UUID
      """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Bank statement not found")
  }

  def findByDocumentId(documentId: UUID, userId: UUID)(implicit conn: Connection): Option[BankStatement] =
    SQL"""
      SELECT id, document_id, user_id, #$metadataColumns, created_at, updated_at
      FROM bank_statement
      WHERE document_id = ${documentId.toString}::UUID
        AND user_id = ${userId.toString}::UUID
      """.as(parser.singleOpt)

  def findInternal(id: UUID)(implicit conn: Connection): Option[BankStatement] =
    SQL"""
      SELECT id, document_id, user_id, #$metadataColumns, created_at, updated_at
      FROM bank_statement
      WHERE id = ${id.toString}::UUID
      """.as(parser.singleOpt)

  def findByDocumentIdInternal(documentId: UUID)(implicit conn: Connection): Option[BankStatement] =
    SQL"""
      SELECT id, document_id, user_id, #$metadataColumns, created_at, updated_at
      FROM bank_statement
      WHERE document_id = ${documentId.toString}::UUID
      """.as(parser.singleOpt)

}
