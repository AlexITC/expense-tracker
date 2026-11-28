package net.wiringbits.repositories.daos

import net.wiringbits.common.models.{Email, Name}
import net.wiringbits.repositories.models.User

import java.sql.Connection
import java.util.UUID

object UsersDAO {

  import anorm.*

  def create(request: User.CreateUser)(implicit conn: Connection): Unit = {
    val _ = SQL"""
        INSERT INTO users
          (user_id, name, email, password, created_at, session_version)
        VALUES (
          ${request.id.toString}::UUID,
          ${request.name.string},
          ${request.email.string},
          ${request.hashedPassword},
          NOW(),
          0
        )
        """
      .execute()
  }

  def all()(implicit conn: Connection): List[User] = {
    SQL"""
        SELECT user_id, name, email, password, created_at, verified_on, session_version
        FROM users
        """.as(userParser.*)
  }

  def find(email: Email)(implicit conn: Connection): Option[User] = {
    SQL"""
        SELECT user_id, name, email, password, created_at, verified_on, session_version
        FROM users
        WHERE email = ${email.string}::CITEXT
        """.as(userParser.singleOpt)
  }

  def find(userId: UUID)(implicit conn: Connection): Option[User] = {
    SQL"""
        SELECT user_id, name, email, password, created_at, verified_on, session_version
        FROM users
        WHERE user_id = ${userId.toString}::UUID
        """.as(userParser.singleOpt)
  }

  def updateName(userId: UUID, name: Name)(implicit conn: Connection): Unit = {
    val _ = SQL"""
      UPDATE users
      SET name = ${name.string}
      WHERE user_id = ${userId.toString}::UUID
    """.execute()
  }

  def verify(userId: UUID)(implicit conn: Connection): Unit = {
    val _ = SQL"""
      UPDATE users
      SET verified_on = NOW()
      WHERE user_id = ${userId.toString}::UUID
    """.execute()
  }

  def resetPassword(userId: UUID, password: String)(implicit conn: Connection): Unit = {
    val _ = SQL"""
      UPDATE users
      SET password = $password
      WHERE user_id = ${userId.toString}::UUID
    """.execute()
  }

  def findUserForUpdate(userId: UUID)(implicit conn: Connection): Option[User] = {
    SQL"""
        SELECT user_id, name, email, password, created_at, verified_on, session_version
        FROM users
        WHERE user_id = ${userId.toString}::UUID
        FOR UPDATE NOWAIT
        """.as(userParser.singleOpt)
  }

  def findSessionVersion(userId: UUID)(implicit conn: Connection): Option[Int] = {
    SQL"""
        SELECT session_version
        FROM users
        WHERE user_id = ${userId.toString}::UUID
        """.as(SqlParser.int("session_version").singleOpt)
  }

  def bumpSessionVersion(userId: UUID)(implicit conn: Connection): Int = {
    SQL"""
      UPDATE users
      SET session_version = session_version + 1
      WHERE user_id = ${userId.toString}::UUID
      RETURNING session_version
    """.as(SqlParser.int("session_version").single)
  }
}
