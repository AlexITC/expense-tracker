package net.wiringbits.models

/** A database-persisted enum whose stored string is [[entryName]], used by the DAOs in SQL. It derives from `toString`
  * (the case name) by default, so cases need not restate it; enums whose stored form differs (a snake_case or uppercase
  * column value) override it per case. Because the default stored value is the case name, renaming a case changes what
  * is written — migrate the column if you rename one.
  */
trait DbEnum {
  def entryName: String = toString
}

object DbEnum {

  /** Mixed into an enum's companion object to add name-based lookups over the enum's `values`, matched on `entryName`.
    */
  trait Companion[E <: DbEnum] {
    def values: Array[E]
    def withNameOption(name: String): Option[E] = values.find(_.entryName == name)
    def withNameInsensitiveOption(name: String): Option[E] = values.find(_.entryName.equalsIgnoreCase(name))
  }
}
