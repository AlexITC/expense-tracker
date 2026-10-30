package net.wiringbits.components.pages

object ReceiptsLogic {

  /** Per-item outcomes of a batch, labelled for display. */
  type BatchResults = List[(String, Either[String, Unit])]

  /** A batch upload/confirm lifecycle. Results are kept while running and after it finishes, so they stay displayed.
    * The counters are explicit (not derived from results) so clearing results mid-run keeps the progress text intact.
    */
  enum BatchState {
    case Idle
    case Running(done: Int, total: Int, failed: Int, override val results: BatchResults)
    case Done(override val results: BatchResults)

    /** Idle has none; Running and Done override this with their own field. */
    def results: BatchResults = Nil

    def isRunning: Boolean = this match {
      case Running(_, _, _, _) => true
      case _ => false
    }

    /** Count and append an item's outcome; only a running batch records anything. */
    def recorded(label: String, outcome: Either[String, Unit]): BatchState = this match {
      case Running(done, total, failed, rs) =>
        Running(done + 1, total, if (outcome.isLeft) failed + 1 else failed, rs :+ (label -> outcome))
      case other => other
    }

    def finished: BatchState = this match {
      case Running(_, _, _, rs) => Done(rs)
      case other => other
    }

    /** Drop displayed results; a running batch keeps its counters. */
    def cleared: BatchState = this match {
      case r: Running => r.copy(results = Nil)
      case _ => Idle
    }
  }
}
