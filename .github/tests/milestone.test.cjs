// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-License-Identifier: GPL-3.0-or-later
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const workflow = fs.readFileSync(path.join(__dirname, '../workflows/milestone.yml'), 'utf8');
const script = workflow.match(/^        script: \|\r?\n([\s\S]*)$/m)[1]
  .replace(/^          /gm, '')
  // Render the original workflow expressions to reproduce its failure as well.
  .replaceAll('${{ github.event.pull_request.number }}', '7')
  .replaceAll('${{ github.event.repository.name }}', 'Anki-Android');
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const execute = new AsyncFunction('github', 'context', 'core', script);

function fixture({ milestoneError, updateError, issues = [] } = {}) {
  const context = {
    repo: { owner: 'float3', repo: 'Anki-Android' },
    payload: { pull_request: { number: 7, html_url: 'https://github.com/float3/Anki-Android/pull/7' } },
  };
  const reads = [];
  const updates = [];
  const queries = [];
  const messages = [];
  const github = {
    rest: { issues: {
      getMilestone: async args => {
        reads.push(args);
        if (milestoneError) throw milestoneError;
        return { data: { number: 79 } };
      },
      update: async args => {
        updates.push(args);
        // GitHub rejects assigning a milestone that does not exist.
        if (milestoneError?.status === 404) throw Object.assign(new Error('Invalid milestone'), { status: 422 });
        if (updateError) throw updateError;
      },
    } },
    graphql: async (query, variables) => {
      queries.push({ query, variables });
      return { resource: { closingIssuesReferences: { nodes: issues } } };
    },
  };
  return {
    context, reads, updates, queries, messages,
    run: () => execute(github, context, { info: message => messages.push(message) }),
  };
}

test('a missing milestone skips all PR and issue writes', async () => {
  const f = fixture({ milestoneError: Object.assign(new Error('Not found'), { status: 404 }) });
  await f.run();
  assert.deepEqual(f.updates, []);
  assert.deepEqual(f.queries, []);
  assert.equal(f.reads.length, 1);
  assert.ok(f.messages.some(message => /79/.test(message)));
});

test('an existing milestone updates the actual PR and its local linked issues', async () => {
  const f = fixture({ issues: [{ number: 12, repository: { nameWithOwner: 'float3/Anki-Android' } }] });
  await f.run();
  assert.deepEqual(f.reads, [{ owner: 'float3', repo: 'Anki-Android', milestone_number: 79 }]);
  assert.deepEqual(f.updates, [7, 12].map(issue_number => ({ ...f.context.repo, issue_number, milestone: 79 })));
  assert.equal(f.queries[0].variables._url, f.context.payload.pull_request.html_url);
  assert.match(f.queries[0].query, /repository\s*\{\s*nameWithOwner\s*\}/);
});

test('a foreign linked issue cannot update a local issue with the same number', async () => {
  const f = fixture({ issues: [
    { number: 12, repository: { nameWithOwner: 'ankidroid/Anki-Android' } },
    { number: 13, repository: { nameWithOwner: 'float3/Anki-Android' } },
  ] });
  await f.run();
  assert.deepEqual(f.updates.map(update => update.issue_number), [7, 13]);
});

for (const status of [401, 403, 429, 500]) {
  test(`milestone lookup errors (${status}) still fail without writes`, async () => {
    const error = Object.assign(new Error('GitHub request failed'), { status });
    const f = fixture({ milestoneError: error });
    await assert.rejects(f.run(), actual => actual === error);
    assert.deepEqual(f.updates, []);
    assert.deepEqual(f.queries, []);
  });
}

test('assignment errors still fail after a successful milestone lookup', async () => {
  const error = Object.assign(new Error('Assignment denied'), { status: 403 });
  await assert.rejects(fixture({ updateError: error }).run(), actual => actual === error);
});
