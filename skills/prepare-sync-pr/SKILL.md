---
name: prepare-sync-pr
description: >
  Prepares the PR description for an "upstream sync" PR — a merge of an upstream
  release branch (e.g. oracle/graal release/graal-vm/25.0) into a GraalVM
  community downstream repo's master. The merge itself is done manually by the
  maintainer beforehand; this skill analyzes the resulting merge commit and
  categorizes every newly-merged upstream PR/commit into: already backported,
  known open backport, previously unknown, or other (non-PR) changes — matching
  the structure used in past sync PRs like
  https://github.com/graalvm/graalvm-community-jdk25u/pull/57.
  Use this skill whenever the user says "prepare sync PR", "upstream sync PR
  body", "categorize the upstream merge", or asks for help writing the
  description for a merge of an upstream release branch into a downstream repo.
---

## Overview

An "upstream sync" PR merges a range of commits from `oracle/graal`'s release
branch into a GraalVM community downstream repo (e.g. `graalvm-community-jdk21u`,
`graalvm-community-jdk25u`). The maintainer performs the merge locally
(`git fetch upstream; git merge upstream/release/graal-vm/X.Y`) and resolves any
conflicts themselves — **by default this skill only reads git state and opens
nothing** (the opt-in `--create` flag is the sole exception; see "Creating the
PR"). Its main job is to read the resulting merge commit and produce the
categorized PR description, since manually cross-referencing dozens of upstream
PRs against backport tracking issues is tedious and error-prone.

Each upstream PR pulled in by the merge gets bucketed into one of:

1. **Already backported/merged in this repository** — a downstream PR already
   contains these changes (found via a cherry-pick trailer in local history, or
   a closed/merged issue or PR labeled `backport` referencing the upstream PR).
2. **Known but not backported/merged in this repository** — an open downstream
   issue or PR labeled `backport` already tracks it.
3. **Previously unknown backports** — no tracking found at all; this is new
   information for the maintainer.
4. **Other changes not mapping to upstream PRs** — bare commits (CI/mx tweaks,
   labsJDK/JVMCI bumps, version annotation updates, etc.) that never went
   through a `oracle/graal` PR, grouped by keyword heuristics.

## Preconditions

Run this from inside the downstream repo's git working directory, with `HEAD`
already being the merge commit (two parents: the prior downstream `master`,
and the upstream release branch tip). If the user hasn't done the merge yet,
tell them to do that first — don't attempt it yourself.

## Workflow

The tool is a `jbang` script living next to `backport.java`/`review.java` at the
repo root:

```bash
SKILL_DIR=$(dirname "$(realpath "$0")")   # or the skill directory explicitly
jbang "$SKILL_DIR/../../prepare-sync-pr.java" [-r DOWNSTREAM_REPO] [-ur UPSTREAM_REPO] [-C DIR]
```

- `-r` / `--repository` defaults to the `owner/name` parsed from the working
  directory's `origin` remote.
- `-ur` / `--upstream-repository` defaults to `oracle/graal`.
- `-C` / `--directory` is the downstream git working directory (default `.`).
- `-c` / `--create` additionally pushes the current branch and opens a
  pre-filled PR-create form in a browser (off by default — see "Creating the
  PR" below).

The tool is fully deterministic: it needs only `git` and `jbang`, involves no
LLM, and uses JGit + the GitHub API library directly (no `gh`/`jq`). You can run
it directly without invoking this skill at all; the skill exists mainly so the
result gets a human review pass.

The tool:
1. Reads `HEAD`'s two parents (via JGit) to get the exact set of new upstream
   commits.
2. Maps each commit to its originating upstream PR by **title** — not by SHA
   (see "How matching works" below) — or leaves it "bare" if no title matches.
3. For each upstream PR, checks local git history for a cherry-pick trailer
   first (cheap, no API calls), then falls back to searching the downstream
   repo for any issue/PR referencing the upstream PR URL in its body.
4. For bare commits, checks the same way, then groups the unmatched remainder
   by keyword (labsJDK/JVMCI, CI/mx, Node.js, BasedOnJDK annotations,
   Darwin/macOS, or Uncategorized).
5. Prints the categorized markdown body to stdout and saves it to `pr-body.md`
   in a temp work dir (path printed to stderr) for inspection.

## How matching works (and its limits)

`oracle/graal`'s release branches are produced by an internal mirroring process,
so a release-branch commit has a **different SHA** than the corresponding public
GitHub PR commit and carries a `PullRequest: graal/NNNNN` *internal*-tracker
footer (not a GitHub link). Commit-SHA → GitHub-PR lookup therefore does not
work here. Instead the script recovers the originating PR from the commit
subject, which follows the convention `[GR-nnnnn] Backport to X.Y: <original PR
title>`, by searching upstream for that exact title.

Consequences worth knowing when you review the draft:

- **Commits without a `[GR-nnnnn]` subject prefix** can't be title-matched and
  fall into "Other changes" even if they did originate from an upstream PR
  (e.g. a GC refactoring series merged as individual raw commits). Scan that
  section for anything that looks like a real feature/fix rather than a
  bump/CI tweak.
- **Ambiguous titles** (the same title matching more than one upstream PR) are
  listed in a separate "Needs manual check" section rather than guessed.
- **A backport PR's body may cite the *original* PR number** rather than the
  release-branch merge PR number; the script reports whichever PR's title the
  commit matched, so the exact number can differ from a hand-written entry
  while still pointing at the same change.
- The downstream tracker search matches any issue/PR **body** that references
  the upstream PR URL. A *previous* sync PR that merely listed an upstream PR
  as "unknown" can therefore look like a backport. Sanity-check "already
  backported with ..." links that point at another sync/merge PR.

## After running

The generated body is a **draft, not a final answer** — the keyword grouping
for "Other changes" and anything landing in "Previously unknown backports" is
a heuristic and deserves a human read-through:

- Merge or relabel keyword groups that don't fit well (e.g. split
  "Uncategorized" further, as was done manually in PR #57 for things like
  "Removal of darwin x86_64 support").
- For "Previously unknown backports", it can be worth a quick check of the
  upstream PR body/comments for hints that a backport is already planned
  (e.g. "will be backported" mentioned in review comments), and noting that.
- The intro sentence's upstream branch name is auto-derived from the merge
  commit subject (`Merge upstream 'release/graal-vm/X.Y' branch ...`); it falls
  back to a `<UPSTREAM_BRANCH>` placeholder only if that can't be parsed.

## Creating the PR

Running with `-c` / `--create` pushes the current branch (via `git push`) and
opens GitHub's PR-create form in a browser with the title and body pre-filled
(the tool builds the compare URL and opens it with `Desktop.browse`):

```bash
jbang "$SKILL_DIR/../../prepare-sync-pr.java" --create
```

It does **not** submit the PR — the maintainer reviews the pre-filled form and
clicks "Create". If the environment is headless, or the body exceeds GitHub's
prefill URL limit (~8KB), the browser step is skipped and the tool prints the
compare URL and the saved body-file path to paste manually.

When operating on the maintainer's behalf, don't pass `--create` yourself without
explicit confirmation — default to printing the body and handing it back. The
maintainer running the tool directly is of course free to use `--create`.
