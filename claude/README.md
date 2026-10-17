# Orca review flows

[Orca](https://github.com/VirtusLab/orca) flows that enforce this project's
rules as **blocking review gates** rather than advisory docs. Each rule lives in
a small, single-purpose reviewer file; a reviewer runs read-only over a diff,
reports findings, and (in the feature flow) the fixer must resolve them before a
PR is opened.

## Files

| File | What it is |
|---|---|
| `review.sc` | Report-only gate: review the current branch against the rules. No edits. |
| `implement.sc` | Feature flow: plan a prompt → implement → review+fix each change → open a PR. |
| `orca-checks.scala` | Shared `CommitMessageCheck` (a `ReviewCheck`), used by both scripts. |
| `../.orca/reviewers/*.md` | The reviewers — one rule-set per file (see below). |

Reviewers run on `claude.sonnet` (each scope is tiny); the coder/fixer uses the
default coding agent.

## Running

Requires `scala-cli`, JVM 21, and an authenticated `claude`. **Run from the repo
root** so Orca operates on the git root.

```bash
# Flag rule violations on the current branch (read-only):
scala-cli run claude/review.sc -- "review this branch"

# Implement a feature end-to-end and open a PR (makes commits — use a branch):
scala-cli run claude/implement.sc -- "Add a <feature> to <area>"
```

`implement.sc` runs in an isolated worktree (`.orca/worktrees/<prompt-hash>`,
`RunTarget.Worktree`), so several features can run in parallel without sharing a
checkout or branch, and your current checkout is left untouched. The first run
for a prompt is a cold checkout (no build cache); Orca reuses it on retries and
never auto-removes it. `review.sc` runs in-place — it reviews the branch you are
on.

## Reviewers

Orca auto-discovers every `.md` in `.orca/reviewers/` (plus a user-global dir)
and `allReviewers(...)` includes them. Each file is frontmatter + a body that
becomes the reviewer's system prompt:

```markdown
---
description: one line, used by the reviewer picker (required)
files: server/.*\.scala          # optional regex; reviewer only fires when a changed file matches
---

Instructions to the reviewer: what to flag, and what NOT to flag.
```

Add a rule = add a file here. A `files:` regex scopes a reviewer to matching
paths (the default selector drops it otherwise); a file named after a shipped
reviewer (e.g. `security.md`) replaces that shipped one. Current reviewers:

- `coding-patterns.md` — Chimney for case-class mapping, no dropped `Future`s, no silent defaults, prefer enums over stringly-typed values.
- `architecture.md` (`server/src/main/scala`) — downward-only layering (no same-layer peer calls), external services behind their own class, trait + its impls in one file via the companion object.
- `persistence.md` (`…/repositories`) — per-user queries scoped with `WHERE user_id`, reusable queries in DAOs, `withTransaction` for multi-call operations.
- `tests.md` (`src/test/scala`) — no PII in fixtures, fakes/real over mocks, no external calls, no redundant action tests, multi-line JSON, method-only test descriptions.
- `frontend.md` (`web/src/main/scala`) — fetch API data via `AsyncComponent`.
- `api-limits.md` (`lib/api/shared`) — Tapir inputs must bound string length and collection size.
- `evolutions.md` (`evolutions/default/*.sql`) — ≤1 new evolution per PR, existing ones immutable, no downs, FK ⇒ index, named constraints, no DB defaults, UPPER keywords / snake_case columns.

## Commit-message gate

`CommitMessageCheck` (in `orca-checks.scala`) checks every commit in
`defaultBase..HEAD`, skipping Orca's own `orca:` bookkeeping commits. It flags: a
Claude session reference (a `claude.ai/…` link or a `Claude-Session:` footer — a
`Co-Authored-By: Claude` line is allowed), a subject over 72 chars, a missing
body, and conversational references ("as requested", …). It fails loudly if git
can't be read.

## Notes

- **Reviewers must live in `.orca/reviewers/`** — it's the only project-tier
  location Orca scans. `.orca/reviewers/` is git-tracked; the rest of `.orca/`
  (Orca's run-state, cache, settings) is ignored.
- **Commit what you want reviewed.** Orca stashes uncommitted changes at startup,
  so an uncommitted edit is set aside and never seen.
- **Output goes to the trace log, not the console.** Orca drives a TUI and
  captures stdout; read `.orca/cache/runs/<id>/<ts>-<pid>.trace.log` (grep
  `orca.flow - step:` for the narrative, `structured result (<reviewer>):` for
  finding JSON).
- **Pinning models:** reviewers are pinned to `claude.sonnet` in the scripts. To
  move the whole review side (reviewers + picker + lint) to one tier, set
  `reviewAgent = claude:sonnet` in `.orca/settings.properties`.
- **Upgrading Orca:** bump the `//> using dep "org.virtuslab::orca:…"` line in
  each script.
