# AGENTS.md

Micah's AI assistant management tools — isaac.comm.discord module.

## Toolbox

This project uses [toolbox](https://raw.githubusercontent.com/slagyr/toolbox/main/SKILL.md)
to manage agent components. If `.toolbox/` doesn't exist, fetch the toolbox
SKILL.md from the URL above and follow its instructions. Once bootstrapped:

- **Skills:** Load from `.toolbox/skills/{name}/SKILL.md` when their descriptions match the task at hand.
- **Commands:** When the user invokes a command by name (e.g., "/work"), read and follow `.toolbox/commands/{name}.md`.

### Skills

- [tdd](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/tdd/SKILL.md)
- [refactor](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/refactor/SKILL.md)
- [smells](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/smells/SKILL.md)
- [gherclj](https://raw.githubusercontent.com/slagyr/gherclj/refs/heads/master/SKILL.md)
- [gherkin](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/gherkin/SKILL.md)
- [clojure](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/clojure/SKILL.md)

### Commands

- [work](https://raw.githubusercontent.com/slagyr/agent-lib/main/commands/work.md)
- [verify](https://raw.githubusercontent.com/slagyr/agent-lib/main/commands/verify.md)

## Project Overview

This repo is the Discord comm module for [Isaac](https://github.com/slagyr/isaac).
It registers as an Isaac module via `src/isaac-manifest.edn` and provides a
`:discord` comm channel that bridges Discord gateway events to Isaac sessions.

**Isaac dependency:** `deps.edn` and `bb.edn` reference Isaac core via a git dep.
Update the `:git/sha` when pulling in Isaac changes.

## Testing

```bash
bb spec       # Run Clojure specs
bb features   # Run Gherkin feature scenarios (excludes @slow and @wip)
bb ci         # Run both
```

Feature step definitions live in `spec/isaac/features/steps/discord.clj` and
pull in the shared Isaac step namespaces via the git dep.

### Testing Discipline

- No production code without a failing unit test first (TDD)
- Every `src/` namespace must have a corresponding `spec/` file
- Run `bb ci` before pushing — both spec and features must be green

## Isaac Core Dependency

The module depends on Isaac core APIs:

- `isaac.api` — session creation and turn dispatch
- `isaac.comm` — comm protocol (on-turn-start, on-turn-end, send!)
- `isaac.logger` — structured logging
- `isaac.fs` — filesystem abstraction
- `isaac.util.ws-client` — WebSocket client utilities
- `isaac.comm.delivery.queue` — message delivery queue

To update the Isaac SHA after new Isaac commits:

```bash
# In deps.edn and bb.edn, update :git/sha to the new HEAD
git -C /path/to/isaac rev-parse HEAD
```
