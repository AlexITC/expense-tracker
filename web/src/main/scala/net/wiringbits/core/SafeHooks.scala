package net.wiringbits.core

import slinky.core.facade.Hooks

import scala.scalajs.js

/** Dependency-safe wrappers over Slinky's hooks.
  *
  * React compares each dependency with `Object.is`, which only compares by VALUE for JS primitives. In Scala.js only
  * `String`, `Int`, `Double` and `Boolean` qualify — NOT `Long` or `Char` (both boxed objects), and no collection,
  * `Option`, tuple, case class, `UUID` or `BigDecimal` (all objects, compared by reference, so a fresh instance each
  * render re-fires the hook and defeats the dependency list). See `HookDepsProbeSpec` for the proof.
  *
  * These wrappers force every dependency to be a `String` key, so the trap is unrepresentable: for anything that is not
  * already a `String`, derive a key (`toString`, interpolation, `ids.map(_.toString).mkString(",")`). Passing no key
  * means "once" (an empty dependency list), exactly like `Hooks.use*(..., Nil)`. For the rare "run on every render"
  * effect, call `Hooks.useEffect(effect)` (no dependency argument) directly.
  *
  * Use these in components instead of `Hooks.useEffect` / `useMemo` / `useCallback` directly. The low-level hook
  * utilities in this package (e.g. `ReactiveHooks`) are the exception: they deliberately depend on a stable object
  * reference and use `Hooks` directly.
  */
object SafeHooks {

  /** Runs after render whenever any dependency key changes; no keys means once. The effect may return a cleanup. */
  def useEffect(deps: String*)(effect: () => Any): Unit =
    Hooks.useEffect(effect, deps)

  /** Recomputes the value whenever any dependency key changes; no keys means once. */
  def useMemo[T](deps: String*)(value: () => T): T =
    Hooks.useMemo(value, deps)

  /** Returns a memoized callback whose identity changes only when a dependency key changes; no keys means stable. */
  def useCallback[T](deps: String*)(callback: T)(implicit ev: T => js.Function): T =
    Hooks.useCallback(callback, deps)
}
