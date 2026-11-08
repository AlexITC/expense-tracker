package net.wiringbits.core

import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import slinky.core.FunctionalComponent
import slinky.web.html.*

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

class SafeHooksSpec extends AnyWordSpec with ReactTestDom {
  import SafeHooksSpec.*

  override def beforeEach(): Unit = {
    super.beforeEach()
    computes = 0
  }

  "useMemo" should {
    "recompute once when the String key is constant across renders" in {
      (0 until 3).foreach(n => render(probe(Props("k", n))))
      computes must be(1)
    }
    "recompute on each render when the String key changes" in {
      (0 until 3).foreach(n => render(probe(Props(n.toString, n))))
      computes must be(3)
    }
  }
}
