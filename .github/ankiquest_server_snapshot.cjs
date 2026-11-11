// SPDX-License-Identifier: AGPL-3.0-only
// Server checkouts are read as JSON data only. No server code is executed.
const fs = require("node:fs");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const RESOURCES = "AnkiDroid/src/test/resources";
const PIN = "ankiquest-server.sha";
const SHA = /^[0-9a-f]{40}$/;
const PAIRS = [
    ["docs/api/examples", "ankiquest-api"],
    ["tests/fixtures", "ankiquest"],
];
const CONTENT = Object.freeze({ SERVER: "server", CATALOG: "catalog" });

function readPin(root) {
    const pin = fs.readFileSync(path.join(root, RESOURCES, PIN), "utf8").trim();
    if (!SHA.test(pin)) {
        throw new Error(`${PIN} must contain a full commit SHA`);
    }
    return pin;
}

function serverRef(root, override = "") {
    if (!override) {
        return readPin(root);
    }
    if (!/^[A-Za-z0-9][A-Za-z0-9._/-]*$/.test(override)) {
        throw new Error("Invalid server_ref");
    }
    return override;
}

function jsonFiles(directory, content = CONTENT.SERVER) {
    const files = new Map();
    for (const name of fs.readdirSync(directory).sort()) {
        if (!name.endsWith(".json") || (content === CONTENT.CATALOG && name === "names.json")) {
            continue;
        }
        if (name === "names.json") {
            throw new Error("Server examples must not replace Android names.json");
        }
        const file = path.join(directory, name);
        const stat = fs.lstatSync(file);
        if (!stat.isFile() || stat.isSymbolicLink()) {
            throw new Error(`Expected a regular JSON file: ${file}`);
        }
        const bytes = fs.readFileSync(file);
        JSON.parse(bytes.toString("utf8"));
        files.set(name, bytes);
    }
    if (!files.size) {
        throw new Error(`No JSON data in ${directory}`);
    }
    return files;
}

function differences(expected, actual, label) {
    const errors = [];
    for (const name of new Set([...expected.keys(), ...actual.keys()])) {
        if (!expected.has(name)) {
            errors.push(`${label}/${name}: not in the pinned server`);
        } else if (!actual.has(name)) {
            errors.push(`${label}/${name}: missing`);
        } else if (!expected.get(name).equals(actual.get(name))) {
            errors.push(`${label}/${name}: differs`);
        }
    }
    return errors;
}

function compare(root, server, commit) {
    if (commit !== undefined && readPin(root) !== commit) {
        throw new Error("Server checkout does not match the pin");
    }
    const errors = PAIRS.flatMap(([source, target]) =>
        differences(
            jsonFiles(path.join(server, source)),
            jsonFiles(path.join(root, RESOURCES, target), contentFor(target)),
            target,
        ),
    );
    if (errors.length) {
        throw new Error(errors.join("\n"));
    }
}

function contentFor(target) {
    return target === "ankiquest-api" ? CONTENT.CATALOG : CONTENT.SERVER;
}

function replaceJson(directory, files, content) {
    fs.mkdirSync(directory, { recursive: true });
    for (const name of fs.readdirSync(directory)) {
        if (fs.lstatSync(path.join(directory, name)).isSymbolicLink()) {
            throw new Error(`Refusing a resource symlink: ${name}`);
        }
        if (
            name.endsWith(".json") &&
            !(content === CONTENT.CATALOG && name === "names.json") &&
            !files.has(name)
        ) {
            // Only immediate JSON files in the fixed resource directory are removed.
            fs.unlinkSync(path.join(directory, name));
        }
    }
    for (const [name, bytes] of files) {
        fs.writeFileSync(path.join(directory, name), bytes);
    }
}

function writableResources(root) {
    let current = path.resolve(root);
    for (const part of RESOURCES.split("/")) {
        current = path.join(current, part);
        if (fs.lstatSync(current, { throwIfNoEntry: false })?.isSymbolicLink()) {
            throw new Error("Resource directories cannot be symlinks");
        }
    }
    for (const name of ["ankiquest-api", "ankiquest", PIN]) {
        const file = path.join(current, name);
        if (fs.lstatSync(file, { throwIfNoEntry: false })?.isSymbolicLink()) {
            throw new Error("Snapshot paths cannot be symlinks");
        }
    }
}

function copy(root, server, commit) {
    if (!SHA.test(commit)) {
        throw new Error("The new pin must be a full commit SHA");
    }
    // Read and validate all data before changing either committed directory.
    const snapshots = PAIRS.map(([source, target]) => [
        target,
        jsonFiles(path.join(server, source)),
    ]);
    writableResources(root);
    for (const [target, files] of snapshots) {
        replaceJson(path.join(root, RESOURCES, target), files, contentFor(target));
    }
    fs.writeFileSync(path.join(root, RESOURCES, PIN), commit + "\n");
}

function examples(root, source) {
    writableResources(root);
    replaceJson(path.join(root, RESOURCES, "ankiquest-api"), jsonFiles(source), CONTENT.CATALOG);
}

if (require.main === module) {
    try {
        const [command, server] = process.argv.slice(2);
        const root = process.cwd();
        if (command === "ref") {
            console.log(serverRef(root, process.env.SERVER_REF || ""));
        } else if (command === "examples") {
            examples(root, server);
        } else if (command === "compare") {
            compare(root, server);
        } else if (command === "verify" || command === "copy") {
            const commit = execFileSync("git", ["-C", server, "rev-parse", "HEAD"], {
                encoding: "utf8",
            }).trim();
            if (command === "copy") {
                copy(root, server, commit);
            } else {
                compare(root, server, commit);
            }
        } else {
            throw new Error("Use ref, verify, compare, copy or examples");
        }
    } catch (error) {
        console.error(error.message);
        process.exitCode = 1;
    }
}

module.exports = { readPin, serverRef, compare, copy, examples };
