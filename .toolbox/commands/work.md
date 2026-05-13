---
name: work
description: Pick up the next ready bean and work on it. Use when the user says "/work" or asks to start working on the next task.
user-invocable: true
---

# Work on Next Bean

Pick up the next ready bean and work on it.

## Steps

1. Pull the latest code with `git pull`. Beans live alongside the code, so this also syncs the latest bean state.
2. Branch on `$ARGUMENTS`:
   - **If a bean ID was provided** → follow "Targeted bean" below. Do not fall back to the ready queue.
   - **If no argument was provided** → follow "Pick from ready queue" below.

### Targeted bean (a specific ID was passed)

The user named this bean deliberately. Treat it as a hard constraint: never substitute a different bean, never auto-pick a dependency, never silently fall back to the queue. If the named bean cannot be worked right now, **stop and report** — let the user decide what to do.

1. Run `beans show <id>` to load the bean. If the ID does not exist, stop and tell the user.
2. Check status:
   - If `completed` or `scrapped` → **stop**. Report the status and ask whether the user meant a different bean or wants to reopen this one. Do not pick another bean.
   - If `in-progress` → **stop**. Report that the bean is already claimed (likely by another worker — check `git log .beans/<id>--*.md` for who) and ask whether to proceed anyway. Do not silently take it over.
   - If `draft` → **stop**. Drafts are deferred or unspecified work. Ask whether the user wants to promote it to `todo` first.
   - If `todo` and blocked → **stop**. Run `beans show <id>` to see blockers; list them and ask which one the user wants to work on. Do not auto-pick the dependency.
   - If `todo` and unblocked (appears in `beans list --ready`) → continue.
3. Show the bean's details with `beans show <id>`.
4. Run `beans update <id> --status=in-progress` to claim it. This writes the change to `.beans/<id>--*.md`.
5. Commit and push the claim so other workers see it as taken: `git add .beans/<id>--*.md && git commit -m "claim <id>" && git push`.
6. Implement the bean, following any applicable project skills and conventions.

### Pick from ready queue (no argument)

1. Run `beans list --ready` to find beans with no blockers.
2. If no beans are ready, inform the user and stop.
3. Select the highest-priority bean (`critical` > `high` > `normal` > `low` > `deferred`).
4. Show the bean's details with `beans show <id>`.
5. Run `beans update <id> --status=in-progress` to claim it.
6. Commit and push the claim: `git add .beans/<id>--*.md && git commit -m "claim <id>" && git push`.
7. Implement the bean, following any applicable project skills and conventions.

## When Complete

1. Ensure all unit tests/specs pass.
2. If the bean references approved feature scenarios, ensure those scenarios pass and are not pending.
3. If the bean references approved feature scenarios, do not move the bean past `in-progress` while those scenarios remain pending.
4. If the bean references approved feature scenarios, do not change approved feature direction without review; if feature text and implementation diverge, stop and raise it.
5. Check boot files (`AGENTS.md`, `CLAUDE.md`, etc.) for the project's completion convention. If the project uses a separate `/verify` flow, mark the bean `completed` with the `unverified` tag for the verifier to pick up: `beans update <id> --status=completed --tag=unverified`. Otherwise, default to plain `beans update <id> --status=completed`.
6. Commit the bean update together with the code changes in one commit, with a descriptive message. Push.

## Common Traps

### Premature close

Beans get closed before the work is actually done. Always verify with the project's feature/test runner before trusting "it's done." Always check that `@wip` was removed AND the scenarios pass. If the project uses a verifier (separate `/verify` flow), mark the bean `completed` with `tag=unverified` rather than removing the tag — let the verifier confirm and remove the tag.

### Multi-worker collisions

Beans live in version control. If another worker claimed the bean while you were reading it, your `git push` will be rejected. Resolve the same way as any push conflict: `git pull --rebase`, see the new bean state, decide whether to back off (pick a different bean) or continue. Do not force-push.

### Status semantics, briefly

- `todo` — not started; appears in `beans list --ready` if no blockers.
- `in-progress` — actively being worked.
- `completed` — done. With `tag=unverified`, awaiting `/verify`.
- `scrapped` — decided not to do; preserved as project memory.
- `draft` — not actionable yet; ideas, deferred work (with `tag=deferred`), unspecified scope.

## Arguments

$ARGUMENTS - Optional: a specific bean ID. When provided, this is a hard constraint: work that exact bean or stop. Never substitute a different bean (not its dependencies, not the next ready bean, not anything else). If it can't be worked, surface the reason to the user and let them decide.
