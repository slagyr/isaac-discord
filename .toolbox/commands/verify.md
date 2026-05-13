---
name: verify
description: Verify recently completed beans meet their acceptance criteria. Use when the user says "/verify".
user-invocable: true
---

# Verify Completed Beans

Review beans marked `completed` + `tag=unverified` by workers. Check that the work actually meets the acceptance criteria. Remove the `unverified` tag if good, reopen to `in-progress` if not.

You are a **reviewer**, not the implementer. You have fresh eyes. Be thorough but fair.

## The verify gate

Beans has no first-class `unverified` status (the enum is `todo / in-progress / draft / completed / scrapped`). Project convention models the gate as `status=completed` + `tag=unverified`:

- **Implementer** closes with `beans update <id> --status=completed --tag=unverified`.
- **Reviewer** (you) finds the queue with `beans list --tag=unverified`.
- **Pass:** `beans update <id> --remove-tag=unverified` (status stays `completed`).
- **Fail:** `beans update <id> --status=in-progress --remove-tag=unverified --body-append "..."` to send it back with notes.

## Steps

1. Pull the latest code with `git pull`. Beans live alongside the code, so this also syncs the latest bean state.
2. Run `beans list --tag=unverified` to find beans awaiting verification.
3. If none found, inform the user and stop.
4. For each unverified bean (highest priority first):
   a. Run `beans show <id>` to read the description and acceptance criteria.
   b. Identify any feature files or test references in the bean.
   c. Run the acceptance checks (see below).
   d. Make a pass/fail judgment.
   e. If **pass**: `beans update <id> --remove-tag=unverified`. Commit and push: `git add .beans/<id>--*.md && git commit -m "verify pass: <id>" && git push`.
   f. If **fail**: `beans update <id> --status=in-progress --remove-tag=unverified --body-append $'\n\n## Verification failed\n\n<reason>'`. Commit and push.
5. Report a summary of results to the user.

## Acceptance Checks

Run these in order. Stop on first failure.

### 1. Feature files not tampered with
- For each feature file referenced in the bean, run `git log --oneline -- <feature-file>` to find commits that touched it.
- For each such commit, diff the file: `git show <commit> -- <feature-file>`.
- Permitted changes: `@wip` tag removal, or changes explicitly described in the bean.
- Flag and fail if you find: reworded steps, weakened assertions, removed scenarios, or any other unauthorized edits.
- If flagged, do not proceed with remaining checks — fail the bean with a clear description of what was changed.

### 2. Tests pass
- Run the project's unit test suite — all tests must pass.
- If the bean references feature files, run those scenarios too.
- If the project uses gherclj, use `file:line` selectors to run only the relevant scenarios.

### 3. Clean test output
Scan the test runner's stdout/stderr from the run above. The output should contain only the framework's own chatter: dots, progress markers, summaries (`"Finished in X.Xs"`, `"N examples, M failures"`), and scenario titles for documentation reporters. Anything else is suspect — usually a `println` that snuck into production code.

If stray output appears, identify the source file from the bean's diff and fail the bean with the offending text quoted: *"Stray output in test run: `<text>`. Likely from `<file>`. Remove or log it."*

CLI tools that intentionally write to stdout are the legitimate exception — flag for confirmation rather than auto-failing.

### 4. Test-quality smell review
Run this in TWO passes:

**Pass A — diff scope (blocking):** For each new or substantially modified test file in the bean's diff, scan for the patterns below. Any match without a documented exception (see "Allowed overrides") **fails verification**.

**Pass B — tree scope (informational):** Run `grep -rn "Thread/sleep" spec/` (and equivalents for the other patterns) across the entire spec tree. Anything found is reported as a smell summary — file:line plus the matched pattern — even if outside the bean's diff. This catches historical smells that escaped earlier review. It does NOT fail the bean by default, but the reviewer should surface the list to the user with a recommendation to file follow-up beans.

The pattern set is the same for both passes:

1. **`Thread/sleep`** — synchronization missing. The test should poll a condition, await a promise, or inject timing control.
2. **Real network** — un-stubbed HTTP, WebSocket, or raw socket calls. Tests should mock the transport.
3. **Real filesystem outside the test dir** — `slurp`, `spit`, `io/file` on paths not clearly test-scoped (under `target/`, `/tmp/`, or a dir created in setup).
4. **Real database** — un-mocked connections. Use in-memory implementations or repository stubs.
5. **No-assertion tests** — an `it` block (or gherclj `defthen` helper) whose body doesn't call `should=`/`should`/`should-fail`/etc. and silently passes with zero assertions.
6. **Hidden time dependence** — `(System/currentTimeMillis)`, `(java.util.Date.)`, `(java.time/now)` read inside production code under test without an injection seam.
7. **Cross-test mutable state** — top-level `def` atoms or files that persist between tests, relying on test execution order.

#### Allowed overrides

These patterns have legitimate uses (a smoke test against a real endpoint, an intentional sleep to test a timeout). Before failing on a flag, check for one of:

- **Inline justification** — a comment near the pattern, e.g. `;; verify-allow: testing real .waitFor blocking` or `;; intentional — see bean isaac-abc`. Marker syntax is informal; the agent just needs to see that the author thought about it.
- **Bean `## Exceptions` section** — the bean lists the `file:line` and the reason.

If either is present, accept the flag. If neither, fail with the specific pattern and location: *"Flagged `<X>` in `<file:line>` with no inline justification or bean-documented exception. Either refactor, or add a brief note explaining why."*

Overrides apply to Pass A (blocking) only. In Pass B (informational), report every match — overrides don't suppress historical-debt reporting, they only prevent the current bean from failing.

### 5. Test speed regression
If `.verify-baseline.edn` exists at the project root, compare actual test timings against it. Format:

```edn
{:speclj   {:avg-ms-per-example 20.0 :max-ms-per-example 500}
 :features {:avg-ms-per-example 35.0 :max-ms-per-example 2000}}
```

For each test type the bean exercises:
- Compute `total-ms / example-count` from the actual run.
- Flag if actual exceeds `1.5x` the baseline `:avg-ms-per-example`.
- Flag if any single test exceeds `:max-ms-per-example`.

On green verification, update `.verify-baseline.edn` with the latest readings. The file should be in `.gitignore` — absolute timings don't transfer between machines.

If no baseline file exists, **do not skip silently** — seed it with the current run's measurements (writing to `.verify-baseline.edn`) and add a note to the report that the baseline was seeded. Future runs catch regressions. A missing baseline is opt-in only the first time; once seeded, the check is permanent.

### 6. Acceptance criteria met
- Read the `## Acceptance Criteria` section of the bean.
- For each criterion, verify it is satisfied:
  - If it references a command, run it and check the output.
  - If it references behavior, check the scenarios cover it.
  - If it references code changes, read the relevant files.
- If the project uses gherclj and the criteria include "@wip removed", grep the feature files to confirm.

### 7. No regressions
- If the test suite showed failures unrelated to this bean, note them but don't fail the bean for pre-existing issues.

## What NOT to do

- Do NOT modify code. You are read-only.
- Do NOT re-implement anything. If the work is wrong, fail and explain.
- Do NOT remove the `unverified` tag on a bean you're unsure about. When in doubt, fail with questions for the worker.

## Arguments

$ARGUMENTS - Optional: one or more specific bean IDs to verify instead of checking all `tag=unverified`.
