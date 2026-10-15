package net.wiringbits.services

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.*

class TextractTableSerializerSpec extends AnyWordSpec {

  private def word(id: String, text: String): JsObject =
    Json.obj("Id" -> id, "BlockType" -> "WORD", "Text" -> text)
  private def cell(id: String, row: Int, col: Int, childIds: Seq[String]): JsObject =
    Json.obj(
      "Id" -> id,
      "BlockType" -> "CELL",
      "RowIndex" -> row,
      "ColumnIndex" -> col,
      "Relationships" -> Json.arr(Json.obj("Type" -> "CHILD", "Ids" -> childIds))
    )
  private def table(id: String, cellIds: Seq[String]): JsObject =
    Json.obj(
      "Id" -> id,
      "BlockType" -> "TABLE",
      "Page" -> 1,
      "Relationships" -> Json.arr(Json.obj("Type" -> "CHILD", "Ids" -> cellIds))
    )

  "TextractTableSerializer.serialize" should {
    "render a table with its columns preserved" in {
      val blocks = Seq(
        table("t1", Seq("c1", "c2", "c3", "c4")),
        cell("c1", 1, 1, Seq("w1")),
        cell("c2", 1, 2, Seq("w2")),
        cell("c3", 2, 1, Seq("w3", "w3b")),
        cell("c4", 2, 2, Seq("w4")),
        word("w1", "CONCEPTO"),
        word("w2", "CARGOS"),
        word("w3", "PDM"),
        word("w3b", "PAGOS"),
        word("w4", "40,00")
      )
      val out = TextractTableSerializer.serialize(blocks)
      out must include("--- TABLE 1 (page 1) ---")
      out must include("CONCEPTO | CARGOS")
      out must include("PDM PAGOS | 40,00")
    }

    "return an empty string when there are no tables" in {
      TextractTableSerializer.serialize(Seq(Json.obj("Id" -> "x", "BlockType" -> "PAGE"))) must be("")
    }
  }
}
