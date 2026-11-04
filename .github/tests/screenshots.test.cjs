// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-License-Identifier: GPL-3.0-or-later
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const workflow = name => fs.readFileSync(path.join(__dirname, `../workflows/${name}.yml`), 'utf8');
const compare = workflow('ankiquest_screenshot_compare');

function step(source, name) {
  const start = source.indexOf(`      - name: ${name}\n`);
  assert.notEqual(start, -1, `Missing workflow step: ${name}`);
  const end = source.indexOf('\n      - ', start + 1);
  return source.slice(start, end === -1 ? undefined : end);
}

test('a missing baseline cannot produce a successful comparison', () => {
  const download = step(compare.replaceAll('\r\n', '\n'), 'Download base branch screenshots');
  assert.doesNotMatch(download, /continue-on-error:\s*true/);
  assert.match(download, /if_no_artifact_found:\s*fail/);
  assert.match(download, /workflow_conclusion:\s*success/);
  assert.match(download, /search_artifacts:\s*true/);
  assert.match(download, /allow_forks:\s*false/);
  assert.match(download, /github\.event\.pull_request\.base\.ref/);
  assert.ok(compare.indexOf('Download base branch screenshots') < compare.indexOf('Compare screenshots'));
});

test('comparison selects the PR base branch or the pushed branch', () => {
  const download = step(compare.replaceAll('\r\n', '\n'), 'Download base branch screenshots');
  const expression = download.match(/branch: \$\{\{ (.+) \}\}/)[1];
  const selectBranch = new Function('github', `return ${expression};`);
  for (const [event, ref, base, expected] of [
    ['pull_request', '42/merge', 'ankiquest', 'ankiquest'],
    ['pull_request', '42/merge', 'main', 'main'],
    ['push', 'main', undefined, 'main'],
    ['push', 'ankiquest', undefined, 'ankiquest'],
  ]) {
    assert.equal(selectBranch({
      event_name: event, ref_name: ref,
      event: { pull_request: { base: { ref: base } }, repository: { default_branch: 'ankiquest' } },
    }), expected);
  }
});
