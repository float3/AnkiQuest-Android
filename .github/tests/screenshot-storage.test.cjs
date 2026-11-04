// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-License-Identifier: GPL-3.0-or-later
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const store = fs.readFileSync(path.join(__dirname, '../workflows/ankiquest_screenshot_store.yml'), 'utf8').replaceAll('\r\n', '\n');

test('each supported target branch produces screenshot baselines on push', () => {
  const branches = store.match(/^  push:\n    branches:\n((?:      - .+\n)+)/m)[1];
  for (const branch of ['main', 'ankiquest']) {
    assert.match(branches, new RegExp(`^      - ${branch}$`, 'm'), `${branch} needs its own baseline`);
  }
});

test('expired baselines can be regenerated manually without a source change', () => {
  assert.match(store, /^  workflow_dispatch:/m);
});

test('failed, empty, or PR recordings cannot be published as branch baselines', () => {
  const start = store.indexOf('      - name: Upload screenshot baseline\n');
  assert.notEqual(start, -1);
  const end = store.indexOf('\n      - ', start + 1);
  const upload = store.slice(start, end === -1 ? undefined : end);
  assert.doesNotMatch(upload, /always\(\)/);
  assert.match(upload, /github\.event_name != 'pull_request'/);
  assert.match(upload, /if-no-files-found:\s*error/);
});
