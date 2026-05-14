#!/usr/bin/env bash
# Usage: review-backport.sh <PR> [BACKPORT_REPO] [UPSTREAM_REPO]
set -euo pipefail

PR=${1:?Usage: review-backport.sh <PR> [BACKPORT_REPO] [UPSTREAM_REPO]}
BACKPORT_REPO=${2:-graalvm/graalvm-community-jdk21u}
UPSTREAM_REPO=${3:-oracle/graal}
WORK=$(mktemp -d)

# ── Step 0: Fetch everything ────────────────────────────────────────────────

gh pr view "$PR" --repo "$BACKPORT_REPO" --json body,commits > "$WORK/backport-pr.json"
gh pr diff  "$PR" --repo "$BACKPORT_REPO"                    > "$WORK/backport.diff"

UPSTREAM_PR=$(jq -r '.body' "$WORK/backport-pr.json" \
  | grep -oP "(?<=${UPSTREAM_REPO}/pull/)\d+" | head -1)

if [[ -z "$UPSTREAM_PR" ]]; then
  echo "❌ No upstream PR URL found in backport PR description."
  exit 1
fi

gh pr view "$UPSTREAM_PR" --repo "$UPSTREAM_REPO" --json commits > "$WORK/upstream-pr.json"
gh pr diff  "$UPSTREAM_PR" --repo "$UPSTREAM_REPO"               > "$WORK/upstream.diff"

ISSUE=$(jq -r '.body' "$WORK/backport-pr.json" \
  | grep -oP "(?<=${BACKPORT_REPO}/issues/)\d+" | head -1)

if [[ -n "$ISSUE" ]]; then
  gh issue view "$ISSUE" --repo "$BACKPORT_REPO" --json body > "$WORK/issue.json"
fi

echo "Backport ${BACKPORT_REPO}#${PR} ← ${UPSTREAM_REPO}#${UPSTREAM_PR}"
echo "WORK=$WORK  ISSUE=$ISSUE"
echo

# ── Helpers ─────────────────────────────────────────────────────────────────

normalize() {
  grep '^[+-]' "$1" | grep -v '^---\|^+++' \
    | sed -e 's|org\.graalvm\.compiler|jdk.graal.compiler|g' \
          -e 's|jdk\.internal\.vm\.compiler|jdk.graal.compiler|g' \
          -e 's|org/graalvm/compiler|jdk/graal/compiler|g' \
          -e 's|jdk/internal/vm/compiler|jdk/graal/compiler|g'
}

# ── Step 1: Compare diffs ────────────────────────────────────────────────────

normalize "$WORK/backport.diff"  > "$WORK/backport-norm.diff"
normalize "$WORK/upstream.diff"  > "$WORK/upstream-norm.diff"
diff "$WORK/upstream-norm.diff" "$WORK/backport-norm.diff" > "$WORK/diff-result.txt" || true

if [[ ! -s "$WORK/diff-result.txt" ]]; then
  echo "✅ Diffs match"
else
  echo "❌ Diff: substantive differences (see $WORK/diff-result.txt)"
  echo
  echo "── Step 1b: Commit-by-commit diff comparison ──────────────────────────────"
  echo

  # Collect (backport-sha, upstream-sha) pairs from cherry-pick trailers
  while IFS=$'\t' read -r BSHA MBODY; do
    USHA=$(echo "$MBODY" | grep -oP '(?<=cherry picked from commit )[0-9a-f]+' | head -1)
    if [[ -z "$USHA" ]]; then
      echo "  ❌ $BSHA has no cherry-pick trailer"
      continue
    fi
    BSHA7=${BSHA:0:7}
    USHA7=${USHA:0:7}

    curl -sL "https://github.com/${BACKPORT_REPO}/commit/${BSHA}.patch" \
      > "$WORK/commit-backport-${BSHA7}.patch"
    curl -sL "https://github.com/${UPSTREAM_REPO}/commit/${USHA}.patch" \
      > "$WORK/commit-upstream-${USHA7}.patch"

    normalize "$WORK/commit-backport-${BSHA7}.patch" > "$WORK/commit-backport-${BSHA7}-norm.patch"
    normalize "$WORK/commit-upstream-${USHA7}.patch" > "$WORK/commit-upstream-${USHA7}-norm.patch"

    CDIFF=$(diff "$WORK/commit-upstream-${USHA7}-norm.patch" \
                 "$WORK/commit-backport-${BSHA7}-norm.patch" || true)
    if [[ -z "$CDIFF" ]]; then
      echo "  ✅ $BSHA7 ← $USHA7"
    else
      echo "  ❌ $BSHA7 ← $USHA7  (diff saved to $WORK/commit-diff-${BSHA7}.txt)"
      echo "$CDIFF" > "$WORK/commit-diff-${BSHA7}.txt"
      cat "$WORK/commit-diff-${BSHA7}.txt"
    fi
  done < <(jq -r '.commits[] | .oid + "\t" + .messageBody' "$WORK/backport-pr.json")
fi

# ── Step 2: Commit integrity ─────────────────────────────────────────────────

echo
echo "── Commit integrity ────────────────────────────────────────────────────────"

UPSTREAM_SHAS=$(jq -r '.commits[].oid' "$WORK/upstream-pr.json")
COVERED=()
ALL_OK=true

while IFS=$'\t' read -r BSHA MBODY; do
  USHA=$(echo "$MBODY" | grep -oP '(?<=cherry picked from commit )[0-9a-f]+' | head -1)
  BSHA7=${BSHA:0:7}
  if [[ -z "$USHA" ]]; then
    echo "  ❌ $BSHA7 has no cherry-pick trailer"
    ALL_OK=false
    continue
  fi
  USHA7=${USHA:0:7}
  if ! echo "$UPSTREAM_SHAS" | grep -q "^${USHA}"; then
    echo "  ❌ $BSHA7 references $USHA7 which is NOT in upstream PR #${UPSTREAM_PR}"
    ALL_OK=false
  else
    COVERED+=("$USHA")
  fi
done < <(jq -r '.commits[] | .oid + "\t" + .messageBody' "$WORK/backport-pr.json")

MISSING=()
while read -r USHA; do
  if ! printf '%s\n' "${COVERED[@]}" | grep -q "^${USHA}"; then
    MISSING+=("${USHA:0:7}")
  fi
done <<< "$UPSTREAM_SHAS"

if [[ ${#MISSING[@]} -gt 0 ]]; then
  echo "  ❌ Upstream commits not covered: ${MISSING[*]}"
  ALL_OK=false
fi

if $ALL_OK; then
  PAIRS=$(jq -r '.commits[] | .oid[0:7] + " ← " + (.messageBody | capture("cherry picked from commit (?<u>[0-9a-f]+)").u[0:7])' \
    "$WORK/backport-pr.json" | paste -sd ', ')
  N=$(jq '.commits | length' "$WORK/backport-pr.json")
  echo "  ✅ Commits: ${N}/${N} cherry-picks valid (${PAIRS})"
fi

# ── Step 3: Issue linkage ────────────────────────────────────────────────────

echo
echo "── Issue linkage ───────────────────────────────────────────────────────────"

if [[ -z "$ISSUE" ]]; then
  echo "  ❌ No backport issue URL found in PR description"
else
  ISSUE_BODY=$(jq -r '.body' "$WORK/issue.json")
  UPSTREAM_PR_URL="https://github.com/${UPSTREAM_REPO}/pull/${UPSTREAM_PR}"

  # Collect cherry-pick upstream SHAs for commit-based match
  CHERRY_SHAS=$(jq -r '.commits[].messageBody' "$WORK/backport-pr.json" \
    | grep -oP '(?<=cherry picked from commit )[0-9a-f]+' || true)

  LINKED=false
  if echo "$ISSUE_BODY" | grep -qF "$UPSTREAM_PR_URL"; then
    LINKED=true
  else
    while read -r USHA; do
      [[ -z "$USHA" ]] && continue
      if echo "$ISSUE_BODY" | grep -qF "https://github.com/${UPSTREAM_REPO}/commit/${USHA}"; then
        LINKED=true
        break
      fi
    done <<< "$CHERRY_SHAS"
  fi

  if $LINKED; then
    echo "  ✅ Issue #${ISSUE} references upstream PR/commit"
  else
    echo "  ❌ Issue #${ISSUE} does not reference upstream PR or any cherry-picked commit"
  fi
fi
