package net.wiringbits.core

import org.scalajs.dom
import org.scalatest.{BeforeAndAfterEach, Suite}
import slinky.core.facade.ReactElement
import slinky.web.{ReactDOMClient, ReactRoot}

import scala.compiletime.uninitialized
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** react-dom/test-utils `act`: runs a block and flushes the React work it schedules (renders, state updates from
  * events, effects) before returning, so assertions observe the settled DOM. React 18's `createRoot` batches and
  * flushes asynchronously, unlike the synchronous legacy `ReactDOM.render`, so tests must drive it through `act`.
  */
@js.native
@JSImport("react-dom/test-utils", JSImport.Namespace)
private object ReactTestUtils extends js.Object {
  def act(block: js.Function0[Any]): Any = js.native
}

private object ReactTestDom {
  private var configured = false
  def configureOnce(): Unit =
    if (!configured) {
      // Set globals as properties of the globalThis object: Scala.js emits `js.Dynamic.global.x = v` as a bare
      // assignment, which throws in the bundle's strict mode when the global does not already exist.
      val g = js.Dynamic.global.globalThis
      // Tell React 18 this is an act() testing environment; without it React warns that act is unsupported here.
      g.updateDynamic("IS_REACT_ACT_ENVIRONMENT")(true)
      // jsdom does not implement layout, so every element's getBoundingClientRect is all zeros. MUI reads that rect
      // to validate a Popover/Menu anchor and warns ("anchorEl ... should be part of the document layout") on an
      // all-zero box, even when the anchor is attached. Give elements a non-zero rect once so that check passes; no
      // test asserts on geometry, so the exact numbers are irrelevant.
      val rect = () =>
        js.Dynamic.literal(x = 0, y = 0, top = 0, left = 0, right = 100, bottom = 40, width = 100, height = 40)
      g.Element.prototype.updateDynamic("getBoundingClientRect")(rect: js.Function0[js.Any])
      configured = true
    }
}

/** Mounts React components into a jsdom container through the React 18 `createRoot` API, flushing each render and
  * interaction synchronously (via `act`) so assertions can run immediately after. Specs mix this in and call `render`
  * instead of the deprecated `ReactDOM.render`, and wrap DOM interactions (clicks) in `act`.
  */
trait ReactTestDom extends BeforeAndAfterEach { this: Suite =>

  protected var container: dom.Element = uninitialized
  private var root: ReactRoot = uninitialized

  override def beforeEach(): Unit = {
    super.beforeEach()
    ReactTestDom.configureOnce()
    container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    root = ReactDOMClient.createRoot(container)
  }

  override def afterEach(): Unit =
    try {
      act(root.unmount())
      dom.document.body.removeChild(container)
    } finally super.afterEach()

  /** Render synchronously into the test container. */
  protected def render(element: ReactElement): Unit =
    act { root.render(element); () }

  /** Run an interaction (such as a DOM click) and flush the React work it schedules before returning. */
  protected def act(body: => Any): Unit =
    ReactTestUtils.act(() => { body; () })
}
