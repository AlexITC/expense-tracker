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

// Runs in an isolated worktree (.orca/worktrees/<prompt-hash>) so several
// features can be implemented in parallel without sharing a checkout or branch.
flow(OrcaArgs(args).copy(target = RunTarget.Worktree)):
  val brief =
    s"""$userPrompt
       |
       |Follow the project conventions in docs/guide/ (antipatterns.md,
       |backend.md, frontend.md, data-infra.md, testing.md, ci-checklist.md).""".stripMargin

  val plan = stage("Plan"):
    Plan.autonomous.from(brief, planningAgent).value

  // One review+fix per task, then a final whole-run loop re-checks everything
  // before the PR. Reviewers run on sonnet; the default selector drops any whose
  // `files:` gate doesn't match. The coder/fixer stays on the default coding
  // agent, where the harder work is.
  val taskOpenFindings =
    for task <- plan.tasks yield
      stage(s"Task: ${task.title}"):
        val session = codingAgent.session("implementer", seed = plan.brief)
        session.run(task.description)
        reviewThenFix(
          coderSession = session,
          reviewers = allReviewers(claude.sonnet),
          task = task,
          checks = List(CommitMessageCheck())
        )

  val openFindings = stage("Final review"):
    val finalFixer = codingAgent.session("final-fixer", seed = plan.brief)
    reviewAndFixLoop(
      coderSession = finalFixer,
      reviewers = allReviewers(claude.sonnet),
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
