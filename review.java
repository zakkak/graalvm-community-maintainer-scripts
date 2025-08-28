///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.kohsuke:github-api:1.329
//DEPS info.picocli:picocli:4.7.7

import static java.lang.System.out;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestCommitDetail;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "review", mixinStandardHelpOptions = true,
        description = "Reviews backport pull requests.")
public class review implements Callable<Integer> {

    @Option(names = {"-r", "--repository"}, description = "The repository to review.", defaultValue = "graalvm/graalvm-community-jdk21u")
    private String repository;

    @Option(names = {"-ur", "--upstream-repository"}, description = "The upstream repository to review.", defaultValue = "oracle/graal")
    private String upstreamRepository;

    @Option(names = {"-p", "--pr"}, description = "The pull request number to review.")
    private Integer pullRequestNumber;

    @Option(names = {"-t", "--token"}, description = "Github token to use when calling the Github API")
    private String token;

    public static void main(String... args) {
        int exitCode = new CommandLine(new review()).execute(args);
        System.exit(exitCode);
    }

    static GitHub github;

    @Override
    public Integer call() throws IOException {

        // Setup github object
        try {
            if (token != null) {
                github = new GitHubBuilder().withOAuthToken(token).build();
            } else {
                github = new GitHubBuilder().build();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize GitHub instance", e);
        }


        GHRepository repo = github.getRepository(repository);

        if (pullRequestNumber == null) {
            for (GHPullRequest pr : repo.getPullRequests(GHIssueState.OPEN)) {
                out.println("Reviewing PR: " + pr.getHtmlUrl());
                reviewPR(pr);
            }
        } else {
            GHPullRequest pr = repo.getPullRequest(pullRequestNumber);
            reviewPR(pr);
        }
        return 0;
    }

    private void reviewPR(GHPullRequest pr) throws IOException {
        String description = pr.getBody();

        int upstreamPRNumber = extractPullRequestNumber(description);
        if (upstreamPRNumber == -1) {
            out.println("❌ No upstream PR found in:");
            out.println(description);
            return;
        }
        GHRepository upstreamRepo = github.getRepository(upstreamRepository);
        GHPullRequest upstreamPR = upstreamRepo.getPullRequest(upstreamPRNumber);

        // 1. Compare patches to see if they match
        compareDiffs(pr, upstreamPR);

        // 2. Check if all backport commits reference the corresponding upstream commits
        checkBackportCommits(pr, upstreamPR);

        // 3. Check if the linked backport issue references the upstream PR being backported
        checkClosingIssue(pr, upstreamPR);
    }

    private void checkClosingIssue(GHPullRequest pr, GHPullRequest upstreamPR) throws IOException {
        String description = pr.getBody();
        int backportIssue = extractBackportIssue(description);
        if (backportIssue == -1) {
            out.println("❌ No backport issue found in:");
            out.println(description);
            return;
        }
        String body = pr.getRepository().getIssue(backportIssue).getBody();
        String commitRegex = upstreamRepository + "/commit/([a-f0-9]{40})";
        Matcher issueMatcher = Pattern.compile(commitRegex).matcher(body);
        if (body.contains(upstreamRepository + "/pull/" + upstreamPR.getNumber())) {
            out.println("✅ Backport issue references upstream PR.");
            return;
        } else if (issueMatcher.find()) {
            String commitSHA = issueMatcher.group(1);
            for (GHPullRequestCommitDetail commit : pr.listCommits()) {
                String message = commit.getCommit().getMessage();
                String cherryPickMessage = "(cherry picked from commit " + commitSHA + ")";
                if (message.contains(cherryPickMessage)) {
                    out.println("✅ Backport issue references upstream commit: " + commitSHA);
                    return;
                }
            }
        }
        out.println("❌ Backport issue does not reference upstream PR:");
        out.println(description);
    }

    private void checkBackportCommits(GHPullRequest pr, GHPullRequest upstreamPR) {
        List<String> upstreamCommitSHAs = new ArrayList<>();
        for (GHPullRequestCommitDetail commit : upstreamPR.listCommits()) {
            upstreamCommitSHAs.add(commit.getSha());
        }
        for (GHPullRequestCommitDetail commit : pr.listCommits()) {
            String message = commit.getCommit().getMessage();
            String cherryPickRegex = "\\(cherry picked from commit ([a-f0-9]{40})\\)";
            Matcher matcher = Pattern.compile(cherryPickRegex).matcher(message);
            if (!matcher.find()) {
                out.println("❌ Commit " + commit.getUrl() + " is not a cherry-pick.");
            } else {
                String cherryPickedCommitSHA = matcher.group(1);
                if (!upstreamCommitSHAs.remove(cherryPickedCommitSHA)) {
                    out.println("❌ Commit " + commit.getUrl() + " does not reference a valid upstream commit: " + cherryPickedCommitSHA);
                }
            }
        }
        if (upstreamCommitSHAs.isEmpty()) {
            out.println("✅ All backport commits reference valid upstream commits.");
        } else {
            out.println("❌ Some upstream commits were not referenced in the backport PR: " + upstreamCommitSHAs);
        }
    }

    private void compareDiffs(GHPullRequest pr, GHPullRequest pullRequest) {
        try {
            URL prDiffUrl = pr.getDiffUrl();
            URL upstreamDiffUrl = pullRequest.getDiffUrl();

            String prPatch = new String(prDiffUrl.openConnection().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String upstreamPatch = new String(upstreamDiffUrl.openConnection().getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            prPatch = prPatch.lines()
                .filter(line -> line.startsWith("-") || line.startsWith("+"))
                .collect(Collectors.joining("\n"));

            upstreamPatch = upstreamPatch.lines()
                .filter(line -> line.startsWith("-") || line.startsWith("+"))
                .collect(Collectors.joining("\n"));

            if (!prPatch.equals(upstreamPatch)) {
                out.println("❌ Diffs do not match!");
                out.println("PR Diff:\n" + prPatch);
                out.println("Upstream Diff:\n" + upstreamPatch);
            } else {
                out.println("✅ Diffs match.");
            }
        } catch (IOException e) {
            out.println("❌ Failed to fetch or compare diffs: " + e.getMessage());
        }
    }

    private int extractBackportIssue(String description) {
        String backportIssueRegex = "https://github.com/graalvm/graalvm-community-jdk21u/issues/(\\d+)";
        Matcher matcher = Pattern.compile(backportIssueRegex).matcher(description);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return -1;
    }

    private int extractPullRequestNumber(String description) {
        String upstreamPRRegex = "https://github.com/" + upstreamRepository + "/pull/(\\d+)";
        Matcher matcher = Pattern.compile(upstreamPRRegex).matcher(description);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return -1; // Return -1 if no match is found
    }
}

