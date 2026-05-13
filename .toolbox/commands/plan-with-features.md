---
name: plan-with-features
description: Plan work with Gherkin feature specification using gherclj. Use when the user says "/plan-with-features" or asks to plan with acceptance tests.
user-invocable: true
---

# Plan with Features

Extends the [/plan](plan.md) workflow with Gherkin feature specification using [gherclj](https://github.com/slagyr/gherclj).

Follow all instructions from `/plan`. This command adds a **feature-first** workflow: before creating beans, specify the desired behavior as Gherkin feature files.

Load the [`gherclj`](https://raw.githubusercontent.com/slagyr/gherclj/master/SKILL.md) skill for writing good scenarios.

## Feature-First Workflow

Replace steps 5-7 from `/plan` with:

5. **Draft features** — Present proposed `.feature` scenarios as text in the conversation. Do NOT write files yet.
6. **Refine** — Iterate based on feedback until the user approves.
7. **Write & commit** — Write the approved `.feature` file with `@wip` tag, commit and push.
8. **Create beans** — Create implementation beans that reference the feature file.

## @wip Convention

- New features start with `@wip` at the top of the file.
- `@wip` scenarios are excluded from generation — they produce no test code.
- Removing `@wip` is part of the implementation bean's definition of done.
- A bean is NOT complete if its feature file still has `@wip`.

## Modifying Existing Features

- Add new scenarios with `@wip` tag on the individual scenario, not the whole file.
- When changing expected behavior, update the scenario's expected output — the implementation bean should make it pass.
- When removing a scenario, remove it from the feature file and verify tests still pass.
- Commit feature file changes before creating the implementation bean — the failing/pending test is the spec.

## Beans and Features

- Every implementation bean should reference the feature file it implements.
- Include the `file:line` selector for each scenario the bean implements; if the bean covers the whole feature, include the bare `.feature` path too, so the implementer can run either targeted scenarios or the entire feature with `gherclj`.
- One bean per feature file is typical, but large features may be split across beans.
- Infrastructure beans (parser changes, framework updates) may block feature implementation beans.
- Feature files are the spec; bean descriptions cover implementation approach.

## Read First

Before drafting features, run `gherclj steps` to see all registered steps organized by Given/When/Then, with their phrases and source locations. This is the authoritative way to know what steps already exist — reuse them before proposing new ones. Use `gherclj match "<phrase>"` to verify that a proposed phrase will route to the intended step before committing it to a feature file. Also study existing `.feature` files to match the project's scenario style.

## Planning Workflow Discipline

### Feature-first, always

Gherkin scenarios come before beans. Draft scenarios in chat as text, iterate until approved, write to files with `@wip`, commit, THEN create the bean. Never write production code before scenarios exist.

### Beans without scenarios stay draft

A bean can be *created* in `draft` status (title, rough description, design notes) to capture intent. But it only becomes eligible for the active queue — promoted to `todo`, pickable by a worker — after its Gherkin scenarios are drafted, committed with `@wip`, and referenced from the bean description or acceptance. A bean in `todo` with no scenarios has no acceptance contract; workers can't verify they're done.

### Scenarios force abstractions

When you want a real seam (not cargo-culted), write a scenario that exercises it from the outside. An in-memory transport scenario forces the transport protocol to be real. A compaction scenario across multiple channels forces the chat flow to be channel-agnostic. Pick the scenario that would break if someone took the lazy path.

### `@wip` is the implementation contract

When you commit scenarios with `@wip`, that's the spec for the implementation bean. The bean's Definition of Done is "remove `@wip` and the scenario passes." Don't change the scenario semantics during implementation — if the implementation and scenario diverge, raise the mismatch.

### Acceptance criteria must be runnable

Every bean with `@wip` scenarios should include exact `gherclj features/path/to/file.feature:LINE` commands (or the project's wrapper equivalent — `bb features ...`, `lein features ...`, etc.). Don't write "verify scenario X passes" — write the exact command. Workers will copy-paste.

### Existing scenarios that change behavior get `@wip`

When an existing scenario's expected behavior changes (not adding a new scenario, changing an existing one), mark the whole scenario `@wip` until the implementation catches up. Don't leave passing-but-wrong scenarios. Failing scenarios ARE the spec for the next bean.

### Draft in small chunks

When drafting scenarios (or any dense code), show one or two at a time — not a whole feature file. Long blocks overflow a terminal pane. Draft → confirm → move on. A rejected batch of twelve scenarios is worse than twelve single scenarios reviewed one-by-one.

### Reuse before re-implement

Before creating a second storage file, step-def, dispatch layer, or helper, check if the existing surface can be extended:

- generic HTTP assertions → don't create per-adapter assertions
- generic EDN inspection → don't create per-entity assertions
- one helper, many fields → don't reimplement per field

"Could this be the same thing with a slightly different name?" is almost always yes.

## Common Traps

### Scoping too broadly

Epic beans are fine. "Let me implement this in one bean" when the scope is 200 LoC across 5 files is not. If a bean touches a coupling point (a god namespace, a high-traffic dispatch function), it's probably too big. Split before committing.

## Final Advice

Feature-first, tables over prose, forcing abstractions through tests, concise communication, and trust that failing `@wip` scenarios are the best documentation of what's next. The rest follows.

## Reminder

You are READ-ONLY on code files. Feature files and beans are your output. If you accidentally modify source files, immediately revert with `git checkout`.
