// Orca feature flow — plan a prompt into tasks, implement each, review every
// change against the scoped reviewers in `.orca/reviewers/*.md` and the
// commit-message gate, then open a PR.
//
//   scala-cli run implement.sc -- "Add a <feature> to <area>"
//
// Driven by a plain prompt — no GitHub issue required. The reviewers and the
// commit check are the same ones review.sc uses, so a rule is enforced the same
// way whether code is being reviewed or freshly implemented.
//> using scala 3.9.0
//> using dep "org.virtuslab::orca:0.2.0"
//> using jvm 21
//> using file orca-checks.scala

import orca.{*, given}

flow(OrcaArgs(args)):
  val brief =
    s"""$userPrompt
       |
       |Follow the project conventions in docs/guide/ (antipatterns.md,
       |backend.md, frontend.md, data-infra.md, testing.md, ci-checklist.md).""".stripMargin

  val plan = stage("Plan"):
    Plan.autonomous.from(brief, planningAgent).value

  // One review+fix per task, taken on trust; the final whole-run loop re-checks
  // everything with fresh eyes before the PR.
  val taskOpenFindings =
    for task <- plan.tasks yield
      stage(s"Task: ${task.title}"):
        val session = codingAgent.session("implementer", seed = plan.brief)
        session.run(task.description)
        reviewThenFix(
          coderSession = session,
          // Reviewers on sonnet (tiny per-criterion scope); the coder/fixer
          // stays on the default coding agent, where the harder work is.
          reviewers = allReviewers(claude.sonnet),
          task = task,
          checks = List(CommitMessageCheck())
        )

  val openFindings = stage("Final review"):
    val finalFixer = codingAgent.session("final-fixer", seed = plan.brief)
    reviewAndFixLoop(
      coderSession = finalFixer,
      reviewers = allReviewers(claude.sonnet),
      reviewerSelection = ReviewerSelector.allEveryRound,
      task = Task(Title("The whole planned change"), plan.brief),
      diff = ReviewDiff.WholeRun,
      checks = List(CommitMessageCheck()),
      maxFixTurns = 5,
      priorOpenFindings = taskOpenFindings.flatMap(_.findings)
    )

  openPrIfGitHub(
    summarisingAgent = codingAgent,
    openFindings = openFindings
  )
