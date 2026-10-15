# Orca Autonomous Issue Loop

Run this prompt via `/loop` in Claude Code to autonomously resolve GitHub issues one at a time using Orca.

## Usage

```
/loop 30minutes <paste prompt below>
```

---

## Prompt

Autonomously resolve GitHub issues for AlexITC/expense-tracker one at a time using Orca. Repeat every 30 minutes, but only after each cycle fully completes — never schedule the next wakeup before the current one is done.

## Each cycle

**0. CI check + conflict check (best-effort)**
For each open issue labeled `done`, find its associated PR and check both CI status and mergeability:
```bash
gh issue list --repo AlexITC/expense-tracker --label done --state open --json number | python3 -c "import json,sys; print([i['number'] for i in json.load(sys.stdin)])"
```
For each issue number N:
```bash
PR=$(gh pr list --repo AlexITC/expense-tracker --state open --json number,headRefName,body | python3 -c "
import json,sys; prs=json.load(sys.stdin)
m=[p for p in prs if '#N' in p['body']]
print(m[0]['number'], m[0]['headRefName']) if m else print('none none')
")
PR_NUM=${PR%% *}; BRANCH=${PR##* }
```
If PR_NUM == "none": skip.

**CI check (best-effort):**
```bash
HEAD_SHA=$(gh pr view $PR_NUM --repo AlexITC/expense-tracker --json headRefOid --jq .headRefOid 2>/dev/null)
FAILURES=$(gh api repos/AlexITC/expense-tracker/commits/$HEAD_SHA/check-runs --jq '[.check_runs[] | select(.conclusion=="failure") | .name]' 2>/dev/null)
```
If any check has `conclusion=failure`: remove `done` label, post comment "CI failed on PR #$PR_NUM: <failing check names>. Re-queuing." Skip if 403 or no checks found.

**Conflict check:**
```bash
MERGEABLE=$(gh api repos/AlexITC/expense-tracker/pulls/$PR_NUM --jq '.mergeable' 2>/dev/null)
```
- If `MERGEABLE` is empty, `null`, or `true`: skip.
- If `MERGEABLE == "false"`:
  1. Stash/clean working tree, then attempt rebase:
     ```bash
     git checkout main && git pull --rebase && git checkout $BRANCH && git pull --rebase origin $BRANCH
     git rebase main
     ```
  2. If rebase exits 0: `git push --force-with-lease origin $BRANCH` — conflicts resolved, leave label as-is.
  3. If rebase exits non-zero:
     ```bash
     git rebase --abort
     git checkout main
     ```
     Then: remove `done` label, add `ready` label, post comment: "PR #$PR_NUM has merge conflicts that could not be auto-resolved. Re-queuing."

**1. Find work**
```bash
gh issue list --repo AlexITC/expense-tracker --state open --json number,title,body,labels | python3 -c "
import json,sys
issues=json.load(sys.stdin)
result=[i for i in issues if len(i['labels'])==0 or any(l['name']=='ready' for l in i['labels'])]
print(json.dumps(result))
"
```
If empty → schedule wakeup in 30 minutes, stop.

**2. Prioritize**
Read all candidate issues and pick one:
1. Data corruption or security vulnerabilities
2. Bugs on critical flows (auth, expenses, bank statements)
3. Other bugs
4. Features

**3. Pre-flight checks**
a. Issue is still open: `gh issue view N --repo AlexITC/expense-tracker --json state`
b. Count "Attempt X/3" comments on the issue — if 3 exist: remove ready label if present, add failed, skip to step 7
c. Check for an existing open PR referencing this issue:
   ```bash
   gh pr list --repo AlexITC/expense-tracker --state open --json number,headRefName,body | python3 -c "
   import json,sys; prs=json.load(sys.stdin)
   m=[p for p in prs if '#N' in p['body']]
   print(m[0]['number'], m[0]['headRefName']) if m else print('none')
   "
   ```
   - If a PR is found → set CONTINUE_MODE=true, save PR_NUM and BRANCH. Do NOT treat this as a hard block.
   - If no PR found → CONTINUE_MODE=false, proceed normally.

**4. Prepare**
Detect default branch: `gh repo view AlexITC/expense-tracker --json defaultBranchRef --jq '.defaultBranchRef.name'`

If CONTINUE_MODE=false:
  `cd /home/ubuntu/projects/expense-tracker && git checkout <default-branch> && git pull --rebase`

If CONTINUE_MODE=true:
  - Fetch PR review comments and issue comments, build feedback string
  - Save the original issue body: `ORIG_BODY=$(gh issue view N --repo AlexITC/expense-tracker --json body --jq .body)`
  - Prepend feedback to the issue body so Orca reads it via `issue.body`
  - Checkout the existing PR branch: `git checkout <default-branch> && git pull --rebase && git checkout $BRANCH && git pull --rebase`

**5. Run Orca — in background, then wait**
Run with run_in_background=true:
```bash
cd /home/ubuntu/projects/expense-tracker && SBT_OPTS="-Xmx2g" scala-cli run implement.sc -- "AlexITC/expense-tracker#N"
```
Do not proceed until the background process completes and you receive its output.

**6. Cleanup (if CONTINUE_MODE=true)**
Restore the original issue body: `gh issue edit N --repo AlexITC/expense-tracker --body "$ORIG_BODY"`

**7. Report**
On success (exit 0, OR exit 1 with output containing only "PrAlreadyExists"):
  - Read Orca output for "Opened PR:" line; if PrAlreadyExists use the existing PR URL
  ```bash
  gh issue edit N --repo AlexITC/expense-tracker --remove-label ready --add-label done
  gh issue comment N --repo AlexITC/expense-tracker --body "PR: <url>"
  ```

On failure (any other error):
  Count prior "Attempt X/3" comments to determine M, then post: "Attempt M/3: <brief error summary>"
  If M == 3: `gh issue edit N --repo AlexITC/expense-tracker --remove-label ready --add-label failed`

**8. Schedule next wakeup — only after steps 0 through 7 are fully complete**
ScheduleWakeup 30 minutes.

## Hard blocks → needs-info
Remove ready label if present, add needs-info. Read relevant code first, then comment explaining what currently exists and what is needed:
- Issue body too vague to produce a concrete plan
- Referenced component or feature does not exist in the codebase
- Would require modifying any existing file under server/src/main/resources/evolutions/ — only new sequentially-numbered files are allowed
- Requires destructive DB operations (DROP TABLE, mass DELETE/UPDATE without user-scoped WHERE)
- Issue is a question or discussion, not an actionable change
- Issue is already closed
