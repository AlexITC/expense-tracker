package net.wiringbits.repositories.daos

import anorm.*
import anorm.SqlParser.*
import net.wiringbits.models.documents.DocumentStatus
import net.wiringbits.repositories.models.Receipt

import java.sql.Connection
import java.time.{Instant, LocalDate, LocalTime}
import java.util.UUID

object ReceiptDAO {

  private implicit val sqlTimeOptionToStatement: ToStatement[Option[java.sql.Time]] = (stmt, idx, v) =>
    v match {
      case None => stmt.setNull(idx, java.sql.Types.TIME)
      case Some(t) => stmt.setTime(idx, t)
    }

  def create(receipt: Receipt.Create)(implicit conn: Connection): Unit = {
    val _ = SQL"""
      INSERT INTO receipt (id, user_id, document_id, manually_edited, status, created_at, updated_at)
      VALUES (
        ${receipt.id.toString}::UUID,
        ${receipt.userId.toString}::UUID,
        ${receipt.documentId.toString}::UUID,
        ${receipt.manuallyEdited},
        ${receipt.status.toString},
        ${receipt.createdAt},
        ${receipt.updatedAt}
      )
      """.executeUpdate()
  }

  def findByDocumentId(documentId: UUID)(implicit conn: Connection): Option[Receipt] =
    SQL"""
      SELECT r.id, r.user_id, r.document_id, r.amount, r.date, r.time, r.merchant_name, r.tax_id,
             r.merchant_id, r.expense_id, r.refund_id, r.ignored_at, r.manually_edited, r.parser_version, r.status AS receipt_status,
             r.created_at, r.updated_at, d.filename, d.status AS document_status
      FROM receipt r
      JOIN document d ON r.document_id = d.id
      WHERE r.document_id = ${documentId.toString}::UUID
      """.as(receiptParser.singleOpt)

  def find(id: UUID, userId: UUID)(implicit conn: Connection): Option[Receipt] =
    SQL"""
      SELECT r.id, r.user_id, r.document_id, r.amount, r.date, r.time, r.merchant_name, r.tax_id,
             r.merchant_id, r.expense_id, r.refund_id, r.ignored_at, r.manually_edited, r.parser_version, r.status AS receipt_status,
             r.created_at, r.updated_at, d.filename, d.status AS document_status
      FROM receipt r
      JOIN document d ON r.document_id = d.id
      WHERE r.id = ${id.toString}::UUID
        AND r.user_id = ${userId.toString}::UUID
      """.as(receiptParser.singleOpt)

  def all(userId: UUID)(implicit conn: Connection): List[Receipt] =
    SQL"""
      SELECT r.id, r.user_id, r.document_id, r.amount, r.date, r.time, r.merchant_name, r.tax_id,
             r.merchant_id, r.expense_id, r.refund_id, r.ignored_at, r.manually_edited, r.parser_version, r.status AS receipt_status,
             r.created_at, r.updated_at, d.filename, d.status AS document_status
      FROM receipt r
      JOIN document d ON r.document_id = d.id
      WHERE r.user_id = ${userId.toString}::UUID
      ORDER BY r.created_at DESC, r.id
      """.as(receiptParser.*)

  def updateParsed(
      id: UUID,
      amount: Option[BigDecimal],
      date: Option[LocalDate],
      time: Option[LocalTime],
      merchantName: Option[String],
      taxId: Option[String],
      parserVersion: Option[String],
      updatedAt: Instant
  )(implicit conn: Connection): Unit = {
    val updated = SQL"""
      UPDATE receipt
      SET amount = $amount,
          date = $date,
          time = ${time.map(java.sql.Time.valueOf)},
          merchant_name = $merchantName,
          tax_id = $taxId,
          parser_version = $parserVersion,
          updated_at = $updatedAt
      WHERE id = ${id.toString}::UUID
      """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Receipt not found")
  }

  def updateStatus(id: UUID, userId: UUID, status: DocumentStatus, updatedAt: Instant)(implicit
      conn: Connection
  ): Unit = {
    val updated = SQL"""
      UPDATE receipt
      SET status = ${status.toString},
          updated_at = $updatedAt
      WHERE id = ${id.toString}::UUID
        AND user_id = ${userId.toString}::UUID
      """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Receipt not found")
  }

  def setExpenseId(id: UUID, userId: UUID, expenseId: UUID, updatedAt: Instant)(implicit conn: Connection): Unit = {
    val updated = SQL"""
      UPDATE receipt
      SET expense_id = ${expenseId.toString}::UUID,
          ignored_at = NULL,
          updated_at = $updatedAt
      WHERE id = ${id.toString}::UUID
        AND user_id = ${userId.toString}::UUID
        AND expense_id IS NULL
      """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Receipt already linked to an expense")
  }

  def setIgnoredAt(id: UUID, userId: UUID, ignoredAt: Instant, updatedAt: Instant)(implicit conn: Connection): Unit = {
    val rows = SQL"""
      SELECT expense_id FROM receipt
      WHERE id = ${id.toString}::UUID AND user_id = ${userId.toString}::UUID
      """.as(get[Option[UUID]]("expense_id").singleOpt)
    rows match {
      case None => throw new RuntimeException("Receipt not found")
      case Some(Some(_)) => throw new RuntimeException("Cannot ignore a receipt that is linked to an expense")
      case Some(None) =>
        SQL"""
          UPDATE receipt
          SET ignored_at = $ignoredAt, updated_at = $updatedAt
          WHERE id = ${id.toString}::UUID AND user_id = ${userId.toString}::UUID
          """.executeUpdate()
        ()
    }
  }

  def clearIgnoredAt(id: UUID, userId: UUID, updatedAt: Instant)(implicit conn: Connection): Unit = {
    val updated = SQL"""
      UPDATE receipt
      SET ignored_at = NULL, updated_at = $updatedAt
      WHERE id = ${id.toString}::UUID AND user_id = ${userId.toString}::UUID
      """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Receipt not found")
  }

  def clearExpenseId(id: UUID, userId: UUID, updatedAt: Instant)(implicit conn: Connection): Unit = {
    val updated = SQL"""
      UPDATE receipt
      SET expense_id = NULL,
          updated_at = $updatedAt
      WHERE id = ${id.toString}::UUID
        AND user_id = ${userId.toString}::UUID
      """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Receipt not found")
  }

  def setRefundId(id: UUID, userId: UUID, refundId: UUID, updatedAt: Instant)(implicit conn: Connection): Unit = {
    val updated = SQL"""
      UPDATE receipt
      SET refund_id = ${refundId.toString}::UUID,
          updated_at = $updatedAt
      WHERE id = ${id.toString}::UUID
        AND user_id = ${userId.toString}::UUID
        AND refund_id IS NULL
      """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Receipt already linked to a refund")
  }

  def clearRefundId(id: UUID, userId: UUID, updatedAt: Instant)(implicit conn: Connection): Unit = {
    val updated = SQL"""
      UPDATE receipt
      SET refund_id = NULL,
          updated_at = $updatedAt
      WHERE id = ${id.toString}::UUID
        AND user_id = ${userId.toString}::UUID
      """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Receipt not found")
  }

  def setMerchantId(id: UUID, merchantId: UUID, updatedAt: Instant)(implicit conn: Connection): Unit = {
    val _ = SQL"""
      UPDATE receipt
      SET merchant_id = ${merchantId.toString}::UUID,
          updated_at = $updatedAt
      WHERE id = ${id.toString}::UUID
      """.executeUpdate()
  }
}
