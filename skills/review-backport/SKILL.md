---
name: review-backport
description: >
  Reviews backport pull requests against their upstream counterparts on GitHub.
  Checks that diffs match, commits are proper cherry-picks, and the linked backport
  issue references the upstream PR. Produces a concise status report.
  Use this skill whenever the user says "review backport", "check backport PR", or
  mentions reviewing a PR in the context of backporting. Trigger on any phrase like
  "review backport #123", "check backport PR 45", or "can you review this backport".
---

## Overview

You are reviewing a **backport pull request**: a PR that cherry-picks commits from an
upstream repository into a maintenance/LTS branch. Verify three things:

1. **Diff match** — the changes in the backport PR match the upstream PR
2. **Commit integrity** — every commit is a cherry-pick of an upstream commit, covering all upstream PR commits
3. **Issue linkage** — the backport issue references the upstream PR or one of its commits

---

## Default repositories

- **Backport repo**: `graalvm/graalvm-community-jdk21u`
- **Upstream repo**: `oracle/graal`

Override if the user specifies different repos.

---

## Known structural differences (not failures)

For the default repo pair, these diff differences are **expected and not failures** — flag them as ⚠️ with a brief note, not ❌:

- **Package namespace**: backport uses `org.graalvm.compiler`, upstream uses `jdk.graal.compiler`
- **Module name / file path**: backport uses `jdk.internal.vm.compiler` / `src/jdk.internal.vm.compiler/src/org/graalvm/compiler`, upstream uses `jdk.graal.compiler` / `src/jdk.graal.compiler/src/jdk/graal/compiler`
- **Copyright year**: the "before" year may differ because the two repos have independent histories

If the only differences fall into these categories, the diff check is ⚠️ (expected structural differences) rather than ❌.

---

## Workflow

Run the script from `scripts/review-backport.sh` (relative to this skill directory):

```bash
SKILL_DIR=$(dirname "$(realpath "$0")")   # or set to the skill directory explicitly
bash $SKILL_DIR/scripts/review-backport.sh <PR> [BACKPORT_REPO] [UPSTREAM_REPO]
```

The script:
1. Fetches all data upfront into a temp dir (`$WORK`) and prints the path.
2. Normalizes and compares the full PR diffs.
3. If diffs differ, falls back to commit-by-commit comparison.
4. Checks that every backport commit is a valid cherry-pick of an upstream commit and that all upstream commits are covered.
5. Checks that the linked backport issue references the upstream PR or a cherry-picked commit.

Intermediate files are left in `$WORK` for manual inspection when there are failures.

---

## Interpreting output

The script prints one status line per check. Translate to the final report format:

```
Backport <backport-repo>#<PR> ← <upstream-repo>#<upstream-PR>

✅ Diffs match
⚠️  Diff: expected structural differences (package namespace org.graalvm.compiler→jdk.graal.compiler, copyright year)
❌ Diff: substantive differences — see commit-by-commit breakdown below
✅ Commits: N/N cherry-picks valid (<sha7> ← <upstream-sha7>, ...)
❌ Commits: <sha7> is not a cherry-pick
❌ Commits: upstream commits not covered: [<sha7>, ...]
✅ Issue #N references upstream PR/commit
❌ Issue #N does not reference upstream PR or any cherry-picked commit
```

Only add a "Details" section if there are ❌ items — and keep it to the facts needed to act on the failure (which commit, which SHA, what was found vs. expected). No tables, no repeated information.

Differences that are only structural (package namespace, module path, copyright year) should be reported as ⚠️ not ❌ even if the script outputs ❌ for the diff step.
