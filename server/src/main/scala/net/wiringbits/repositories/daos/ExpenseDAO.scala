package net.wiringbits.repositories.daos

import net.wiringbits.repositories.models.Expense

import java.sql.Connection
import java.time.{Instant, LocalDate, LocalTime}
import java.util.UUID

object ExpenseDAO {

  import anorm.*

  private implicit val sqlTimeOptionToStatement: ToStatement[Option[java.sql.Time]] = (stmt, idx, v) =>
    v match {
      case None => stmt.setNull(idx, java.sql.Types.TIME)
      case Some(t) => stmt.setTime(idx, t)
    }

  def create(request: Expense.Create)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        INSERT INTO expense (id, user_id, amount, date, time, merchant_id, notes, created_at, updated_at)
        VALUES (
          ${request.id.toString}::UUID,
          ${request.userId.toString}::UUID,
          ${request.amount},
          ${request.date},
          ${request.time.map(java.sql.Time.valueOf)},
          ${request.merchantId.map(_.toString)}::UUID,
          ${request.notes},
          ${request.createdAt},
          ${request.updatedAt}
        )
        """.execute()
  }

  def all(userId: UUID)(implicit conn: Connection): List[Expense] = {
    SQL"""
        SELECT id, user_id, amount, date, time, merchant_id, notes, created_at, updated_at
        FROM expense
        WHERE user_id = ${userId.toString}::UUID
        ORDER BY date DESC, time DESC NULLS LAST, created_at DESC
        """.as(expenseParser.*)
  }

  def find(id: UUID, userId: UUID)(implicit conn: Connection): Option[Expense] = {
    SQL"""
        SELECT id, user_id, amount, date, time, merchant_id, notes, created_at, updated_at
        FROM expense
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.as(expenseParser.singleOpt)
  }

  def update(
      id: UUID,
      userId: UUID,
      amount: BigDecimal,
      date: LocalDate,
      time: Option[LocalTime],
      merchantId: Option[UUID],
      notes: Option[String],
      updatedAt: Instant
  )(implicit conn: Connection): Unit = {
    val updated = SQL"""
        UPDATE expense
        SET amount = $amount,
            date = $date,
            time = ${time.map(java.sql.Time.valueOf)},
            merchant_id = ${merchantId.map(_.toString)}::UUID,
            notes = $notes,
            updated_at = $updatedAt
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.executeUpdate()
    if (updated == 0) throw new RuntimeException("Expense not found")
  }

  def countByMonth(userId: UUID, year: Int, month: Int)(implicit conn: Connection): Long = {
    SQL"""
        SELECT COUNT(*)
        FROM expense
        WHERE user_id = ${userId.toString}::UUID
          AND EXTRACT(YEAR FROM date) = $year
          AND EXTRACT(MONTH FROM date) = $month
        """.as(anorm.SqlParser.scalar[Long].single)
  }

  def delete(id: UUID, userId: UUID)(implicit conn: Connection): Unit = {
    val deleted = SQL"""
        DELETE FROM expense
        WHERE id = ${id.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.executeUpdate()
    if (deleted == 0) throw new RuntimeException("Expense not found")
  }

  def updateMerchantId(pendingId: UUID, realId: UUID, userId: UUID, updatedAt: Instant)(implicit
      conn: Connection
  ): Unit = {
    SQL"""
        UPDATE expense
        SET merchant_id = ${realId.toString}::UUID, updated_at = $updatedAt
        WHERE merchant_id = ${pendingId.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.executeUpdate()
    ()
  }

  def medianAmountByMerchant(userId: UUID, merchantId: UUID)(implicit conn: Connection): Option[BigDecimal] = {
    import anorm.SqlParser.get as sqlGet
    SQL"""
        SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY amount) AS median_amount
        FROM expense
        WHERE user_id = ${userId.toString}::UUID
          AND merchant_id = ${merchantId.toString}::UUID
        """.as(sqlGet[Option[BigDecimal]]("median_amount").single)
  }
}
