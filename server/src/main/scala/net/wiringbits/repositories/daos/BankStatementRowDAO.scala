package net.wiringbits.repositories.daos

import anorm.*
import anorm.SqlParser.*
import net.wiringbits.repositories.models.BankStatementRow

import java.sql.Connection
import java.time.{Instant, LocalDate}
import java.util.UUID

object BankStatementRowDAO {

  val parser: RowParser[BankStatementRow] =
    (get[UUID]("id") ~
      get[UUID]("bank_statement_id") ~
      get[Int]("index") ~
      get[LocalDate]("date") ~
      get[BigDecimal]("amount") ~
      str("description") ~
      get[Boolean]("manually_edited") ~
      get[Option[Instant]]("confirmed_at") ~
      get[Option[UUID]]("expense_id") ~
      get[Option[UUID]]("refund_id") ~
      get[Option[Instant]]("ignored_at") ~
      str("txn_type") ~
      get[Instant]("created_at") ~
      get[Instant]("updated_at")).map {
      case id ~ bankStatementId ~ index ~ date ~ amount ~ description ~ manuallyEdited ~ confirmedAt ~ expenseId ~ refundId ~ ignoredAt ~ txnType ~ createdAt ~ updatedAt =>
        BankStatementRow(
          id,
          bankStatementId,
          index,
          date,
          amount,
          description,
          manuallyEdited,
          confirmedAt,
          expenseId,
          refundId,
          ignoredAt,
          txnType,
          createdAt,
          updatedAt
        )
    }

  def createAll(rows: List[BankStatementRow.Create])(implicit conn: Connection): Unit = {
    rows.foreach { r =>
      val _ = SQL"""
        INSERT INTO bank_statement_row (id, bank_statement_id, index, date, amount, description, manually_edited, txn_type, created_at, updated_at)
        VALUES (
          ${r.id.toString}::UUID,
          ${r.bankStatementId.toString}::UUID,
          ${r.index},
          ${r.date},
          ${r.amount},
          ${r.description},
          ${r.manuallyEdited},
          ${r.txnType},
          ${r.createdAt},
          ${r.updatedAt}
        )
        """.execute()
    }
  }

  def deleteByStatementId(bankStatementId: UUID)(implicit conn: Connection): Unit = {
    val _ = SQL"""
      DELETE FROM bank_statement_row WHERE bank_statement_id = ${bankStatementId.toString}::UUID
      """.execute()
  }

  def allForUser(userId: UUID)(implicit conn: Connection): List[BankStatementRow] =
    SQL"""
      SELECT bsr.id, bsr.bank_statement_id, bsr.index, bsr.date, bsr.amount, bsr.description,
             bsr.manually_edited, bsr.confirmed_at, bsr.expense_id, bsr.refund_id, bsr.ignored_at,
             bsr.txn_type, bsr.created_at, bsr.updated_at
      FROM bank_statement_row bsr
      JOIN bank_statement bs ON bsr.bank_statement_id = bs.id
      WHERE bs.user_id = ${userId.toString}::UUID
      ORDER BY bsr.date DESC, bsr.id
      """.as(parser.*)

  /** (categoryId, description) pairs from the user's own rows linked to a categorized expense, keeping the
    * `perCategory` most recent rows per category.
    */
  def categorizedExamples(userId: UUID, perCategory: Int)(implicit conn: Connection): List[(UUID, String)] = {
    val exampleParser = (get[UUID]("category_id") ~ str("description")).map { case categoryId ~ description =>
      (categoryId, description)
    }
    SQL"""
      SELECT category_id, description
      FROM (
        SELECT ecs.category_id, bsr.description, bsr.date, bsr.id,
               ROW_NUMBER() OVER (PARTITION BY ecs.category_id ORDER BY bsr.date DESC, bsr.id) AS rn
        FROM bank_statement_row bsr
        JOIN bank_statement bs ON bsr.bank_statement_id = bs.id
          AND bs.user_id = ${userId.toString}::UUID
        JOIN expense_category_split ecs ON ecs.expense_id = bsr.expense_id
          AND ecs.user_id = ${userId.toString}::UUID
      ) ranked
      WHERE rn <= $perCategory
      ORDER BY category_id, date DESC, id
      """.as(exampleParser.*)
  }

  def setExpenseIdForUser(rowId: UUID, userId: UUID, expenseId: UUID, updatedAt: Instant)(implicit
      conn: Connection
  ): Unit = {
    val updated = SQL"""
      UPDATE bank_statement_row
      SET expense_id = ${expenseId.toString}::UUID,
          ignored_at = NULL,
          updated_at = $updatedAt
      WHERE id = ${rowId.toString}::UUID
        AND expense_id IS NULL
        AND bank_statement_id IN (
          SELECT id FROM bank_statement WHERE user_id = ${userId.toString}::UUID
        )
      """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Bank statement row not found or already linked to an expense")
  }

  def findByStatementId(bankStatementId: UUID)(implicit conn: Connection): List[BankStatementRow] =
    SQL"""
      SELECT id, bank_statement_id, index, date, amount, description, manually_edited, confirmed_at, expense_id, refund_id, ignored_at, txn_type, created_at, updated_at
      FROM bank_statement_row
      WHERE bank_statement_id = ${bankStatementId.toString}::UUID
      ORDER BY index ASC
      """.as(parser.*)

  def findById(rowId: UUID, bankStatementId: UUID)(implicit conn: Connection): Option[BankStatementRow] =
    SQL"""
      SELECT id, bank_statement_id, index, date, amount, description, manually_edited, confirmed_at, expense_id, refund_id, ignored_at, txn_type, created_at, updated_at
      FROM bank_statement_row
      WHERE id = ${rowId.toString}::UUID
        AND bank_statement_id = ${bankStatementId.toString}::UUID
      """.as(parser.singleOpt)

  def confirm(
      rowId: UUID,
      bankStatementId: UUID,
      date: LocalDate,
      amount: BigDecimal,
      description: String,
      manuallyEdited: Boolean,
      confirmedAt: Instant,
      updatedAt: Instant
  )(implicit conn: Connection): Unit = {
    val _ = SQL"""
      UPDATE bank_statement_row
      SET date = $date,
          amount = $amount,
          description = $description,
          manually_edited = $manuallyEdited,
          confirmed_at = $confirmedAt,
          updated_at = $updatedAt
      WHERE id = ${rowId.toString}::UUID
        AND bank_statement_id = ${bankStatementId.toString}::UUID
      """.execute()
  }

  def setExpenseId(rowId: UUID, bankStatementId: UUID, expenseId: UUID, updatedAt: Instant)(implicit
      conn: Connection
  ): Unit = {
    val updated = SQL"""
      UPDATE bank_statement_row
      SET expense_id = ${expenseId.toString}::UUID,
          ignored_at = NULL,
          updated_at = $updatedAt
      WHERE id = ${rowId.toString}::UUID
        AND bank_statement_id = ${bankStatementId.toString}::UUID
        AND expense_id IS NULL
      """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Bank statement row not found or already linked to an expense")
  }

  def clearExpenseId(rowId: UUID, bankStatementId: UUID, updatedAt: Instant)(implicit conn: Connection): Unit = {
    val updated = SQL"""
      UPDATE bank_statement_row
      SET expense_id = NULL,
          updated_at = $updatedAt
      WHERE id = ${rowId.toString}::UUID
        AND bank_statement_id = ${bankStatementId.toString}::UUID
      """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Bank statement row not found")
  }

  def setRefundId(rowId: UUID, bankStatementId: UUID, refundId: UUID, updatedAt: Instant)(implicit
      conn: Connection
  ): Unit = {
    val updated = SQL"""
      UPDATE bank_statement_row
      SET refund_id = ${refundId.toString}::UUID,
          ignored_at = NULL,
          updated_at = $updatedAt
      WHERE id = ${rowId.toString}::UUID
        AND bank_statement_id = ${bankStatementId.toString}::UUID
        AND refund_id IS NULL
      """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Bank statement row not found or already linked to a refund")
  }

  def clearRefundId(rowId: UUID, bankStatementId: UUID, updatedAt: Instant)(implicit conn: Connection): Unit = {
    val updated = SQL"""
      UPDATE bank_statement_row
      SET refund_id = NULL,
          updated_at = $updatedAt
      WHERE id = ${rowId.toString}::UUID
        AND bank_statement_id = ${bankStatementId.toString}::UUID
      """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Bank statement row not found")
  }

  def setIgnoredAt(rowId: UUID, bankStatementId: UUID, ignoredAt: Instant, updatedAt: Instant)(implicit
      conn: Connection
  ): Unit = {
    val updated = SQL"""
      UPDATE bank_statement_row
      SET ignored_at = $ignoredAt,
          updated_at = $updatedAt
      WHERE id = ${rowId.toString}::UUID
        AND bank_statement_id = ${bankStatementId.toString}::UUID
        AND expense_id IS NULL
        AND refund_id IS NULL
      """.executeUpdate()
    if (updated == 0) {
      val stateParser = (get[Option[UUID]]("expense_id") ~ get[Option[UUID]]("refund_id")).map {
        case expenseId ~ refundId => (expenseId, refundId)
      }
      val stateOpt = SQL"""
        SELECT expense_id, refund_id
        FROM bank_statement_row
        WHERE id = ${rowId.toString}::UUID
          AND bank_statement_id = ${bankStatementId.toString}::UUID
        """.as(stateParser.singleOpt)
      stateOpt match {
        case None => throw new RuntimeException("Bank statement row not found")
        case Some((Some(_), _)) => throw new RuntimeException("Bank statement row is already linked to an expense")
        case Some((_, Some(_))) => throw new RuntimeException("Bank statement row is already linked to a refund")
        case Some((None, None)) => throw new RuntimeException("Bank statement row not found")
      }
    }
  }

  def clearIgnoredAt(rowId: UUID, bankStatementId: UUID, updatedAt: Instant)(implicit conn: Connection): Unit = {
    val updated = SQL"""
      UPDATE bank_statement_row
      SET ignored_at = NULL,
          updated_at = $updatedAt
      WHERE id = ${rowId.toString}::UUID
        AND bank_statement_id = ${bankStatementId.toString}::UUID
      """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Bank statement row not found")
  }
}
