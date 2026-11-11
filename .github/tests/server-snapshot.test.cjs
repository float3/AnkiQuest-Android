// SPDX-License-Identifier: AGPL-3.0-only
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const root = path.resolve(__dirname, "../..");
const resources = path.join(root, "AnkiDroid/src/test/resources");
const os = require("node:os");
const snapshot = require("../ankiquest_server_snapshot.cjs");
const pin = "a".repeat(40);

function fixture(t) {
    const folder = fs.mkdtempSync(path.join(os.tmpdir(), "aq-server-snapshot-"));
    t.after(() => fs.rmSync(folder, { recursive: true, force: true }));
    const app = path.join(folder, "app");
    const server = path.join(folder, "server");
    const data = path.join(app, "AnkiDroid/src/test/resources");
    for (const directory of [
        path.join(server, "docs/api/examples"),
        path.join(server, "tests/fixtures"),
        path.join(data, "ankiquest-api"),
    ]) {
        fs.mkdirSync(directory, { recursive: true });
    }
    fs.writeFileSync(path.join(server, "docs/api/examples/profile.json"), '{"xp": 5}\n');
    fs.writeFileSync(path.join(server, "tests/fixtures/clients.json"), '{"days": []}\n');
    fs.writeFileSync(path.join(data, "ankiquest-api/names.json"), '{"consumed":["profile"]}\n');
    return { app, server, data };
}

test("copy records exact bytes and the pin while preserving the reviewed catalog", t => {
    const { app, server, data } = fixture(t);
    snapshot.copy(app, server, pin);
    assert.equal(snapshot.readPin(app), pin);
    assert.doesNotThrow(() => snapshot.compare(app, server, pin));
    assert.equal(
        fs.readFileSync(path.join(data, "ankiquest-api/names.json"), "utf8"),
        '{"consumed":["profile"]}\n',
    );
    assert.equal(snapshot.serverRef(app), pin);
    assert.equal(snapshot.serverRef(app, "codex/coordinated-change"), "codex/coordinated-change");
    assert.throws(() => snapshot.serverRef(app, "master\nref=other"), /Invalid server_ref/);
    assert.throws(() => snapshot.compare(app, server, "b".repeat(40)), /does not match/);
});

test("changed, missing and extra committed examples fail verification", t => {
    const { app, server, data } = fixture(t);
    snapshot.copy(app, server, pin);
    const examples = path.join(data, "ankiquest-api");
    fs.writeFileSync(path.join(examples, "profile.json"), '{"xp": 6}\n');
    assert.throws(() => snapshot.compare(app, server, pin), /profile.json: differs/);
    fs.renameSync(path.join(examples, "profile.json"), path.join(examples, "unexpected.json"));
    assert.throws(
        () => snapshot.compare(app, server, pin),
        /profile.json: missing[\s\S]*unexpected.json: not in/,
    );
});

test("new server examples stay unclassified, removed ones disappear, catalog edits survive", t => {
    const { app, server, data } = fixture(t);
    snapshot.copy(app, server, pin);
    fs.writeFileSync(path.join(server, "docs/api/examples/new_feature.json"), "{}\n");
    fs.unlinkSync(path.join(server, "docs/api/examples/profile.json"));
    snapshot.copy(app, server, "b".repeat(40));
    assert.deepEqual(fs.readdirSync(path.join(data, "ankiquest-api")).sort(), [
        "names.json",
        "new_feature.json",
    ]);
    assert.deepEqual(
        JSON.parse(fs.readFileSync(path.join(data, "ankiquest-api/names.json"), "utf8")),
        { consumed: ["profile"] },
    );
    // The strict Kotlin ownership test must decide how to classify the new name.
    assert.doesNotThrow(() => snapshot.compare(app, server, "b".repeat(40)));
});

test("bad or empty server data never partially replaces the committed snapshot", t => {
    const { app, server, data } = fixture(t);
    snapshot.copy(app, server, pin);
    fs.writeFileSync(path.join(server, "docs/api/examples/profile.json"), '{"xp": 8}\n');
    fs.writeFileSync(path.join(server, "tests/fixtures/clients.json"), "not json");
    assert.throws(() => snapshot.copy(app, server, "b".repeat(40)));
    assert.equal(
        fs.readFileSync(path.join(data, "ankiquest-api/profile.json"), "utf8"),
        '{"xp": 5}\n',
    );
    assert.equal(snapshot.readPin(app), pin);
    fs.unlinkSync(path.join(server, "docs/api/examples/profile.json"));
    assert.throws(
        () => snapshot.examples(app, path.join(server, "docs/api/examples")),
        /No JSON data/,
    );
});

test("the server baseline is an immutable commit, with explicit example ownership", () => {
    assert.match(
        fs.readFileSync(path.join(resources, "ankiquest-server.sha"), "utf8").trim(),
        /^[0-9a-f]{40}$/,
    );
    const names = JSON.parse(
        fs.readFileSync(path.join(resources, "ankiquest-api/names.json"), "utf8"),
    );
    assert.deepEqual(Object.keys(names).sort(), [
        "boards",
        "challenges",
        "consumed",
        "not_yet",
        "unused",
    ]);
});

test("normal contract and fixture runs never substitute server master", () => {
    for (const name of ["ankiquest_api_contract.yml", "ankiquest_fixtures.yml"]) {
        const workflow = fs.readFileSync(path.join(root, ".github/workflows", name), "utf8");
        assert.doesNotMatch(
            workflow,
            /inputs\.server_ref\s*\|\|\s*'master'|raw\.githubusercontent\.com\/float3\/ankiquest\/master/,
        );
        assert.match(workflow, /server_ref:/);
        assert.match(workflow, /ankiquest_server_snapshot\.cjs/);
    }
});
