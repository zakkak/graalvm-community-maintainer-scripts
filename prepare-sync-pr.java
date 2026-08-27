///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.kohsuke:github-api:1.330
//DEPS info.picocli:picocli:4.7.7
//DEPS org.eclipse.jgit:org.eclipse.jgit:7.7.1.202607240634-r
//DEPS org.slf4j:slf4j-nop:2.0.18
//JAVA 17+

import static java.lang.System.err;
import static java.lang.System.out;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;

import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestCommitDetail;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubAbuseLimitHandler;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.GitHubRateLimitHandler;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Prepares the PR description for an "upstream sync" PR — a merge of an upstream
 * release branch (e.g. oracle/graal release/graal-vm/25.0) into a GraalVM
 * community downstream repo's master. The merge itself is done manually by the
 * maintainer beforehand; this tool analyzes the resulting merge commit and
 * categorizes every newly-merged upstream PR/commit.
 *
 * oracle/graal's release branches are built by an internal mirroring process: each
 * backport lands as a MERGE commit whose subject is "[GR-nnnnn] Backport to X.Y:
 * <original PR title>" and which carries a "PullRequest: graal/NNNNN" internal-tracker
 * footer (not a GitHub PR link). That merge's own SHA is not resolvable on GitHub, but
 * its individual (non-merge) commits are produced with `git cherry-pick -x`, so they
 * retain a real "(cherry picked from commit <sha>)" trailer pointing at the ORIGINAL
 * upstream commit. We therefore ignore the merge markers and, for each non-merge
 * commit, recover the upstream PR from its cherry-picked SHA via the commit -> PR API
 * (falling back to an exact-title search only when a commit lacks a usable cherry-pick
 * trailer).
 */
@Command(name = "prepare-sync-pr", mixinStandardHelpOptions = true,
        description = "Prepares the categorized PR description for an upstream sync merge.")
// Package-private (not `public`) so the class name need not match the hyphenated
// file name — jbang locates the main method regardless.
class prepare_sync_pr implements Callable<Integer> {

    @Option(names = {"-r", "--repository"},
            description = "Downstream repository (owner/name). Defaults to the 'origin' remote of the working directory.")
    private String repository;

    @Option(names = {"-ur", "--upstream-repository"},
            description = "The upstream repository.", defaultValue = "oracle/graal")
    private String upstreamRepository;

    @Option(names = {"-C", "--directory"},
            description = "Path to the downstream git working directory.", defaultValue = ".")
    private String directory;

    @Option(names = {"-t", "--token"}, description = "Github token to use when calling the Github API")
    private String token;

    @Option(names = {"-v", "--verbose"}, description = "Enable verbose output.")
    private boolean verbose;

    @Option(names = {"-c", "--create"},
            description = "After generating the body, push the current branch and open GitHub's PR-create "
                    + "form in a browser with the title and body pre-filled. Does NOT submit the PR — you "
                    + "review and click \"Create\". Off by default.")
    private boolean create;

    public static void main(String... args) {
        int exitCode = new CommandLine(new prepare_sync_pr()).execute(args);
        System.exit(exitCode);
    }

    static GitHub github;

    // Repository hosting this tool, referenced in the generated body's footer.
    // Update if the maintainer scripts move (e.g. to the graalvm org).
    private static final String SCRIPT_REPO = "https://github.com/zakkak/graalvm-community-maintainer-scripts";
    private static final String SCRIPT_URL = SCRIPT_REPO + "/blob/main/prepare-sync-pr.java";

    // "[GR-nnnnn] " optionally followed by "Backport to X.Y: ", capturing the inner title.
    private static final Pattern GR_PREFIX = Pattern.compile("^\\[GR-\\d+\\]\\s*(.*)$", Pattern.DOTALL);
    private static final Pattern BACKPORT_PREFIX = Pattern.compile("^Backport to [^:]+:\\s*");
    private static final Pattern CHERRY_PICK = Pattern.compile("cherry picked from commit ([a-f0-9]{40})");
    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");
    // A GitHub closing keyword ("Closes #12", "fixes #34", ...) referencing an issue in the same repo.
    private static final Pattern CLOSES_KEYWORD =
            Pattern.compile("(?i)(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\\s*:?\\s+#(\\d+)");

    @Override
    public Integer call() throws Exception {
        github = buildGitHub();

        File repoDir = new File(directory);
        try (Git git = Git.open(repoDir)) {
            Repository jgit = git.getRepository();

            if (repository == null) {
                repository = deriveNwo(jgit.getConfig().getString("remote", "origin", "url"));
                if (repository == null) {
                    err.println("❌ Could not derive the downstream repository from the 'origin' remote. "
                            + "Pass it explicitly with --repository owner/name.");
                    return 1;
                }
            }

            err.println("Downstream: " + repository);
            err.println("Upstream: " + upstreamRepository);

            // ── Step 0: Identify the merge ──────────────────────────────────
            ObjectId headId = jgit.resolve("HEAD");
            if (headId == null) {
                err.println("❌ Could not resolve HEAD.");
                return 1;
            }

            String upstreamBranch;
            List<RevCommit> newCommits;
            ObjectId oldBase, upstreamTip;
            try (RevWalk walk = new RevWalk(jgit)) {
                RevCommit merge = walk.parseCommit(headId);
                if (merge.getParentCount() != 2) {
                    err.println("❌ HEAD is not a two-parent merge commit. Merge the upstream branch first.");
                    return 1;
                }
                oldBase = merge.getParent(0).getId();
                upstreamTip = merge.getParent(1).getId();
                err.println("Old base: " + oldBase.name());
                err.println("Upstream tip: " + upstreamTip.name());

                // Derive the upstream branch name from the merge subject, which looks like:
                //   Merge upstream 'release/graal-vm/25.0' branch (tag vm-25.0.3)
                Matcher m = QUOTED.matcher(merge.getShortMessage());
                upstreamBranch = m.find() ? m.group(1) : "<UPSTREAM_BRANCH>";
                err.println("Upstream branch: " + upstreamBranch);
            }

            newCommits = revList(jgit, oldBase, upstreamTip);
            err.println("New upstream commits: " + newCommits.size());

            // Pre-index every "cherry picked from commit <sha>" trailer in the downstream
            // history that predates this merge (i.e. genuinely earlier backports). Walking
            // from oldBase — not from the merged-in release-branch tip — avoids self-matching
            // the release commits we just pulled in, which carry their own internal trailers.
            Map<String, String> cherryPicks = indexCherryPicks(jgit, oldBase);
            if (verbose) {
                err.println("Indexed cherry-pick trailers in downstream history: " + cherryPicks.size());
            }

            GHRepository downstreamRepo = github.getRepository(repository);
            GHRepository upstreamRepo = github.getRepository(upstreamRepository);

            // ── Step 1: Map each backport to its upstream PR, grouped by merge ──
            // oracle/graal records each backport as a merge commit ("[GR-...] Backport to
            // X.Y") whose second parent holds the individual commits. We walk the release
            // branch's first-parent mainline: every merge is one backport unit, and ALL the
            // commits it introduces map to the same upstream PR — even ones without a
            // cherry-pick trailer (e.g. "fix conflict"), which we'd otherwise misfile. The
            // PR is recovered from any group member's cherry-picked SHA via the commit -> PR
            // API, falling back to an exact-title search on the merge subject. Direct
            // (non-merge) mainline commits are mapped individually the same way.
            Map<Integer, String> prUrl = new LinkedHashMap<>();   // pr number -> upstream html_url
            Set<Integer> touched = new TreeSet<>();               // deterministic order
            List<RevCommit> bareCommits = new ArrayList<>();
            List<String> ambiguous = new ArrayList<>();

            for (RevCommit c : firstParentChain(jgit, oldBase, upstreamTip)) {
                if (c.getParentCount() > 1) {
                    // Backport merge: attribute the whole introduced subtree to its PR(s).
                    List<RevCommit> group = revList(jgit, c.getParent(0).getId(), c.getParent(1).getId());
                    Set<PrRef> prs = resolveGroupPrs(upstreamRepo, group);
                    if (prs.isEmpty()) {
                        // No cherry-pick trailer resolved — fall back to the merge subject's title.
                        String inner = stripGrPrefix(c.getShortMessage());
                        List<PrRef> matches = inner == null ? List.of() : searchUpstreamPrByTitle(inner);
                        if (matches.size() == 1) {
                            prs = Set.of(matches.get(0));
                        } else if (matches.size() > 1) {
                            ambiguous.add(c.name() + " (" + c.getShortMessage() + ") candidates: "
                                    + candidateList(matches));
                        }
                    }
                    if (prs.isEmpty()) {
                        // Still unmapped — surface the group's non-merge commits individually.
                        for (RevCommit g : group) {
                            if (g.getParentCount() == 1) {
                                bareCommits.add(g);
                            }
                        }
                    } else {
                        for (PrRef r : prs) {
                            prUrl.put(r.number(), r.url());
                            touched.add(r.number());
                        }
                    }
                    continue;
                }

                // Direct commit on the release branch (not introduced by a backport merge).
                String subject = c.getShortMessage();

                // Primary — link the cherry-picked upstream SHA to its upstream PR.
                String cherry = cherryPickedSha(c);
                if (cherry != null) {
                    PrRef r = resolveCherryPickedPr(upstreamRepo, c, cherry);
                    if (r == PrRef.MULTIPLE) {
                        ambiguous.add(c.name() + " (" + subject + ") cherry-picks " + cherry
                                + " which maps to multiple upstream PRs");
                        continue;
                    } else if (r != null) {
                        prUrl.put(r.number(), r.url());
                        touched.add(r.number());
                        continue;
                    }
                    // SHA present but unknown upstream — fall through to title matching.
                }

                // Fallback — exact-title match for a "[GR-...]"-prefixed subject.
                String inner = stripGrPrefix(subject);
                if (inner == null) {
                    bareCommits.add(c);
                    continue;
                }
                List<PrRef> matches = searchUpstreamPrByTitle(inner);
                if (matches.isEmpty()) {
                    bareCommits.add(c);
                } else if (matches.size() > 1) {
                    ambiguous.add(c.name() + " (" + subject + ") candidates: " + candidateList(matches));
                } else {
                    PrRef r = matches.get(0);
                    prUrl.put(r.number(), r.url());
                    touched.add(r.number());
                }
            }

            err.println("Upstream PRs touched: " + touched);
            err.println("Bare commits (no upstream PR match): " + bareCommits.size());
            err.println("Ambiguous title matches: " + ambiguous.size());

            // ── Step 2: Classify each upstream PR against the downstream repo ──
            List<String> already = new ArrayList<>();
            List<String> partial = new ArrayList<>();
            List<String> knownOpen = new ArrayList<>();
            List<String> unknown = new ArrayList<>();
            Set<Integer> supersededPrs = new LinkedHashSet<>();   // open backport PRs this sync replaces
            Set<Integer> closesIssues = new LinkedHashSet<>();    // open backport issues to auto-close

            for (int prNum : touched) {
                String upstreamPrUrl = prUrl.get(prNum);
                String matchState = null;   // "already" | "partial" | "open"
                String matchRef = null;
                boolean openIsPr = false;   // when open: is the downstream match a PR or an issue?
                int openNumber = -1;
                String openBody = null;     // when open & a PR: its body, to find the tracking issue
                int totalCommits = 0;       // for partial-backport accounting
                int backportedCommits = 0;
                List<String> missingCommits = new ArrayList<>();

                // 2a: cherry-pick trailer match against the PR's *original* commit SHAs. Count how
                // many of the PR's commits landed downstream: all -> already backported; some -> a
                // partial backport (only specific commits were cherry-picked).
                GHPullRequest upr = upstreamRepo.getPullRequest(prNum);
                for (GHPullRequestCommitDetail cd : upr.listCommits()) {
                    totalCommits++;
                    String downstreamSha = cherryPicks.get(cd.getSha());
                    if (downstreamSha != null) {
                        backportedCommits++;
                        if (matchRef == null) {
                            matchRef = downstreamPrForCommit(downstreamRepo, downstreamSha);
                        }
                    } else {
                        missingCommits.add(cd.getSha());
                    }
                }
                if (backportedCommits > 0) {
                    matchState = backportedCommits == totalCommits ? "already" : "partial";
                }

                // 2b: fall back to searching downstream issues/PRs referencing the upstream PR URL.
                // Deliberately NOT filtered by label:backport — the label is often only on the
                // tracking issue, not the PR that closes it (or vice versa). Categorize every
                // candidate and pick by priority, since the search can return both a tracking issue
                // and its backport PR at once:
                //   merged PR  -> already backported (the definitive ref — a PR, not the issue)
                //   open PR    -> in-progress backport this sync supersedes
                //   open issue -> outstanding backport request this sync closes
                //   closed issue -> already backported (best signal when no PR is found)
                // A closed-but-unmerged PR is an abandoned attempt: ignored entirely.
                if (matchState == null) {
                    GHPullRequest mergedPr = null;
                    GHIssue openPr = null;
                    GHIssue openIssue = null;
                    GHIssue closedIssue = null;
                    for (GHIssue it : github.searchIssues()
                            .q("repo:" + repository + " \"" + upstreamPrUrl + "\" in:body").list()) {
                        if (it.isPullRequest()) {
                            if (it.getState() == GHIssueState.OPEN) {
                                if (openPr == null) {
                                    openPr = it;
                                }
                            } else if (mergedPr == null) {
                                GHPullRequest pr = downstreamRepo.getPullRequest(it.getNumber());
                                if (pr.isMerged()) {
                                    mergedPr = pr;
                                }
                            }
                        } else if (it.getState() == GHIssueState.OPEN) {
                            if (openIssue == null) {
                                openIssue = it;
                            }
                        } else if (closedIssue == null) {
                            closedIssue = it;
                        }
                    }
                    if (mergedPr != null) {
                        matchState = "already";
                        matchRef = mergedPr.getHtmlUrl().toString();
                    } else if (openPr != null) {
                        matchState = "open";
                        openIsPr = true;
                        openNumber = openPr.getNumber();
                        openBody = openPr.getBody();
                        matchRef = openPr.getHtmlUrl().toString();
                    } else if (openIssue != null) {
                        matchState = "open";
                        openIsPr = false;
                        openNumber = openIssue.getNumber();
                        openBody = openIssue.getBody();
                        matchRef = openIssue.getHtmlUrl().toString();
                    } else if (closedIssue != null) {
                        matchState = "already";
                        matchRef = closedIssue.getHtmlUrl().toString();
                    }
                }

                if ("already".equals(matchState)) {
                    already.add("* " + upstreamPrUrl + "\n  (already backported with " + matchRef + ")");
                } else if ("partial".equals(matchState)) {
                    StringBuilder e = new StringBuilder();
                    e.append("* ").append(upstreamPrUrl)
                            .append("\n  (partially backported with ").append(matchRef).append(" — ")
                            .append(backportedCommits).append(" of ").append(totalCommits)
                            .append(" commits; not yet backported:");
                    for (String sha : missingCommits) {
                        e.append("\n    * https://github.com/").append(upstreamRepository)
                                .append("/commit/").append(sha);
                    }
                    e.append(")");
                    partial.add(e.toString());
                } else if ("open".equals(matchState)) {
                    if (openIsPr) {
                        knownOpen.add("* " + upstreamPrUrl + "\n  (open backport PR " + matchRef + ")");
                        supersededPrs.add(openNumber);
                        // Superseding a backport PR also closes the backport issue it tracks, so
                        // this sync PR takes over closing it (the PR itself won't merge).
                        int trackingIssue = extractBackportIssue(openBody);
                        if (trackingIssue != -1) {
                            closesIssues.add(trackingIssue);
                        }
                    } else {
                        knownOpen.add("* " + upstreamPrUrl + "\n  (open backport issue " + matchRef + ")");
                        closesIssues.add(openNumber);
                    }
                } else {
                    unknown.add("* " + upstreamPrUrl);
                }
            }

            // ── Step 2c: close open backport requests that asked for specific commits ──
            // The per-PR search in 2b only matches requests citing the upstream PR URL. A
            // "partial backport" request instead cites specific upstream commit URLs (often with
            // abbreviated SHAs, which a full-SHA search can't match). We enumerate the (few) open
            // backport requests once and close any whose every requested PR/commit is now present
            // — even when only some of an upstream PR's commits were backported, as long as the
            // request asked for exactly those. Scoped to this sync: a request is only closed when
            // at least one thing it asked for is introduced by THIS merge (not solely by an
            // earlier backport). Matching a requested (possibly abbreviated) SHA against the full
            // SHAs we hold is a prefix test, which sidesteps the abbreviated-SHA search limitation.
            Set<String> broughtInShas = new HashSet<>();
            for (RevCommit c : newCommits) {
                String s = cherryPickedSha(c);
                if (s != null) {
                    broughtInShas.add(s);
                }
            }
            Set<String> presentShas = new HashSet<>(cherryPicks.keySet());
            presentShas.addAll(broughtInShas);

            for (GHIssue req : openBackportRequests(downstreamRepo)) {
                int num = req.getNumber();
                boolean isPr = req.isPullRequest();
                if (isPr ? supersededPrs.contains(num) : closesIssues.contains(num)) {
                    continue;   // already handled by 2b
                }
                Set<Integer> reqPrs = parseUpstreamPrs(req.getBody());
                List<String> reqCommits = parseUpstreamCommits(req.getBody());
                if (reqPrs.isEmpty() && reqCommits.isEmpty()) {
                    continue;
                }

                boolean relevant = false;   // asks for something this sync actually introduces
                boolean satisfied = true;   // everything it asks for is now present
                for (int n : reqPrs) {
                    if (touched.contains(n)) {
                        relevant = true;
                    }
                    if (!prFullyPresent(upstreamRepo, n, presentShas)) {
                        satisfied = false;
                    }
                }
                for (String sha : reqCommits) {
                    if (commitPresent(sha, broughtInShas)) {
                        relevant = true;
                    }
                    if (!commitPresent(sha, presentShas)) {
                        satisfied = false;
                    }
                }
                if (!relevant || !satisfied) {
                    continue;
                }

                if (isPr) {
                    supersededPrs.add(num);
                    int trackingIssue = extractBackportIssue(req.getBody());
                    if (trackingIssue != -1) {
                        closesIssues.add(trackingIssue);
                    }
                } else {
                    closesIssues.add(num);
                }
            }

            // ── Step 3: Group bare commits by keyword ──────────────────────
            Map<String, List<String>> otherGroups = new LinkedHashMap<>();
            for (RevCommit c : bareCommits) {
                String subject = c.getShortMessage();
                String group = classifyKeyword(subject);
                String url = "https://github.com/" + upstreamRepository + "/commit/" + c.name();
                otherGroups.computeIfAbsent(group, k -> new ArrayList<>())
                        .add("  * " + url + " " + subject);
            }

            // ── Output ─────────────────────────────────────────────────────
            String body = renderBody(upstreamBranch, already, partial, knownOpen, unknown, otherGroups,
                    ambiguous, supersededPrs, closesIssues);
            out.print(body);

            Path bodyFile = Files.createTempFile("pr-body", ".md");
            Files.writeString(bodyFile, body, StandardCharsets.UTF_8);
            err.println();
            err.println("Draft PR body written to " + bodyFile + " — review before opening the PR.");

            // ── Optional: open a pre-filled PR-create form in the browser ───
            if (create) {
                openPrForm(repoDir, jgit, upstreamBranch, body, bodyFile);
            }
        }
        return 0;
    }

    // ── GitHub setup ───────────────────────────────────────────────────────

    private GitHub buildGitHub() {
        try {
            GitHubBuilder b = new GitHubBuilder();
            if (token != null) {
                b = b.withOAuthToken(token);
            }
            // WAIT handlers block until the primary/secondary (search) rate limits reset,
            // instead of failing — this replaces the hand-rolled sleep/retry loop the shell
            // version needed for the 30 req/min search endpoint.
            return b.withRateLimitHandler(GitHubRateLimitHandler.WAIT)
                    .withAbuseLimitHandler(GitHubAbuseLimitHandler.WAIT)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize GitHub instance", e);
        }
    }

    // ── Local git (JGit) ─────────────────────────────────────────────────────

    /** Commits reachable from {@code tip} but not {@code base}, oldest first. */
    private List<RevCommit> revList(Repository repo, ObjectId base, ObjectId tip) throws IOException {
        List<RevCommit> commits = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo)) {
            walk.sort(RevSort.TOPO);
            walk.sort(RevSort.REVERSE, true);
            walk.markStart(walk.parseCommit(tip));
            walk.markUninteresting(walk.parseCommit(base));
            for (RevCommit c : walk) {
                commits.add(c);
            }
        }
        return commits;
    }

    /** First-parent mainline commits reachable from {@code tip} but not {@code base}, oldest first. */
    private List<RevCommit> firstParentChain(Repository repo, ObjectId base, ObjectId tip) throws IOException {
        List<RevCommit> commits = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo)) {
            walk.setFirstParent(true);   // must precede markStart; requires TOPO sort
            walk.sort(RevSort.TOPO);
            walk.sort(RevSort.REVERSE, true);
            walk.markStart(walk.parseCommit(tip));
            walk.markUninteresting(walk.parseCommit(base));
            for (RevCommit c : walk) {
                commits.add(c);
            }
        }
        return commits;
    }

    /** Map upstream-commit-SHA -> downstream-commit-SHA for cherry-picks in history up to {@code base}. */
    private Map<String, String> indexCherryPicks(Repository repo, ObjectId base) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(base));   // default order is newest-first
            for (RevCommit c : walk) {
                Matcher m = CHERRY_PICK.matcher(c.getFullMessage());
                while (m.find()) {
                    // Newest downstream commit wins (encountered first), matching `git log | head -1`.
                    map.putIfAbsent(m.group(1), c.name());
                }
            }
        }
        return map;
    }

    // ── Title matching ─────────────────────────────────────────────────────

    /** Strip a "[GR-nnnnn] " prefix and an optional "Backport to X.Y: " prefix; null if no GR prefix. */
    private static String stripGrPrefix(String subject) {
        Matcher m = GR_PREFIX.matcher(subject);
        if (!m.matches()) {
            return null;
        }
        return BACKPORT_PREFIX.matcher(m.group(1)).replaceFirst("");
    }

    /** Trim surrounding whitespace and a single trailing period, then lowercase. */
    private static String normalizeTitle(String t) {
        return t.strip().replaceAll("\\.$", "").toLowerCase();
    }

    /** The first line of a (possibly multi-line) commit message. */
    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    /** Upstream PRs whose (prefix-stripped) title exactly matches, case-insensitively. */
    private List<PrRef> searchUpstreamPrByTitle(String title) throws IOException {
        String norm = normalizeTitle(title);
        // The quoted phrase is only a pre-filter; the exact comparison below is authoritative,
        // so stripping quotes from the query term is safe and avoids breaking the search syntax.
        String term = title.replace("\"", "");
        List<PrRef> matches = new ArrayList<>();
        for (GHIssue it : github.searchIssues()
                .q("repo:" + upstreamRepository + " is:pr in:title \"" + term + "\"").list()) {
            String inner = stripGrPrefix(it.getTitle());
            if (inner == null) {
                inner = it.getTitle();
            }
            if (normalizeTitle(inner).equals(norm)) {
                matches.add(new PrRef(it.getNumber(), it.getHtmlUrl().toString()));
            }
        }
        return matches;
    }

    /** Comma-separated PR numbers, for reporting an ambiguous match. */
    private static String candidateList(List<PrRef> matches) {
        StringBuilder nums = new StringBuilder();
        for (PrRef r : matches) {
            nums.append(r.number()).append(',');
        }
        return nums.toString();
    }

    /** The upstream SHA a commit was cherry-picked from (its "-x" trailer), or null if none. */
    private static String cherryPickedSha(RevCommit c) {
        Matcher m = CHERRY_PICK.matcher(c.getFullMessage());
        return m.find() ? m.group(1) : null;
    }

    /** Distinct upstream PRs referenced by any cherry-pick trailer among a merge group's commits. */
    private Set<PrRef> resolveGroupPrs(GHRepository upstreamRepo, List<RevCommit> group) throws IOException {
        Map<Integer, PrRef> prs = new LinkedHashMap<>();
        for (RevCommit g : group) {
            String sha = cherryPickedSha(g);
            if (sha == null) {
                continue;
            }
            PrRef r = resolveCherryPickedPr(upstreamRepo, g, sha);
            if (r != null && r != PrRef.MULTIPLE) {
                prs.putIfAbsent(r.number(), r);
            }
        }
        return new LinkedHashSet<>(prs.values());
    }

    /**
     * Resolve the upstream PR for a cherry-picked commit. Preferred: the commit -> PR API on the
     * cherry-picked SHA. When that SHA is referenced but linked to no PR (e.g. it never landed on
     * the default branch as a PR commit), fall back to finding a PR that contains a commit with the
     * same message. Returns {@link PrRef#MULTIPLE} on ambiguity, or null if nothing matches.
     */
    private PrRef resolveCherryPickedPr(GHRepository upstreamRepo, RevCommit c, String sha) throws IOException {
        PrRef r = upstreamPrForCommit(upstreamRepo, sha);
        if (r != null) {
            return r;
        }
        return upstreamPrByCommitMessage(upstreamRepo, c.getShortMessage());
    }

    /**
     * Find the upstream PR containing a commit whose message matches {@code message}. The PR title
     * need not match — a phrase search on title/body only pre-filters candidates, and the match is
     * confirmed against each candidate PR's own commit messages.
     */
    private PrRef upstreamPrByCommitMessage(GHRepository upstreamRepo, String message) throws IOException {
        String norm = normalizeTitle(message);
        // The quoted phrase is only a pre-filter; the per-commit comparison below is authoritative,
        // so stripping quotes from the query term is safe and avoids breaking the search syntax.
        String term = message.replace("\"", "");
        Map<Integer, PrRef> matches = new LinkedHashMap<>();
        for (GHIssue it : github.searchIssues()
                .q("repo:" + upstreamRepository + " is:pr \"" + term + "\"").list()) {
            GHPullRequest pr = upstreamRepo.getPullRequest(it.getNumber());
            for (GHPullRequestCommitDetail cd : pr.listCommits()) {
                String prCommitSubject = firstLine(cd.getCommit().getMessage());
                String inner = stripGrPrefix(prCommitSubject);
                if (inner == null) {
                    inner = prCommitSubject;
                }
                if (normalizeTitle(inner).equals(norm)) {
                    matches.putIfAbsent(it.getNumber(), new PrRef(it.getNumber(), it.getHtmlUrl().toString()));
                    break;
                }
            }
        }
        if (matches.size() == 1) {
            return matches.values().iterator().next();
        } else if (matches.size() > 1) {
            return PrRef.MULTIPLE;
        }
        return null;
    }

    /**
     * Upstream PR that introduced {@code sha}: a resolved {@link PrRef}, {@link PrRef#MULTIPLE}
     * when more than one PR claims the commit, or null if the commit is unknown upstream or has
     * no associated PR.
     */
    private PrRef upstreamPrForCommit(GHRepository upstreamRepo, String sha) {
        try {
            List<GHPullRequest> pulls = upstreamRepo.getCommit(sha).listPullRequests().toList();
            if (pulls.size() == 1) {
                GHPullRequest pr = pulls.get(0);
                return new PrRef(pr.getNumber(), pr.getHtmlUrl().toString());
            } else if (pulls.size() > 1) {
                return PrRef.MULTIPLE;
            }
        } catch (IOException e) {
            // Commit unknown to the upstream repo (e.g. never pushed there) — caller falls back.
        }
        return null;
    }

    private String downstreamPrForCommit(GHRepository downstreamRepo, String sha) {
        try {
            GHCommit commit = downstreamRepo.getCommit(sha);
            GHPullRequest openPr = null;
            for (GHPullRequest pr : commit.listPullRequests()) {
                if (pr.isMerged()) {
                    return pr.getHtmlUrl().toString();   // a merged PR is the definitive ref
                }
                if (openPr == null && pr.getState() == GHIssueState.OPEN) {
                    openPr = pr;   // keep an open PR as a fallback; skip closed-unmerged (abandoned)
                }
            }
            if (openPr != null) {
                return openPr.getHtmlUrl().toString();
            }
        } catch (IOException e) {
            // fall through to reporting the raw SHA
        }
        return sha + " (no PR found via API)";
    }

    /** Open issues and PRs in the downstream repo that reference the upstream repo (candidate backport requests). */
    private List<GHIssue> openBackportRequests(GHRepository repo) throws IOException {
        List<GHIssue> result = new ArrayList<>();
        for (GHIssue it : repo.getIssues(GHIssueState.OPEN)) {
            String body = it.getBody();
            if (body != null && (body.contains(upstreamRepository + "/pull/")
                    || body.contains(upstreamRepository + "/commit/"))) {
                result.add(it);
            }
        }
        return result;
    }

    /** Upstream PR numbers referenced by "…/pull/NN" URLs in a request body. */
    private Set<Integer> parseUpstreamPrs(String body) {
        Set<Integer> prs = new LinkedHashSet<>();
        if (body == null) {
            return prs;
        }
        Matcher m = Pattern.compile(Pattern.quote(upstreamRepository) + "/pull/(\\d+)").matcher(body);
        while (m.find()) {
            prs.add(Integer.parseInt(m.group(1)));
        }
        return prs;
    }

    /** Upstream commit SHAs (possibly abbreviated) referenced by "…/commit/SHA" URLs in a request body. */
    private List<String> parseUpstreamCommits(String body) {
        List<String> shas = new ArrayList<>();
        if (body == null) {
            return shas;
        }
        Matcher m = Pattern.compile(Pattern.quote(upstreamRepository) + "/commit/([a-f0-9]{7,40})").matcher(body);
        while (m.find()) {
            shas.add(m.group(1));
        }
        return shas;
    }

    /** Whether {@code abbrev} (a full or abbreviated SHA) is a prefix of any full SHA in {@code shas}. */
    private static boolean commitPresent(String abbrev, Set<String> shas) {
        for (String s : shas) {
            if (s.startsWith(abbrev)) {
                return true;
            }
        }
        return false;
    }

    /** Whether every commit of upstream PR {@code prNum} is among {@code presentShas} (false on error/empty). */
    private boolean prFullyPresent(GHRepository upstreamRepo, int prNum, Set<String> presentShas) {
        try {
            GHPullRequest pr = upstreamRepo.getPullRequest(prNum);
            boolean any = false;
            for (GHPullRequestCommitDetail cd : pr.listCommits()) {
                any = true;
                if (!presentShas.contains(cd.getSha())) {
                    return false;
                }
            }
            return any;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The downstream backport issue a PR body tracks — a full "…/issues/NN" URL for this repo, or a
     * "Closes #NN"-style keyword — or -1 if none is found.
     */
    private int extractBackportIssue(String body) {
        if (body == null) {
            return -1;
        }
        Matcher url = Pattern.compile("github\\.com/" + Pattern.quote(repository) + "/issues/(\\d+)").matcher(body);
        if (url.find()) {
            return Integer.parseInt(url.group(1));
        }
        Matcher kw = CLOSES_KEYWORD.matcher(body);
        if (kw.find()) {
            return Integer.parseInt(kw.group(1));
        }
        return -1;
    }

    private static String classifyKeyword(String subject) {
        String s = subject.toLowerCase();
        if (s.matches(".*(labsjdk|jvmci).*")) {
            return "labsJDK/JVMCI bumps";
        } else if (s.contains("basedonjdk")) {
            return "Updates of BasedOnJDK annotations";
        } else if (s.matches(".*node\\.?js.*")) {
            return "Node.js upgrades";
        } else if (s.matches(".*(darwin|macos).*")) {
            return "Darwin/macOS changes";
        } else if (s.matches("^mx:.*") || s.matches(".*\\bmx\\b.*") || s.matches(".*\\bci\\b.*")
                || s.matches(".*github.?actions.*") || s.contains(".github/") || s.contains("workflow")) {
            return "CI/mx changes";
        } else if (s.startsWith("release graalvm")) {
            return "Release commits";
        }
        return "Uncategorized";
    }

    // ── Output rendering ─────────────────────────────────────────────────────

    private String renderBody(String upstreamBranch, List<String> already, List<String> partial,
            List<String> knownOpen, List<String> unknown, Map<String, List<String>> otherGroups,
            List<String> ambiguous, Set<Integer> supersededPrs, Set<Integer> closesIssues) {
        StringBuilder b = new StringBuilder();
        b.append("The GraalVM team has synced their internal repositories with their public branch `")
                .append(upstreamBranch).append("`\n\n");
        b.append("This merge includes the following changes:\n\n");

        section(b, "Already backported/merged in this repository", already);
        section(b, "Partially backported in this repository (only specific commits)", partial);
        section(b, "Known but not backported/merged in this repository", knownOpen);
        section(b, "Previously unknown backports (i.e. not reported on this repository)", unknown);

        b.append("#### Other changes not mapping to upstream PRs\n\n");
        if (otherGroups.isEmpty()) {
            b.append("(none)\n");
        } else {
            for (Map.Entry<String, List<String>> e : otherGroups.entrySet()) {
                b.append("* ").append(e.getKey()).append('\n');
                for (String line : e.getValue()) {
                    b.append(line).append('\n');
                }
            }
        }

        if (!ambiguous.isEmpty()) {
            b.append('\n');
            b.append("#### Needs manual check (ambiguous title match against multiple upstream PRs)\n\n");
            for (String a : ambiguous) {
                b.append("* ").append(a).append('\n');
            }
        }

        // Superseded open backport PRs — GitHub does not auto-close PRs from keywords, so these
        // are listed for the maintainer to close manually once this sync is merged.
        if (!supersededPrs.isEmpty()) {
            b.append('\n');
            b.append("#### Open backport PRs superseded by this PR\n\n");
            for (int n : supersededPrs) {
                b.append("* #").append(n).append('\n');
            }
        }

        // Open backport issues — emitted with the "Closes" keyword so GitHub auto-closes them
        // when this PR merges.
        if (!closesIssues.isEmpty()) {
            b.append('\n');
            b.append("#### Open backport issues closed by this PR\n\n");
            for (int n : closesIssues) {
                b.append("Closes #").append(n).append('\n');
            }
        }

        b.append("\n---\n\n");
        b.append("_This description was generated by [`prepare-sync-pr.java`](").append(SCRIPT_URL)
                .append(") from the [graalvm-community-maintainer-scripts](").append(SCRIPT_REPO)
                .append(") repository._\n");
        return b.toString();
    }

    private static void section(StringBuilder b, String heading, List<String> entries) {
        b.append("#### ").append(heading).append("\n\n");
        if (entries.isEmpty()) {
            b.append("(none)\n");
        } else {
            for (String e : entries) {
                b.append(e).append('\n');
            }
        }
        b.append('\n');
    }

    // ── Optional PR-form step ──────────────────────────────────────────────

    private void openPrForm(File repoDir, Repository jgit, String upstreamBranch, String body, Path bodyFile)
            throws IOException, InterruptedException {
        String branch = jgit.getBranch();
        String title = "Merge upstream " + upstreamBranch;
        err.println();
        err.println("Pushing '" + branch + "' and opening a pre-filled PR form for " + repository + " ...");

        // Push reuses the maintainer's existing git credentials (ssh-agent / credential helper),
        // which is far more robust than wiring auth through JGit, so we shell out for this one step.
        ProcessBuilder pb = new ProcessBuilder("git", "push", "-u", "origin", branch);
        pb.directory(repoDir);
        pb.inheritIO();
        if (pb.start().waitFor() != 0) {
            err.println("❌ git push failed; not opening the PR form.");
            return;
        }

        String url = "https://github.com/" + repository + "/compare/master..." + branch + "?expand=1"
                + "&title=" + URLEncoder.encode(title, StandardCharsets.UTF_8)
                + "&body=" + URLEncoder.encode(body, StandardCharsets.UTF_8);

        // GitHub rejects/truncates overlong prefill URLs (~8KB); fall back to printing instructions.
        if (url.length() > 8000 || GraphicsEnvironment.isHeadless()
                || !Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            printFormFallback(url, bodyFile);
            return;
        }
        try {
            Desktop.getDesktop().browse(URI.create(url));
            err.println("Opened a pre-filled PR-create form in your browser — review and click \"Create\".");
        } catch (Exception e) {
            printFormFallback(url, bodyFile);
        }
    }

    private void printFormFallback(String url, Path bodyFile) {
        err.println();
        err.println("⚠️  Could not open a browser (headless, or the body exceeds GitHub's prefill URL");
        err.println("    limit). Open the PR manually and paste the body from:");
        err.println("    " + bodyFile);
        err.println("    Or use this compare URL:");
        err.println("    " + url);
    }

    /** Parse "owner/name" out of a GitHub remote URL (SSH or HTTPS), or null. */
    private static String deriveNwo(String remoteUrl) {
        if (remoteUrl == null) {
            return null;
        }
        String u = remoteUrl.trim();
        if (u.endsWith(".git")) {
            u = u.substring(0, u.length() - 4);
        }
        int i;
        if ((i = u.indexOf("github.com:")) >= 0) {
            return u.substring(i + "github.com:".length());
        }
        if ((i = u.indexOf("github.com/")) >= 0) {
            return u.substring(i + "github.com/".length());
        }
        return null;
    }

    record PrRef(int number, String url) {
        /** Sentinel: a commit/title that resolves to more than one upstream PR. */
        static final PrRef MULTIPLE = new PrRef(-1, null);
    }
}
