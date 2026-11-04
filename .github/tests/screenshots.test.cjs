// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-License-Identifier: GPL-3.0-or-later
const assert = require('node:assert/strict');
const childProcess = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
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
  assert.match(download, /branch: \$\{\{ steps\.source\.outputs\.branch \}\}/);
  assert.match(download, /workflow: \$\{\{ steps\.source\.outputs\.workflow \}\}/);
  assert.ok(compare.indexOf('Download base branch screenshots') < compare.indexOf('Compare screenshots'));
});

test('comparison selects the PR base branch or the pushed branch', () => {
  const source = step(compare.replaceAll('\r\n', '\n'), 'Pick the baseline workflow');
  const expression = source.match(/ BRANCH: \$\{\{ (.+) \}\}/)[1];
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

const bash = (() => {
  for (const candidate of process.platform === 'win32'
    ? ['C:/Program Files/Git/bin/bash.exe', 'bash']
    : ['bash']) {
    const probe = childProcess.spawnSync(candidate, ['-c', 'exit 0']);
    if (probe.status === 0) return candidate;
  }
  return undefined;
})();

function pickBaseline({ branch, defaultBranch, published }) {
  const source = step(compare.replaceAll('\r\n', '\n'), 'Pick the baseline workflow');
  const script = source.slice(source.indexOf('run: |\n') + 'run: |\n'.length)
    .split('\n').map(line => line.replace(/^ {10}/, '')).join('\n');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'screenshot-source-'));
  try {
    // Fake gh: prints the count of non-PR successful runs for "<workflow>@<branch>".
    fs.writeFileSync(path.join(dir, 'gh'), [
      '#!/usr/bin/env bash',
      'url="$2"; workflow="${url#*/workflows/}"; workflow="${workflow%%/*}"',
      'branch="${url#*branch=}"; branch="${branch%%&*}"',
      'case " $PUBLISHED " in *" $workflow@$branch "*) echo 1 ;; *) echo 0 ;; esac',
      '',
    ].join('\n'), { mode: 0o755 });
    fs.writeFileSync(path.join(dir, 'script.sh'), script);
    const output = path.join(dir, 'output');
    const result = childProcess.spawnSync(bash, ['script.sh'], {
      cwd: dir,
      encoding: 'utf8',
      env: {
        ...process.env,
        PATH: `${dir}${path.delimiter}${process.env.PATH}`,
        GITHUB_REPOSITORY: 'float3/AnkiQuest-Android',
        GITHUB_OUTPUT: output,
        BRANCH: branch,
        DEFAULT_BRANCH: defaultBranch,
        PUBLISHED: published.join(' '),
      },
    });
    assert.equal(result.status, 0, result.stderr);
    return Object.fromEntries(fs.readFileSync(output, 'utf8').trim().split('\n').map(line => line.split('=')));
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

test('comparison falls back to the default branch baseline for stacked PRs', { skip: !bash && 'bash unavailable' }, () => {
  const ours = 'ankiquest_screenshot_store.yml';
  const upstream = 'screenshot_store.yml';
  for (const [branch, published, expected] of [
    ['main', [`${ours}@main`], { workflow: ours, branch: 'main' }],
    ['feature', [`${ours}@feature`, `${ours}@main`], { workflow: ours, branch: 'feature' }],
    ['feature', [`${ours}@main`], { workflow: ours, branch: 'main' }],
    ['feature', [`${upstream}@feature`, `${ours}@main`], { workflow: upstream, branch: 'feature' }],
    ['feature', [`${upstream}@main`], { workflow: upstream, branch: 'main' }],
    ['feature', [], { workflow: ours, branch: 'feature' }],
  ]) {
    assert.deepEqual(pickBaseline({ branch, defaultBranch: 'main', published }), expected, `${branch} ${published}`);
  }
});
