---
name: scrap
description: Use this skill when working with Speclj spec files — before refactoring, when assessing test quality, or when deciding whether a spec file needs structural improvement. SCRAP analyzes structural complexity, spec-design smells, and duplication pressure to tell an AI assistant whether, where, and how to refactor.
---

# SCRAP

SCRAP is a structural quality analyzer for Speclj specs. It measures structural complexity, weak-spec smells, and extraction pressure from duplicated test scaffolding. Its output is decision support for an AI assistant — not directives.

## When This Skill Applies

Use SCRAP whenever you:

- Are about to refactor a spec file and want to know if it's worth it
- Need to decide where structural problems are concentrated
- Are asked to improve test quality in a Speclj project
- Want to confirm that a refactor actually improved structure

Always run SCRAP before making structural changes to a spec file. Treat its output as guidance to confirm against the actual structure and intent before changing code.

## Running SCRAP

Use `bb` (faster startup, no JVM warm-up) or `clj` interchangeably:

```bash
bb scrap spec                    # bb — whole spec tree
clj -M:scrap spec                # clj equivalent
```

Full command reference (swap `bb scrap` / `clj -M:scrap` as preferred):

```bash
bb scrap spec                                      # whole spec tree
bb scrap spec/foo/bar_spec.clj                     # single file
bb scrap spec/foo                                  # subdirectory
bb scrap spec --verbose                            # full metric dump
bb scrap spec --json                               # machine-readable output
bb scrap spec --write-baseline                     # save a baseline to target/scrap/
bb scrap spec --compare target/scrap/spec.json     # compare against baseline
```

If no path is given, SCRAP defaults to `spec`.

## Reading the Output

### AI Actionability — the primary decision signal

`ai-actionability` is the main field to act on:

| Value | Meaning |
|---|---|
| `LEAVE_ALONE` | Do not change this file unless explicitly asked |
| `AUTO_TABLE_DRIVE` | Safe to consolidate repetitive low-complexity examples into table-driven checks |
| `AUTO_REFACTOR` | Safe to make local structural improvements in place |
| `MANUAL_SPLIT` | Do not do local cleanup first — split the file by responsibility and rerun |
| `REVIEW_FIRST` | Not stable; inspect the file shape before changing anything |

### Remediation Mode — where to start

| Value | Meaning |
|---|---|
| `STABLE` | Leave the file alone |
| `LOCAL` | Fix assertions, duplication, or oversized examples in place |
| `SPLIT` | Split the file by responsibility first, then rerun for local cleanup |

`SPLIT` means: do not polish isolated examples first. Identify coherent describe/context groups, split into narrower namespaces, then rerun SCRAP on the results.

### Refactoring Pressure

`refactor-pressure` and `ai-guidance` give a plain-language summary of what the file needs and why.

### Worst Examples

`worst-examples` lists the specific `it` blocks driving the score, with names and line ranges. Start there when making local improvements.

### Recommendations

Recommendation lines are ranked by confidence:

- `HIGH` — directly actionable: zero-assertion specs, oversized examples, obvious coverage matrices
- `MEDIUM` — likely cleanup: harmful duplication, heavy mocking
- `LOW` — broader design: splitting by responsibility

### Duplication Fields

| Field | Interpretation |
|---|---|
| `harmful-duplication-score` | Repeated setup, fixture, or arrange scaffolding that appears worth extracting |
| `coverage-matrix-candidates` | Repetitive low-complexity examples that are likely coverage tables — prefer table-driven specs over treating as bad duplication |
| `effective-duplication-score` | Net extraction benefit after accounting for helper cost — high means extraction is likely to pay off |
| `extraction-pressure-score` | Summary pressure from all duplicate clusters |

When `coverage-matrix-candidates` is high, prefer consolidating into table-driven specs rather than treating the repetition as a structural problem.

When `effective-duplication-score` is high, inspect the reported `it` names and line ranges first, then extract setup or helpers only for those clusters.

## Baselines and Comparison

Use baselines to confirm that a refactor actually improved structure:

```bash
bb scrap spec/foo/bar_spec.clj --write-baseline    # before refactoring
# ... refactor ...
bb scrap spec/foo/bar_spec.clj --compare target/scrap/bar_spec.json
```

The comparison report includes:

- `verdict`: `improved`, `worse`, `mixed`, or `unchanged`
- deltas for file score, average/max SCRAP, extraction pressure, harmful duplication, and helper-hidden complexity

If `verdict` is `worse` or `extraction-pressure-delta` went up, the refactor likely introduced or preserved duplication. Inspect `helper-hidden` and `harmful-duplication` deltas before keeping the change.

If the refactor made the file structurally worse, the text report says so explicitly and recommends reverting or simplifying helper extraction.

## Structural Checks

SCRAP validates common Speclj mistakes and reports them as `structure-errors`:

- `(it)` inside `(it)`
- `(describe)` inside `(describe)` or `(context)`
- `(before)`, `(with-stubs)`, `(around)`, `(with)`, or `(context)` inside `(it)`
- unclosed forms
- parse errors

Fix structural errors before interpreting SCRAP scores.
