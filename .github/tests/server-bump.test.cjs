// SPDX-License-Identifier: AGPL-3.0-only
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const { alertStale, updatePullRequest, serverCommits } = require("../ankiquest_server_bump.cjs");

const now = Date.parse("2026-11-11T12:00:00Z");
const context = { repo: { owner: "float3", repo: "AnkiQuest-Android" } };
const head = "c".repeat(40);
const pr = {
    number: 204,
    html_url: "https://github.com/float3/AnkiQuest-Android/pull/204",
    body: "",
    head: { sha: head, repo: { full_name: "float3/AnkiQuest-Android" } },
};

function api({ pulls = [pr], checks = [], history = [] } = {}) {
    const comments = new Map(),
        posted = [],
        edits = [],
        creates = [];
    const github = {
        rest: {
            pulls: {
                list: async args => {
                    assert.equal(args.head, "float3:bot/server-bump");
                    assert.equal(args.base, "main");
                    return { data: pulls };
                },
                listCommits() {},
                update: async args => edits.push(args),
                create: async args => creates.push(args),
            },
            checks: { listForRef() {} },
            issues: {
                listComments() {},
                createComment: async args => {
                    posted.push(args);
                    comments.set(args.issue_number, [
                        ...(comments.get(args.issue_number) || []),
                        { body: args.body },
                    ]);
                },
            },
        },
        paginate: async (method, args) => {
            if (method === github.rest.checks.listForRef) {
                assert.equal(args.filter, "latest");
                return args.ref === head
                    ? checks
                    : history.find(entry => entry.sha === args.ref).checks;
            }
            if (method === github.rest.pulls.listCommits) return history;
            assert.equal(method, github.rest.issues.listComments);
            return comments.get(args.issue_number) || [];
        },
    };
    return { github, comments, posted, edits, creates };
}

test("a two-day failing current head alerts the PR and tracking issue only once", async () => {
    const client = api({
        checks: [
            {
                conclusion: "failure",
                completed_at: "2026-11-08T10:00:00Z",
                html_url: "https://github.com/check/1",
            },
        ],
    });
    assert.equal(await alertStale({ ...client, context, now }), true);
    assert.deepEqual(
        client.posted.map(comment => comment.issue_number),
        [204, 203],
    );
    await alertStale({ ...client, context, now });
    assert.equal(client.posted.length, 2);
});

test("fresh failures, green checks and a missing bump never send stale alerts", async () => {
    for (const options of [
        { pulls: [] },
        { checks: [{ conclusion: "success", completed_at: "2026-11-01T10:00:00Z" }] },
        { checks: [{ conclusion: "failure", completed_at: "2026-11-10T10:00:00Z" }] },
        { checks: [{ conclusion: null }] },
    ]) {
        const client = api(options);
        assert.equal(await alertStale({ ...client, context, now }), false);
        assert.equal(client.posted.length, 0);
    }
});

test("an incomplete alert retries only the missing destination", async () => {
    const client = api({
        checks: [
            {
                conclusion: "timed_out",
                completed_at: "2026-11-08T10:00:00Z",
                html_url: "https://github.com/check/2",
            },
        ],
    });
    client.comments.set(204, [
        { body: "<!-- ankiquest-server-bump-stale:204 --> already reported" },
    ]);
    await alertStale({ ...client, context, now });
    assert.deepEqual(
        client.posted.map(comment => comment.issue_number),
        [203],
    );
});

test("new daily bump commits do not reset the age of an uninterrupted failure", async () => {
    const client = api({
        checks: [{ conclusion: "failure", completed_at: "2026-11-11T10:00:00Z" }],
        history: [
            {
                sha: "a".repeat(40),
                checks: [{ conclusion: "failure", completed_at: "2026-11-08T10:00:00Z" }],
            },
        ],
    });
    assert.equal(await alertStale({ ...client, context, now }), true);
    assert.equal(client.posted.length, 2);
});

test("a successful intervening head starts a new failure period", async () => {
    const client = api({
        checks: [{ conclusion: "failure", completed_at: "2026-11-11T10:00:00Z" }],
        history: [
            {
                sha: "a".repeat(40),
                checks: [{ conclusion: "failure", completed_at: "2026-11-08T10:00:00Z" }],
            },
            {
                sha: "b".repeat(40),
                checks: [
                    {
                        conclusion: "success",
                        status: "completed",
                        completed_at: "2026-11-10T10:00:00Z",
                    },
                ],
            },
        ],
    });
    assert.equal(await alertStale({ ...client, context, now }), false);
});

test("coordinated pins outside master history are fetched before listing commits", () => {
    const calls = [];
    const from = "a".repeat(40),
        to = "b".repeat(40);
    const run = (command, args) => {
        calls.push(args);
        if (args.includes("cat-file")) throw new Error("not in the master checkout");
        return args.includes("log") ? `${to}\tCoordinated change\n` : "";
    };
    assert.deepEqual(serverCommits("server", from, to, run), [
        { sha: to, subject: "Coordinated change" },
    ]);
    assert.deepEqual(calls[1], ["-C", "server", "fetch", "--no-tags", "origin", from]);
    assert.ok(calls[2].includes(`${from}..${to}`));
    assert.throws(() => serverCommits("server", "--invalid", to, run), /full server commit/);
});

test("a new PR names the snapshot and source commits without notifying commit-title mentions", async () => {
    const client = api({ pulls: [] });
    await updatePullRequest({
        ...client,
        context,
        from: "a".repeat(40),
        to: "b".repeat(40),
        commits: [{ sha: "d".repeat(40), subject: "Add an example @someone <note>" }],
    });
    assert.equal(client.creates[0].head, "bot/server-bump");
    assert.equal(client.creates[0].base, "main");
    assert.equal(client.creates[0].title, "Bump server to bbbbbbb");
    assert.match(client.creates[0].body, /\/commit\/dddddddd/);
    assert.match(client.creates[0].body, /&#64;someone &lt;note&gt;/);
});

test("updates preserve the reviewers notes outside the generated body", async () => {
    const client = api({
        pulls: [
            {
                ...pr,
                body: "Owner note\n<!-- ankiquest-server-bump:start -->old<!-- ankiquest-server-bump:end -->\nReview decision",
            },
        ],
    });
    await updatePullRequest({
        ...client,
        context,
        from: "a".repeat(40),
        to: "b".repeat(40),
        commits: [],
    });
    assert.match(client.edits[0].body, /^Owner note/);
    assert.match(client.edits[0].body, /Review decision$/);
    assert.doesNotMatch(client.edits[0].body, />old</);
});

test("the scheduled job only pushes the bump branch and keeps trusted helpers from main", () => {
    const workflow = fs.readFileSync(
        path.join(__dirname, "../workflows/ankiquest_bump_server.yml"),
        "utf8",
    );
    assert.match(workflow, /schedule:/);
    assert.match(workflow, /workflow_dispatch:/);
    assert.match(workflow, /token: \$\{\{ secrets\.UPSTREAM_SYNC_TOKEN \}\}/);
    assert.match(workflow, /git push origin HEAD:refs\/heads\/bot\/server-bump/);
    assert.doesNotMatch(workflow, /--force|git push[^\n]*(?:heads\/main|heads\/master)/);
    assert.match(workflow, /node "\$RUNNER_TEMP\/ankiquest_server_snapshot\.cjs" copy server/);
    assert.match(workflow, /require\(path.join\(process.env.RUNNER_TEMP/);
    assert.ok(
        workflow.indexOf("Keep reviewed main-branch automation") <
            workflow.indexOf("git checkout -b bot/server-bump"),
    );
});
