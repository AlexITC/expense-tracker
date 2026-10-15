package net.wiringbits.repositories.daos

import net.wiringbits.repositories.models.ExpenseCategorySplit

import java.sql.Connection
import java.util.UUID

object ExpenseCategorySplitDAO {

  import anorm.*

  def create(request: ExpenseCategorySplit.Create)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        INSERT INTO expense_category_split (id, user_id, expense_id, category_id, amount)
        VALUES (
          ${request.id.toString}::UUID,
          ${request.userId.toString}::UUID,
          ${request.expenseId.toString}::UUID,
          ${request.categoryId.toString}::UUID,
          ${request.amount}
        )
        """.executeUpdate()
  }

  def createAll(requests: List[ExpenseCategorySplit.Create])(implicit conn: Connection): Unit =
    requests.foreach(create)

  def deleteByExpenseId(expenseId: UUID, userId: UUID)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        DELETE FROM expense_category_split
        WHERE expense_id = ${expenseId.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.executeUpdate()
  }

  def findByExpenseId(expenseId: UUID, userId: UUID)(implicit conn: Connection): List[ExpenseCategorySplit] = {
    SQL"""
        SELECT id, user_id, expense_id, category_id, amount
        FROM expense_category_split
        WHERE expense_id = ${expenseId.toString}::UUID
          AND user_id = ${userId.toString}::UUID
        """.as(expenseCategorySplitParser.*)
  }

  def findByUserId(userId: UUID)(implicit conn: Connection): List[ExpenseCategorySplit] = {
    SQL"""
        SELECT id, user_id, expense_id, category_id, amount
        FROM expense_category_split
        WHERE user_id = ${userId.toString}::UUID
        ORDER BY expense_id, id
        """.as(expenseCategorySplitParser.*)
  }
}
