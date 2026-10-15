//> using dep "org.virtuslab::orca:0.0.11"
//> using jvm 21

import orca.{*, given}

flow(OrcaArgs(args)):
  val issueHandle = IssueHandle.parseOrThrow(userPrompt)

  val issue = stage("Fetch issue"):
    gh.readIssue(issueHandle)

  val issueContext =
    s"""Issue: ${issueHandle.shortRef}
       |Title: ${issue.title}
       |
       |${issue.body}
       |
       |Before planning or implementing, read the project conventions in docs/guide/:
       |antipatterns.md, backend.md, ci-checklist.md, data-infra.md, frontend.md, local-setup.md, testing.md""".stripMargin

  val coder = claude.sonnet
  val session = coder.newSession

  val planFile = Plan.defaultPath(userPrompt)
  val plan = stage("Acquire plan"):
    Plan.recoverOrCreate(planFile, "orca: starting work"):
      Plan.autonomous.from(issueContext, coder).value

  Plan.implementTaskLoop(planFile, plan): task =>
    stage(s"Implement task: ${task.title}"):
      stage("Implementation"):
        val _ = coder.autonomous.run(task.description, session)
      reviewAndFixLoop(
        coder = coder,
        sessionId = session,
        reviewers = minimalReviewers(claude.opus).map(r => r.withName(r.name + " (opus)")),
        reviewerSelection = ReviewerSelector.llmDriven(claude.sonnet),
        task = s"${task.title.value}\n\nWhen reviewing, check compliance with project conventions in docs/guide/ (antipatterns.md, frontend.md, backend.md, testing.md, etc.).",
        formatCommand = Some("sbt scalafmtAll"),
        lintCommand = Some("sbt scalafmtCheckAll Test/compile"),
        lintLlm = Some(claude.sonnet)
      )

  git.push().orThrow
  gh.createPr(issue.title.trim.take(255), s"Closes ${issueHandle.shortRef}\n\n${plan.description}").orThrow

