---
name: toolbox
description: Manages component dependencies (skills, commands, rules, modes, agents) for a project. Parses component URLs from the project's boot file, fetches them into a local .toolbox/ cache, tracks freshness, and updates on demand. Use this skill when a project's boot file declares components via URL.
---

# Toolbox — Component Management

Resolve, cache, and update component dependencies declared in a project's boot file.

## When This Skill Applies

When you land in a project and the boot file contains a `## Toolbox` section with a link to this skill (toolbox), follow the procedure below to ensure all declared components are available locally before doing any work.

## Component Types

Toolbox manages different types of agent components. Each type has its own subsection under `## Toolbox` in the boot file, its own cache subdirectory, and its own entry in the manifest.

### Skills

Skills are instruction sets that teach agents how to perform specific tasks. A skill is a directory with a `SKILL.md` entry point and optional reference files.

- **Boot file section:** `### Skills` (under `## Toolbox`)
- **Cache location:** `.toolbox/skills/{name}/SKILL.md`
- **Reference discovery:** Yes — relative markdown links in `SKILL.md` are fetched automatically.

### Commands

Commands are single-file agent instructions invoked by name (e.g., `/test`, `/deploy`). A command is a single markdown file.

- **Boot file section:** `### Commands` (under `## Toolbox`)
- **Cache location:** `.toolbox/commands/{name}.md`
- **Reference discovery:** No — commands are single files.

### Rules

Rules are behavioral constraints that modify how the agent operates (e.g., "always write tests", "never push to main"). A rule is a single markdown file.

- **Boot file section:** `### Rules` (under `## Toolbox`)
- **Cache location:** `.toolbox/rules/{name}.md`
- **Reference discovery:** No — rules are single files.

### Modes

Modes are operating profiles that configure the agent's behavior for a specific workflow (e.g., code-review, architect, planner). A mode is a single markdown file.

- **Boot file section:** `### Modes` (under `## Toolbox`)
- **Cache location:** `.toolbox/modes/{name}.md`
- **Reference discovery:** No — modes are single files.

### Agents

Agents are full persona definitions with system prompts and tool configurations. An agent is a single markdown file.

- **Boot file section:** `### Agents` (under `## Toolbox`)
- **Cache location:** `.toolbox/agents/{name}.md`
- **Reference discovery:** No — agents are single files.

## How Components Are Declared

Components are declared in the project's boot file under a `## Toolbox` section. The boot file is whatever file the agent reads on startup — `AGENTS.md`, `CLAUDE.md`, or any platform-specific equivalent. Each component is a markdown link in a bullet list under its type subsection:

```markdown
## Toolbox

This project uses [toolbox](https://raw.githubusercontent.com/slagyr/toolbox/main/SKILL.md)
to manage agent components. If `.toolbox/` doesn't exist, fetch the toolbox
SKILL.md from the URL above and follow its instructions. Once bootstrapped:

- **Skills:** Load from `.toolbox/skills/{name}/SKILL.md` when their descriptions match the task at hand.
- **Commands:** When the user invokes a command by name (e.g., "/test"), read and follow `.toolbox/commands/{name}.md`.
- **Rules:** Read and apply all rules from `.toolbox/rules/` at session start.
- **Modes:** When the user requests a mode by name, read and apply `.toolbox/modes/{name}.md`.
- **Agents:** When the user requests an agent by name, read and apply `.toolbox/agents/{name}.md`.

### Skills

- [tdd](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/tdd/SKILL.md)
- [braids](https://raw.githubusercontent.com/slagyr/braids/main/braids/SKILL.md)

### Commands

- [test](https://raw.githubusercontent.com/slagyr/agent-lib/main/commands/test.md)
- [deploy](https://raw.githubusercontent.com/slagyr/agent-lib/main/commands/deploy.md)

### Rules

- [no-force-push](https://raw.githubusercontent.com/slagyr/agent-lib/main/rules/no-force-push.md)

### Modes

- [architect](https://raw.githubusercontent.com/slagyr/agent-lib/main/modes/architect.md)

### Agents

- [reviewer](https://raw.githubusercontent.com/slagyr/agent-lib/main/agents/reviewer.md)
```

- The **link text** is the component name.
- The **URL** points to the component's entry point.
- Both `https://` and `file://` URLs are supported.
- Component names must be unique within their type. If duplicates are found, warn the user and use the last declaration.

## Procedure

### 1. Check for Cached Components

Look for `.toolbox/toolbox.json` in the project root.

- **If it exists**: components have been fetched before. Read cached components and proceed (see §6). Check for updates only when the user asks (see §4).
- **If it doesn't exist**: bootstrap (see §2).

### 2. Bootstrap (First Run)

When `.toolbox/toolbox.json` is missing:

1. Create the `.toolbox/` directory in the project root.
2. Parse the `## Toolbox` section of the boot file for component subsections (`### Skills`, `### Commands`, `### Rules`, `### Modes`, `### Agents`). Extract each `[name](url)` pair.
3. For each declared skill (including toolbox itself — use the already-fetched copy rather than re-fetching):
   a. Fetch `SKILL.md` from the skill's URL.
   b. Discover reference files by parsing relative markdown links in `SKILL.md` — patterns like `[text](references/foo.md)` or `[text](some/path.md)`. Only include links to relative paths (not absolute URLs or anchors).
   c. Compute the base URL by removing `SKILL.md` from the skill's URL. Fetch each discovered reference file relative to that base URL.
   d. Write all fetched files into `.toolbox/skills/{name}/`, preserving directory structure.
   e. Compute a SHA-256 hash covering all fetched files (concatenate file contents in sorted order by path, then hash).
4. For each single-file component (commands, rules, modes, agents):
   a. Fetch the file from the URL.
   b. Write it to `.toolbox/{type}/{name}.md`.
   c. Compute the SHA-256 hash of the fetched content.
5. Write `.toolbox/toolbox.json` with the manifest (see §3).
6. Ensure `.toolbox/` is listed in the project's `.gitignore`. If not, add it.

### 3. The Manifest — `.toolbox/toolbox.json`

The manifest tracks all cached components, their source URLs, fetched files, and content hashes for change detection. Example (hashes and timestamps are illustrative):

```json
{
  "skills": {
    "toolbox": {
      "url": "https://raw.githubusercontent.com/slagyr/toolbox/main/SKILL.md",
      "fetched_at": "2026-03-06T12:00:00Z",
      "sha256": "a1b2c3d4e5f6...",
      "files": ["SKILL.md"]
    },
    "tdd": {
      "url": "https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/tdd/SKILL.md",
      "fetched_at": "2026-03-06T12:00:00Z",
      "sha256": "f6e5d4c3b2a1...",
      "files": ["SKILL.md"]
    },
    "braids": {
      "url": "https://raw.githubusercontent.com/slagyr/braids/main/braids/SKILL.md",
      "fetched_at": "2026-03-06T12:00:00Z",
      "sha256": "1a2b3c4d5e6f...",
      "files": [
        "SKILL.md",
        "references/worker.md",
        "references/orchestrator.md",
        "references/project-creation.md",
        "references/init.md",
        "references/migration.md",
        "references/agents-template.md",
        "references/coordinator-constraint-snippet.md",
        "references/worker-agent-template.md"
      ]
    }
  },
  "commands": {
    "test": {
      "url": "https://raw.githubusercontent.com/slagyr/agent-lib/main/commands/test.md",
      "fetched_at": "2026-03-06T12:00:00Z",
      "sha256": "d4e5f6a1b2c3...",
      "files": ["test.md"]
    }
  },
  "rules": {
    "no-force-push": {
      "url": "https://raw.githubusercontent.com/slagyr/agent-lib/main/rules/no-force-push.md",
      "fetched_at": "2026-03-06T12:00:00Z",
      "sha256": "c3d4e5f6a1b2...",
      "files": ["no-force-push.md"]
    }
  },
  "modes": {
    "architect": {
      "url": "https://raw.githubusercontent.com/slagyr/agent-lib/main/modes/architect.md",
      "fetched_at": "2026-03-06T12:00:00Z",
      "sha256": "e5f6a1b2c3d4...",
      "files": ["architect.md"]
    }
  },
  "agents": {
    "reviewer": {
      "url": "https://raw.githubusercontent.com/slagyr/agent-lib/main/agents/reviewer.md",
      "fetched_at": "2026-03-06T12:00:00Z",
      "sha256": "a1b2c3d4e5f6...",
      "files": ["reviewer.md"]
    }
  }
}
```

**Fields:**

| Field | Description |
|-------|-------------|
| `skills` | Map of skill name → skill entry. |
| `commands` | Map of command name → command entry. |
| `rules` | Map of rule name → rule entry. |
| `modes` | Map of mode name → mode entry. |
| `agents` | Map of agent name → agent entry. |
| `{type}.{name}.url` | The URL from which the component was fetched. |
| `{type}.{name}.fetched_at` | ISO 8601 timestamp of when the component was last fetched. |
| `{type}.{name}.sha256` | SHA-256 hash covering all of the component's files at fetch time. Computed by concatenating file contents in sorted order by path, then hashing. Used to detect remote changes. |
| `{type}.{name}.files` | List of all files cached for this component, relative to its cache directory. |

### 4. Check for Updates

Toolbox detects updates by comparing content, not by time. Each component's `sha256` in the manifest is the hash of all its files at fetch time.

**On session start**, if cached components exist, proceed silently. Do not fetch anything automatically — the cached versions are ready to use.

**When the user asks** (e.g., "check for updates", "are my skills up to date?"):

1. For each component in the manifest, fetch all files from the URL.
2. Compute the SHA-256 hash covering all fetched files (same method as bootstrap).
3. Compare to the stored `sha256` in the manifest.
4. Report results:
   ```
   Component updates available:
     Skills:
       - braids (changed)
       - tdd (up to date)
     Commands:
       - test (up to date)
     Rules:
       - no-force-push (changed)
   Update? [y/n]
   ```
5. If the user confirms, proceed with §5 (Update) for the changed components.

### 5. Update Components

When the user asks to update (e.g., "update skills", "refresh components"):

1. Re-parse the boot file for current component declarations. This catches added or removed components.
2. For each declared component:
   a. Re-fetch all files from the URL.
   b. For skills, re-discover and fetch reference files.
   c. Overwrite the cached files.
   d. Update `fetched_at` and `sha256` in the manifest.
3. Remove any cached components that are no longer declared in the boot file.
4. Write the updated `.toolbox/toolbox.json`.

### 6. Use Components

- **Skills:** Load from `.toolbox/skills/{name}/SKILL.md` when the skill's description matches the current task. References are at `.toolbox/skills/{name}/references/` (or wherever the skill's relative links point).
- **Commands:** When the user invokes a command by name (e.g., "/test"), read and follow `.toolbox/commands/{name}.md`.
- **Rules:** Read and apply all rules from `.toolbox/rules/` at session start. Rules are always active.
- **Modes:** When the user requests a mode by name, read and apply `.toolbox/modes/{name}.md`.
- **Agents:** When the user requests an agent by name, read and apply `.toolbox/agents/{name}.md`.

## URL Schemes

### `https://`

Fetch via HTTP GET. This is the primary use case for portable, published components.

For components hosted on GitHub, use `raw.githubusercontent.com` URLs:
```
https://raw.githubusercontent.com/{owner}/{repo}/{branch}/path/to/file.md
```

To pin a specific version, use a commit SHA instead of a branch name:
```
https://raw.githubusercontent.com/{owner}/{repo}/{sha}/path/to/file.md
```

**Private repos:** `raw.githubusercontent.com` does not serve files from private repositories without authentication. For private components, use `file://` URLs or a URL that includes an access token.

### `file://`

Copy from the local filesystem. Useful for:
- Components under active development
- Private components that won't be published
- Migration from filesystem-based references

Example:
```markdown
- [braids](file:///Users/micah/Projects/braids/braids/SKILL.md)
```

**Note:** `file://` URLs are not portable across machines. Use `https://` for components that need to work everywhere.

## Reference Discovery

When fetching a skill's `SKILL.md`, parse it for relative markdown links to discover supporting files.

**Match patterns:**
- `[text](references/foo.md)` — standard markdown link with relative path
- `[text](some/path.md)` — any relative path (no scheme, no leading `/`)

**Exclude:**
- Absolute URLs (`https://...`, `http://...`, `file://...`)
- Anchor links (`#section`)

**Resolve:** Given a skill URL like `https://example.com/skills/solid/SKILL.md`, the base URL is `https://example.com/skills/solid/`. A reference `references/tdd.md` resolves to `https://example.com/skills/solid/references/tdd.md`.

For `file://` URLs, the same logic applies using filesystem paths.

Reference discovery applies only to skills. All other component types are single files with no references.

## Error Handling

- **Fetch failure (single component):** If a component's URL returns an error (404, timeout, network unavailable), warn the user and skip that component. Do not block the entire bootstrap or update process.
- **Fetch failure (reference file):** If a reference file fails to fetch, warn the user and continue. The skill may still be usable without it.
- **No `## Toolbox` section:** If the boot file has no `## Toolbox` section, toolbox does not apply. Do nothing.
- **Invalid `file://` path:** If a `file://` path does not exist, treat it as a fetch failure — warn and skip.
- **General rule:** Never silently swallow errors. Always inform the user what failed and why.

## Limitations

- **No component dependencies.** Toolbox treats each component as independent. If skill A requires skill B, the skill author should note this in their `SKILL.md` description so that projects declare both explicitly.
