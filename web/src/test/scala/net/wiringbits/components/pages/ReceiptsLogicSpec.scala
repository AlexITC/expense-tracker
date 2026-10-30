package net.wiringbits.components.pages

import net.wiringbits.components.pages.ReceiptsLogic.BatchState
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class ReceiptsLogicSpec extends AnyWordSpec {

  "BatchState" should {
    val ok = "a.pdf" -> Right(())
    val bad = "b.pdf" -> Left("boom")

    "have no results when idle" in {
      BatchState.Idle.results mustBe Nil
      BatchState.Idle.isRunning mustBe false
    }

    "count outcomes and failures while running" in {
      BatchState.Running(0, 3, 0, Nil).recorded(ok._1, ok._2).recorded(bad._1, bad._2) mustBe
        BatchState.Running(2, 3, 1, List(ok, bad))
    }

    "be running only while running" in {
      BatchState.Running(0, 1, 0, Nil).isRunning mustBe true
    }

    "expose results while running and when done" in {
      BatchState.Running(1, 2, 0, List(ok)).results mustBe List(ok)
      BatchState.Done(List(ok, bad)).results mustBe List(ok, bad)
    }

    "ignore outcomes when not running" in {
      BatchState.Idle.recorded(ok._1, ok._2) mustBe BatchState.Idle
      BatchState.Done(List(ok)).recorded(bad._1, bad._2) mustBe BatchState.Done(List(ok))
    }

    "keep the results when finished" in {
      val done = BatchState.Running(2, 2, 1, List(ok, bad)).finished
      done mustBe BatchState.Done(List(ok, bad))
      done.isRunning mustBe false
      BatchState.Idle.finished mustBe BatchState.Idle
    }

    "go idle when cleared after finishing" in {
      BatchState.Done(List(ok)).cleared mustBe BatchState.Idle
      BatchState.Idle.cleared mustBe BatchState.Idle
    }

    "keep counters when cleared mid-run" in {
      val cleared = BatchState.Running(2, 3, 1, List(ok, bad)).cleared
      cleared mustBe BatchState.Running(2, 3, 1, Nil)
      cleared.recorded(ok._1, ok._2) mustBe BatchState.Running(3, 3, 1, List(ok))
    }
  }
}
