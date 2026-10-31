package net.wiringbits.components

import org.scalajs.dom
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import slinky.core.FunctionalComponent
import slinky.core.facade.Hooks
import slinky.web.ReactDOM
import slinky.web.html.*

import java.util.UUID
import scala.compiletime.uninitialized
import scala.scalajs.js

/** Proves how React hook dependency lists actually behave in Slinky, so the "use only primitives in deps" rule rests on
  * evidence rather than folklore.
  *
  * React compares each element of the dependency list between renders with `Object.is`. Slinky takes the list as
  * `Iterable[Any]` (so `js.Array(...)`, `List(...)` or an empty `""` all work — the container is irrelevant; the
  * ELEMENTS are the dependencies). All three dependency-taking hooks (`useEffect`, `useMemo`, `useCallback`) share the
  * exact same per-element `Object.is` comparison in React (`areHookInputsEqual`), so proving it for one proves it for
  * all. `useMemo` is the probe here because it recomputes synchronously during render — no passive-effect flush /
  * `act()` needed (plain jsdom does not flush `useEffect`, and Slinky re-wraps a `useCallback` result each render, so
  * neither counts cleanly; `useMemo` does).
  *
  * A dependency is STABLE (compared by value, so the hook does not re-fire when it is unchanged) only if it maps to a
  * JS primitive: `String`, `Int`, `Double`, `Boolean`, `Char`. Any Scala object recreated per render — `List`,
  * `Option`, tuple, case class, `UUID`, and notably `Long` (a `RuntimeLong` object, not a JS primitive) — is compared
  * by reference, so a fresh instance each render looks "changed" every time and the hook re-fires on every render.
  */
object HookDepsProbeSpec {

  final case class Box(x: Int)

  // A single instance reused across renders, to show that reference identity (not type) is what matters.
  val stableList: List[Int] = List(1, 2)
  val fixedUuid = "00000000-0000-0000-0000-000000000001"

  var counts: scala.collection.mutable.Map[String, Int] = scala.collection.mutable.Map.empty
  private def bump(k: String): Unit = counts(k) = counts.getOrElse(k, 0) + 1

  case class Props(strategy: String, n: Int)

  // Each strategy builds the dependency list a different way; `n` is the render index (0,1,2).
  private def depsFor(strategy: String, n: Int): Iterable[Any] = strategy match {
    // primitives, constant across renders -> stable (compared by value)
    case "string" => js.Array("k")
    case "int" => js.Array(42)
    case "double" => js.Array(3.14)
    case "boolean" => js.Array(true)
    case "char" => js.Array('x')
    // a primitive that genuinely changes each render -> re-fires every render
    case "changing" => js.Array(n.toString)
    // Scala objects, a NEW instance each render (value-equal but reference-different) -> re-fires every render
    case "list" => js.Array(List(1, 2))
    case "vector" => js.Array(Vector(1, 2))
    case "array" => js.Array(Array(1, 2)) // scala.Array is a JS array object; new instance each render
    case "set" => js.Array(Set(1, 2))
    case "map" => js.Array(Map(1 -> 2))
    case "option" => js.Array(Some(1))
    case "tuple" => js.Array((1, 2))
    case "caseclass" => js.Array(Box(1))
    case "bigdecimal" => js.Array(BigDecimal("1.5"))
    case "uuid" => js.Array(UUID.fromString(fixedUuid))
    case "long" => js.Array(5L)
    // the SAME object reference every render -> stable despite being an object
    case "stable-reference" => js.Array(stableList)
    // List container instead of js.Array, same unstable inner object -> identical behaviour (container irrelevant)
    case "list-container" => List(List(1, 2))
    // the three ways to express "empty deps" — proven below to all mean compute-once
    case "empty-jsarray" => js.Array[Any]()
    case "empty-list" => List.empty[Any]
    case "empty-string" => "" // String is Iterable[Char]; empty String is an empty dependency list
    case other => sys.error(s"unknown strategy $other")
  }

  val memoProbe: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    Hooks.useMemo[Unit](() => bump(props.strategy), depsFor(props.strategy, props.n))
    div(props.n.toString)
  }
}

class HookDepsProbeSpec extends AnyWordSpec with BeforeAndAfterEach {
  import HookDepsProbeSpec.*

  private var container: dom.Element = uninitialized

  override def beforeEach(): Unit = {
    container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    counts = scala.collection.mutable.Map.empty
  }

  override def afterEach(): Unit = {
    ReactDOM.unmountComponentAtNode(container)
    dom.document.body.removeChild(container)
  }

  /** Render the memo probe 3 times into the same container; useMemo recomputes synchronously so the count is final. */
  private def memoRecomputes(strategy: String): Int = {
    (0 until 3).foreach(n => ReactDOM.render(memoProbe(Props(strategy, n)), container))
    counts.getOrElse(strategy, 0)
  }

  "useMemo dependency list — primitives are stable (compared by value)" should {
    // Note: Char is NOT here — Scala.js boxes Char to a non-primitive, so it is unstable (see below).
    for (p <- List("string", "int", "double", "boolean"))
      s"recompute once for a constant $p" in { memoRecomputes(p) must be(1) }
    "recompute every render for a changing primitive" in { memoRecomputes("changing") must be(3) }
  }

  "useMemo dependency list — these are UNSTABLE (compared by reference, re-fire every render)" should {
    for (o <- List("list", "vector", "array", "set", "map", "option", "tuple", "caseclass", "bigdecimal", "uuid"))
      s"recompute every render for a new $o instance" in { memoRecomputes(o) must be(3) }
    "recompute every render for a Long (RuntimeLong is an object, not a JS primitive)" in {
      memoRecomputes("long") must be(3)
    }
    "recompute every render for a Char (Scala.js boxes it, not a JS primitive)" in {
      memoRecomputes("char") must be(3)
    }
  }

  "useMemo dependency list — container and reference identity" should {
    "recompute once when the same object reference is reused" in { memoRecomputes("stable-reference") must be(1) }
    "behave identically with a List container as with js.Array" in { memoRecomputes("list-container") must be(3) }
    "treat an empty js.Array as compute-once" in { memoRecomputes("empty-jsarray") must be(1) }
    "treat an empty List as compute-once (same as an empty js.Array)" in { memoRecomputes("empty-list") must be(1) }
    "treat an empty String as compute-once" in { memoRecomputes("empty-string") must be(1) }
  }

}
