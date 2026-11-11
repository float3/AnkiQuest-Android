# Pinned AnkiQuest server data

`AnkiDroid/src/test/resources/ankiquest-server.sha` names the exact server
commit used by Android's committed API examples and shared fixtures.
Normal PR and main builds test those committed files. A cheap isolated job
checks them against that SHA; it never substitutes server master or runs
server code. The Gradle contract job receives no server checkout or new data
unless a manual `server_ref` is explicitly supplied.

`ankiquest-api/names.json` assigns every example to exactly one group:

- `consumed`: exercised by the Android API consumer tests.
- `boards` and `challenges`: additional consumed example groups, also read by
  the server compatibility guard.
- `unused`: reviewed examples Android intentionally does not consume.
- `not_yet`: examples for a future Android feature. Move them to a consumed
  group when its consumer assertions land.

The contract test requires exact set equality, including `not_yet`. New or
removed examples need a decision in this file. The snapshot verifier ignores
only `names.json` when comparing server data. Prettier excludes the copied
JSON so formatting cannot break byte equality; the ownership catalog keeps
normal repository formatting.

## Bringing in server changes

`ankiquest_bump_server.yml` runs daily and on dispatch. It copies server
master's `docs/api/examples/*.json` and `tests/fixtures/*.json`, records the
commit, and pushes only `bot/server-bump`. It opens or updates a PR whose body
lists the server commits since Android main's pin. Existing review fixes and
notes survive updates. The workflow merges main into an existing bump
branch; it never force-pushes. Resolve any merge conflict on that branch.

If a new example fails the ownership test, fix the bump PR itself. Classify
the example in `names.json` and add consumer assertions when appropriate.
Main remains green on its existing pin. An uninterrupted failure lasting
more than two days triggers one comment on that PR and issue #203. Daily
pushes do not reset that period; an intervening green head does.
An interrupted alert retries only the destination that did not receive it.

The bot uses the existing `UPSTREAM_SYNC_TOKEN` so its pushes start normal PR
CI. The repository owner must grant that token **Contents**, **Pull requests**
and **Issues** read/write on this repository. No token value belongs in the
repository. Scheduled automation is restricted to `float3/AnkiQuest-Android`.
It executes reviewed helpers from main, retained outside the bump checkout;
server data and unmerged changes on the bump branch are never executed by
the privileged updater.

## Coordinated changes and ad-hoc checks

An Android PR can pin a commit on a server feature branch. In a local clean
server checkout at that commit, run from the Android repository:

```sh
node .github/ankiquest_server_snapshot.cjs copy ../ankiquest
node .github/ankiquest_server_snapshot.cjs verify ../ankiquest
./gradlew :AnkiDroid:testFullDebugUnitTest --tests 'com.ichi2.anki.ankiquest.AnkiquestApiContractTest'
```

Commit the data, SHA and any ownership/test changes together. No merge
ordering is required. When the server change reaches master, the next bump
has identical data or a small follow-up.

Both the API-contract and shared-fixtures workflows accept an optional
`server_ref` on manual dispatch. Blank means the committed pin. An explicit
ref tests its API examples or compares its data with the committed snapshot;
it does not change main. Missing/empty server examples fail clearly.

The server's `android-contract` check rejects removal or changed JSON types
for Android's consumed examples; additive changes remain allowed. Server
PRs changing examples should name their Android follow-up or say "picked up
by bump PR".

## Owner settings

Require PRs and required checks on Android `main` and server `master`, including
for administrators. Make the server's `android-contract` check required.
These are repository administration settings; this change cannot enforce
them from code. Neither this workflow nor agents should push default
branches directly.

Run workflow-helper regressions with `node --test .github/tests/*.test.cjs`.
