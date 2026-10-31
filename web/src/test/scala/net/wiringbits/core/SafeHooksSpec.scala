package net.wiringbits.core

import org.scalajs.dom
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import slinky.core.FunctionalComponent
import slinky.web.ReactDOM
import slinky.web.html.*

import scala.compiletime.uninitialized

/** Confirms the SafeHooks wrapper threads String dependency keys through to React's comparison (via useMemo, which
  * recomputes synchronously). HookDepsProbeSpec proves the underlying per-type behaviour.
  */
object SafeHooksSpec {
  var computes: Int = 0
  case class Props(key: String, n: Int)
  val probe: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    SafeHooks.useMemo(props.key)(() => { computes += 1; () })
    div(props.n.toString)
  }
}

class SafeHooksSpec extends AnyWordSpec with BeforeAndAfterEach {
  import SafeHooksSpec.*

  private var container: dom.Element = uninitialized

  override def beforeEach(): Unit = {
    container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    computes = 0
  }

  override def afterEach(): Unit = {
    ReactDOM.unmountComponentAtNode(container)
    dom.document.body.removeChild(container)
  }

  "SafeHooks.useMemo" should {
    "recompute once when the String key is constant across renders" in {
      (0 until 3).foreach(n => ReactDOM.render(probe(Props("k", n)), container))
      computes must be(1)
    }
    "recompute on each render when the String key changes" in {
      (0 until 3).foreach(n => ReactDOM.render(probe(Props(n.toString, n)), container))
      computes must be(3)
    }
  }
}
