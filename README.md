# GraalVM Community Maintainer Scripts

A collection of [JBang](https://www.jbang.dev/) scripts and skills for
maintaining GraalVM community repositories — specifically
[graalvm/graalvm-community-jdk25u](https://github.com/graalvm/graalvm-community-jdk25u)
and similar downstream forks of [oracle/graal](https://github.com/oracle/graal).

## Prerequisites

- [JBang](https://www.jbang.dev/) — for the `.java` scripts
- [GitHub CLI (`gh`)](https://cli.github.com/) — for the shell scripts
- A GitHub token with read access to both the downstream and upstream repositories

## Scripts

### `review.java` — Review a backport PR

Reviews one or all open backport PRs against their upstream counterparts.

```
jbang review.java [-p <PR_NUMBER>] [options]
```

| Option | Default | Description |
|--------|---------|-------------|
| `-r`, `--repository` | `graalvm/graalvm-community-jdk21u` | Downstream repository |
| `-ur`, `--upstream-repository` | `oracle/graal` | Upstream repository |
| `-p`, `--pr` | *(all open PRs)* | Pull request number to review |
| `-t`, `--token` | *(`~/.github` or `GITHUB_OAUTH` env var)* | GitHub OAuth token |
| `-v`, `--verbose` | `false` | Show a `git diff` of any patch mismatches |

For each PR, three checks are performed:

1. **Diff match** — the normalized PR diff matches the upstream PR diff.  
   If the full-PR diffs differ, falls back to a commit-by-commit comparison.
2. **Commit integrity** — every commit carries a `(cherry picked from commit …)` trailer
   referencing a commit in the upstream PR, and all upstream commits are covered.
3. **Issue linkage** — the closing backport issue references the upstream PR URL or
   a cherry-picked commit SHA.

Package/path renames (`org.graalvm.compiler` → `jdk.graal.compiler`, etc.) are
normalized before comparison so they are not flagged as mismatches.

**Example:**
```bash
# Review a specific PR
jbang review.java -r graalvm/graalvm-community-jdk25u -p 123 -t "$GITHUB_TOKEN"

# Review all open PRs
jbang review.java -r graalvm/graalvm-community-jdk25u -t "$GITHUB_TOKEN"
```

---

### `prepare-sync-pr.java` — Prepare an upstream sync PR description

Analyzes a completed merge of an upstream release branch and generates a
categorized PR description.

```
jbang prepare-sync-pr.java [-r DOWNSTREAM_REPO] [-ur UPSTREAM_REPO] [-C DIR] [-c] [options]
```

| Option | Default | Description |
|--------|---------|-------------|
| `-r`, `--repository` | *(derived from `origin` remote)* | Downstream repository (`owner/name`) |
| `-ur`, `--upstream-repository` | `oracle/graal` | Upstream repository |
| `-C`, `--directory` | `.` | Path to the downstream git working directory |
| `-t`, `--token` | *(`~/.github` or `GITHUB_OAUTH` env var)* | GitHub OAuth token |
| `-v`, `--verbose` | `false` | Enable verbose output |
| `-c`, `--create` | `false` | Push the branch and open a pre-filled PR-create form in the browser |

**Precondition:** `HEAD` must already be the merge commit
(`git merge upstream/release/graal-vm/X.Y` done by the maintainer beforehand).

The script categorizes every upstream PR/commit introduced by the merge into:

| Category | Meaning |
|----------|---------|
| Already backported/merged | Cherry-pick trailer found in local history, or a closed/merged downstream issue or PR references the upstream PR |
| Partially backported | Some but not all upstream PR commits are in downstream history |
| Known but not backported | An open downstream issue or PR tracks this upstream PR |
| Previously unknown | No downstream tracking found |
| Other changes | Bare commits (CI/mx, labsJDK/JVMCI, BasedOnJDK, Node.js, etc.) grouped by keyword |

Open backport issues that the sync satisfies are emitted as `Closes #N` keywords
so GitHub auto-closes them on merge. Superseded open backport PRs are listed
separately for manual closure.

**Example:**
```bash
cd ~/repos/graalvm-community-jdk21u
git fetch upstream
git merge upstream/release/graal-vm/25.0 # or a specific tag's SHA

jbang /path/to/prepare-sync-pr.java -t "$GITHUB_TOKEN" -r=graalvm/graalvm-community-jdk25u -ur=oracle/graal
# with --create to also push and open the GitHub PR form on a browser:
jbang /path/to/prepare-sync-pr.java -t "$GITHUB_TOKEN" --create
```

---

## Agent Skills

The `skills/` directory contains agent skill definitions that wrap the scripts
above for use in AI-assisted workflows.

### `skills/review-backport/`

Skill: `review-backport`

Trigger phrases: "review backport #N", "check backport PR", etc.

Uses the `gh` CLI to fetch all data upfront into a temp directory, then
normalizes and compares diffs, checks commit integrity, and checks issue linkage.
Intermediate files are retained in the work directory for manual inspection on failures.

### `skills/prepare-sync-pr/`

Skill: `prepare-sync-pr` — wraps [`prepare-sync-pr.java`](prepare-sync-pr.java).

Trigger phrases: "prepare sync PR", "upstream sync PR body", "categorize the upstream merge", etc.

Invokes `prepare-sync-pr.java` via `jbang`. Requires `HEAD` to already be the merge commit.

---

## Dependency Management

[Renovate](https://docs.renovatebot.com/) is configured via [`.github/renovate.json`](.github/renovate.json)
to keep the JBang `//DEPS` declarations in the `.java` scripts up to date automatically.
