package net.wiringbits.models

/** A database-persisted enum whose stored string is its case name. `entryName` (used by the DAOs in SQL) derives from
  * `toString`, so cases need not restate it. Because the stored value is the case name, renaming a case changes what is
  * written — migrate the column if you rename one. Enums whose stored form differs from the case name (e.g. a
  * snake_case or uppercase column value) define their own `entryName` instead.
  */
trait DbEnum {
  def entryName: String = toString
}
