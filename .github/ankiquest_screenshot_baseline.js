// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from wait_for_screenshot_baseline.js for the fork's workflow and manual recordings.

const WORKFLOW = "ankiquest_screenshot_store.yml";
const ATTEMPTS = 60;
const RETRY_MS = 30_000;

module.exports = async function waitForScreenshotBaseline({
    github,
    context,
    core,
    sleep = delay => new Promise(resolve => setTimeout(resolve, delay)),
}) {
    const base = context.payload.pull_request?.base || {
        sha: context.sha,
        ref: context.ref.replace(/^refs\/heads\//, ""),
    };

    // Store jobs can overlap or outlive the branch tip. Only this commit's artifact is safe.
    for (let attempt = 0; attempt < ATTEMPTS; attempt++) {
        const {
            data: { workflow_runs: runs },
        } = await github.rest.actions.listWorkflowRuns({
            ...context.repo,
            workflow_id: WORKFLOW,
            // No status filter: GitHub's status=success can omit a finished run.
            // The checks below pick a completed, successful run themselves.
            head_sha: base.sha,
            per_page: 100,
        });
        for (const run of runs) {
            if (
                run.head_sha !== base.sha ||
                run.status !== "completed" ||
                run.conclusion !== "success" ||
                !["push", "workflow_dispatch"].includes(run.event)
            ) {
                continue;
            }
            const {
                data: { artifacts },
            } = await github.rest.actions.listWorkflowRunArtifacts({
                ...context.repo,
                run_id: run.id,
                per_page: 100,
            });
            if (artifacts.some(artifact => artifact.name === "screenshot" && !artifact.expired)) {
                core.info(`Using screenshot baseline from ${base.sha}: run ${run.id}`);
                return run.id;
            }
        }
        core.info(`Waiting for screenshot baseline ${base.sha} (${base.ref})`);
        await sleep(RETRY_MS);
    }
    throw new Error(
        `No successful screenshot baseline for ${base.sha} after 30 minutes. ` +
            `Run ${WORKFLOW} on a branch at that commit, then rerun this check.`,
    );
};
