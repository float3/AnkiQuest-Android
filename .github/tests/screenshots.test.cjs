// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-License-Identifier: GPL-3.0-or-later
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const waitForBaseline = require('../ankiquest_screenshot_baseline.js');

const compare = fs.readFileSync(path.join(__dirname, '../workflows/ankiquest_screenshot_compare.yml'), 'utf8').replaceAll('\r\n', '\n');

function step(name) {
  const start = compare.indexOf(`      - name: ${name}\n`);
  assert.notEqual(start, -1, `Missing workflow step: ${name}`);
  const end = compare.indexOf('\n      - ', start + 1);
  return compare.slice(start, end === -1 ? undefined : end);
}

function run(id, options = {}) {
  return { id, head_sha: 'base-sha', head_branch: 'main', event: 'push', status: 'completed', conclusion: 'success', ...options };
}

function harness(batches = [[run(42)]], artifacts = {}) {
  const queries = [], artifactQueries = [], waits = [], messages = [];
  const context = {
    repo: { owner: 'float3', repo: 'AnkiQuest-Android' },
    sha: 'merge-sha', ref: 'refs/pull/1/merge',
    payload: { pull_request: { base: { sha: 'base-sha', ref: 'main' } } },
  };
  const github = { rest: { actions: {
    async listWorkflowRuns(query) {
      queries.push(query);
      return { data: { workflow_runs: batches[Math.min(queries.length - 1, batches.length - 1)] } };
    },
    async listWorkflowRunArtifacts(query) {
      artifactQueries.push(query);
      return { data: { artifacts: artifacts[query.run_id] ?? [{ name: 'screenshot', expired: false }] } };
    },
  } } };
  const core = { info: message => messages.push(message) };
  return {
    github, context, core, queries, artifactQueries, waits, messages,
    sleep: async delay => { waits.push(delay); },
  };
}

test('a missing baseline cannot produce a successful comparison', () => {
  const download = step('Download base branch screenshots');
  assert.doesNotMatch(download, /continue-on-error:\s*true/);
  assert.match(download, /if_no_artifact_found:\s*fail/);
  assert.match(download, /allow_forks:\s*false/);
  assert.match(download, /run_id: \$\{\{ steps\.source\.outputs\.result \}\}/);
  assert.doesNotMatch(download, /(?:branch|workflow|search_artifacts):/);
  assert.ok(compare.indexOf('Download base branch screenshots') < compare.indexOf('Compare screenshots'));
});

test('the workflow passes its matching run to the artifact download', async () => {
  const source = step('Wait for matching base screenshots');
  const script = source.slice(source.indexOf('script: |\n') + 'script: |\n'.length);
  const execute = new (Object.getPrototypeOf(async function () {}).constructor)('github', 'context', 'core', 'require', script);
  const fixture = harness();
  const result = await execute(fixture.github, fixture.context, fixture.core,
    file => require(path.join(__dirname, '../..', file)));
  assert.equal(result, 42);
  assert.deepEqual(fixture.queries[0], {
    owner: 'float3', repo: 'AnkiQuest-Android', workflow_id: 'ankiquest_screenshot_store.yml',
    head_sha: 'base-sha', status: 'success', per_page: 100,
  });
});

test('overlapping jobs cannot substitute a newer or older commit for a running baseline', async () => {
  const other = [run(90, { head_sha: 'newer-sha' }), run(10, { head_sha: 'older-sha' })];
  const fixture = harness([
    [...other, run(42, { status: 'in_progress', conclusion: null })],
    [...other, run(42)],
  ]);
  assert.equal(await waitForBaseline(fixture), 42);
  assert.deepEqual(fixture.waits, [30_000]);
  assert.deepEqual(fixture.artifactQueries.map(query => query.run_id), [42]);
});

test('the pushed commit is used when there is no pull request', async () => {
  const fixture = harness([[run(43, { head_sha: 'push-sha' })]]);
  fixture.context.payload = {};
  fixture.context.sha = 'push-sha';
  fixture.context.ref = 'refs/heads/main';
  assert.equal(await waitForBaseline(fixture), 43);
  assert.equal(fixture.queries[0].head_sha, 'push-sha');
});

test('stacked PRs wait for their exact base commit instead of falling back to main', async () => {
  const fixture = harness([[run(1, { head_sha: 'main-sha' })], [run(2, { head_branch: 'feature' })]]);
  fixture.context.payload.pull_request.base.ref = 'feature';
  assert.equal(await waitForBaseline(fixture), 2);
  assert.equal(fixture.waits.length, 1);
  assert.ok(fixture.queries.every(query => query.head_sha === 'base-sha'));
});

test('a manual recording on another branch can replace an expired artifact for the same SHA', async () => {
  const fixture = harness([
    [run(50), run(49, { event: 'workflow_dispatch', head_branch: 'restore-baseline' })],
  ], { 50: [{ name: 'screenshot', expired: true }] });
  assert.equal(await waitForBaseline(fixture), 49);
  assert.deepEqual(fixture.artifactQueries.map(query => query.run_id), [50, 49]);
  assert.equal(fixture.waits.length, 0);
});

test('a successful PR test or failed store cannot become a baseline', async () => {
  const fixture = harness([[
    run(50, { event: 'pull_request' }), run(49, { conclusion: 'failure' }), run(48),
  ]]);
  assert.equal(await waitForBaseline(fixture), 48);
  assert.deepEqual(fixture.artifactQueries.map(query => query.run_id), [48]);
});

test('a store with no screenshot artifact stays pending', async () => {
  const fixture = harness([[run(41)], [run(42)]], { 41: [{ name: 'logs', expired: false }] });
  assert.equal(await waitForBaseline(fixture), 42);
  assert.equal(fixture.waits.length, 1);
});

test('timeout names the exact commit and a way to regenerate its baseline', async () => {
  const fixture = harness([[]]);
  await assert.rejects(waitForBaseline(fixture), /base-sha after 30 minutes.*ankiquest_screenshot_store.yml.*branch at that commit/);
  assert.equal(fixture.queries.length, 60);
  assert.equal(fixture.waits.reduce((sum, delay) => sum + delay, 0), 30 * 60 * 1000);
});

test('GitHub errors fail without trying a different baseline', async () => {
  const fixture = harness();
  fixture.github.rest.actions.listWorkflowRuns = async () => { throw new Error('API unavailable'); };
  await assert.rejects(waitForBaseline(fixture), /API unavailable/);
  assert.equal(fixture.waits.length, 0);
});
