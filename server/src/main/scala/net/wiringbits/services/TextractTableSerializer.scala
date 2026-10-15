package net.wiringbits.services

import play.api.libs.json.*

/** Turns a Textract AnalyzeDocument (TABLES) block list into column-preserving text, so a statement's charge/credit
  * columns and balance box survive into the LLM input — the structure PDFBox's flat text destroys. One "--- TABLE n
  * ---" block per table, cells joined with " | " per row.
  */
object TextractTableSerializer {

  def serialize(blocks: Seq[JsValue]): String = {
    val byId: Map[String, JsValue] = blocks.flatMap(b => (b \ "Id").asOpt[String].map(_ -> b)).toMap

    def blockType(b: JsValue): String = (b \ "BlockType").asOpt[String].getOrElse("")

    def childIds(b: JsValue): Seq[String] =
      (b \ "Relationships")
        .asOpt[Seq[JsValue]]
        .getOrElse(Nil)
        .filter(r => (r \ "Type").asOpt[String].contains("CHILD"))
        .flatMap(r => (r \ "Ids").asOpt[Seq[String]].getOrElse(Nil))

    def cellText(cell: JsValue): String =
      childIds(cell)
        .flatMap(byId.get)
        .map { w =>
          blockType(w) match {
            case "WORD" => (w \ "Text").asOpt[String].getOrElse("")
            case "SELECTION_ELEMENT" => (w \ "SelectionStatus").asOpt[String].getOrElse("")
            case _ => ""
          }
        }
        .filter(_.nonEmpty)
        .mkString(" ")
        .trim

    def rowIdx(c: JsValue): Int = (c \ "RowIndex").asOpt[Int].getOrElse(1)
    def colIdx(c: JsValue): Int = (c \ "ColumnIndex").asOpt[Int].getOrElse(1)

    val tables: Seq[(Int, Seq[String])] = blocks.filter(b => blockType(b) == "TABLE").flatMap { table =>
      val cells = childIds(table).flatMap(byId.get).filter(c => blockType(c) == "CELL")
      Option.when(cells.nonEmpty) {
        val page = (table \ "Page").asOpt[Int].getOrElse(1)
        val byCoord: Map[(Int, Int), String] = cells.map(c => (rowIdx(c), colIdx(c)) -> cellText(c)).toMap
        val nRows = cells.map(rowIdx).max
        val nCols = cells.map(colIdx).max
        val rows = (1 to nRows).map(r => (1 to nCols).map(col => byCoord.getOrElse((r, col), "")).mkString(" | "))
        (page, rows)
      }
    }

    tables.zipWithIndex
      .map { case ((page, rows), i) => (s"--- TABLE ${i + 1} (page $page) ---" +: rows).mkString("\n") }
      .mkString("\n\n")
  }
}
