// SPDX-License-Identifier: AGPL-3.0-only
const BRANCH = "bot/server-bump";
const ISSUE = 203;
const BEGIN = "<!-- ankiquest-server-bump:start -->";
const END = "<!-- ankiquest-server-bump:end -->";
const { execFileSync } = require("node:child_process");
const FAILURES = new Set(["failure", "timed_out", "startup_failure"]);
const TWO_DAYS = 2 * 24 * 60 * 60 * 1000;

async function openPullRequest(github, repo) {
    const { data } = await github.rest.pulls.list({
        ...repo,
        state: "open",
        base: "main",
        head: `${repo.owner}:${BRANCH}`,
    });
    return data.find(pr => pr.head.repo.full_name === `${repo.owner}/${repo.repo}`);
}

async function oldFailure(github, repo, pr, now) {
    const checksAt = ref =>
        github.paginate(github.rest.checks.listForRef, {
            ...repo,
            ref,
            per_page: 100,
            filter: "latest",
        });
    const current = await checksAt(pr.head.sha);
    if (!current.some(check => FAILURES.has(check.conclusion))) {
        return undefined;
    }
    const stale = checks =>
        checks.find(
            check =>
                FAILURES.has(check.conclusion) && now - Date.parse(check.completed_at) > TWO_DAYS,
        );
    const currentOld = stale(current);
    if (currentOld) {
        return currentOld;
    }

    // Follow uninterrupted failures across daily pushes; a green head resets age.
    const commits = await github.paginate(github.rest.pulls.listCommits, {
        ...repo,
        pull_number: pr.number,
        per_page: 100,
    });
    for (const commit of commits.reverse()) {
        if (commit.sha === pr.head.sha) {
            continue;
        }
        const checks = await checksAt(commit.sha);
        if (
            checks.length &&
            checks.every(check => ["success", "skipped", "neutral"].includes(check.conclusion))
        ) {
            return undefined;
        }
        const old = stale(checks);
        if (old) {
            return old;
        }
    }
    return undefined;
}

async function alertStale({ github, context, now = Date.now() }) {
    const pr = await openPullRequest(github, context.repo);
    if (!pr) {
        return false;
    }
    const old = await oldFailure(github, context.repo, pr, now);
    if (!old) {
        return false;
    }
    const marker = `<!-- ankiquest-server-bump-stale:${pr.number} -->`;
    const body =
        `${marker}\nServer bump #${pr.number} has had a failing check for more than two days. ` +
        `Please fix that bump PR (including names.json when examples change), or explain what blocks it. ` +
        `Android main still uses its committed pin.\n\n[Failing check](${old.html_url}) · [Bump PR](${pr.html_url})`;
    for (const number of [pr.number, ISSUE]) {
        const comments = await github.paginate(github.rest.issues.listComments, {
            ...context.repo,
            issue_number: number,
            per_page: 100,
        });
        if (!comments.some(comment => comment.body.includes(marker))) {
            await github.rest.issues.createComment({ ...context.repo, issue_number: number, body });
        }
    }
    return true;
}

function escape(text) {
    return text
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll("@", "&#64;");
}

function serverCommits(directory, from, to, run = execFileSync) {
    if (![from, to].every(sha => /^[0-9a-f]{40}$/.test(sha))) {
        throw new Error("Expected full server commit SHAs");
    }
    const git = args =>
        run("git", ["-C", directory, ...args], {
            encoding: "utf8",
            stdio: ["ignore", "pipe", "pipe"],
        });
    // A coordinated feature-branch pin may be absent from master's history.
    try {
        git(["cat-file", "-e", `${from}^{commit}`]);
    } catch {
        git(["fetch", "--no-tags", "origin", from]);
    }
    const log = git(["log", "--reverse", "--format=%H%x09%s", `${from}..${to}`]).trim();
    return log
        ? log.split("\n").map(line => ({ sha: line.slice(0, 40), subject: line.slice(41) }))
        : [];
}

async function updatePullRequest({ github, context, from, to, commits }) {
    if (![from, to].every(sha => /^[0-9a-f]{40}$/.test(sha))) {
        throw new Error("Expected full server commit SHAs");
    }
    const title = `Bump server to ${to.slice(0, 7)}`;
    const list = commits
        .slice(0, 200)
        .map(commit => {
            if (!/^[0-9a-f]{40}$/.test(commit.sha)) {
                throw new Error("Invalid commit ID");
            }
            return `- [${commit.sha.slice(0, 7)}](https://github.com/float3/ankiquest/commit/${commit.sha}) ${escape(commit.subject).slice(0, 240)}`;
        })
        .join("\n");
    const generated =
        `${BEGIN}\nCopies server API examples and shared fixtures from \`${to}\` into the committed Android snapshot.\n\n` +
        `Part of #${ISSUE}. Normal PR/main tests use this pin, not a moving server branch. ` +
        `If a new or removed example makes CI fail, update names.json and the consumer test in this PR; do not bypass the strict ownership check.\n\n` +
        `[All server commits](https://github.com/float3/ankiquest/compare/${from}...${to})\n\n${list || "(No new server commits.)"}` +
        (commits.length > 200 ? "\n\nThe compare link includes the remaining commits." : "") +
        `\n${END}`;
    const pr = await openPullRequest(github, context.repo);
    if (!pr) {
        return github.rest.pulls.create({
            ...context.repo,
            base: "main",
            head: BRANCH,
            title,
            body: generated,
        });
    }
    const body = pr.body || "";
    const start = body.indexOf(BEGIN),
        end = body.indexOf(END, start);
    const next =
        start >= 0 && end >= start
            ? body.slice(0, start) + generated + body.slice(end + END.length)
            : generated + "\n\n" + body;
    return github.rest.pulls.update({ ...context.repo, pull_number: pr.number, title, body: next });
}

module.exports = { alertStale, updatePullRequest, serverCommits };
