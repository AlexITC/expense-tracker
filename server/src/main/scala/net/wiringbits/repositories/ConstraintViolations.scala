package net.wiringbits.repositories

import org.postgresql.util.PSQLException

object ConstraintViolations {

  /** Run a DB write and translate a unique/exclusion constraint violation into a domain error (a `RuntimeException`
    * carrying the user-facing message). Each pair maps a constraint/index name to its message; the first whose name
    * appears in the violation wins. Any other failure propagates unchanged. The guarantee lives in the DB constraint —
    * this is how the repository surfaces a conflict instead of a generic 500 (see .orca/reviewers/persistence.md).
    */
  def translating[A](mappings: (String, String)*)(block: => A): A =
    try block
    catch {
      case ex: PSQLException =>
        mappings.collectFirst { case (name, message) if ex.getMessage.contains(name) => message } match {
          case Some(message) => throw new RuntimeException(message)
          case None => throw ex
        }
    }
}
