// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Runtime state lives outside main: workflow_run cannot rely on delivery of its
// own event, and an issue does not exist before the first red verdict. One file
// per watched workflow avoids cross-workflow lost updates.
const STATE_BRANCH = 'main-red-watch-state'

function parseCheckpoint(value, workflowId) {
  if (!value || value.version !== 1 || value.workflow_id !== workflowId ||
      !Number.isSafeInteger(value.run_id) || value.run_id < 1 ||
      !Number.isSafeInteger(value.attempt) || value.attempt < 0) {
    throw new Error('Malformed main-red checkpoint; refusing to skip source runs')
  }
  return value
}

function checkpointPath(workflowId) {
  if (!Number.isSafeInteger(workflowId) || workflowId < 1) throw new Error('Invalid workflow ID')
  return `.main-red-watch/${workflowId}.json`
}

async function ensureStateBranch(github, repo) {
  try {
    await github.rest.git.getRef({ ...repo, ref: `heads/${STATE_BRANCH}` })
    return
  } catch (error) {
    if (error.status !== 404) throw error
  }
  const main = await github.rest.git.getRef({ ...repo, ref: 'heads/main' })
  try {
    await github.rest.git.createRef({ ...repo, ref: `refs/heads/${STATE_BRANCH}`, sha: main.data.object.sha })
  } catch (error) {
    // Another workflow can create the branch between GET and POST.
    if (error.status !== 422) throw error
    await github.rest.git.getRef({ ...repo, ref: `heads/${STATE_BRANCH}` })
  }
}

async function readCheckpoint(github, repo, workflowId) {
  const path = checkpointPath(workflowId)
  let response
  try {
    response = await github.rest.repos.getContent({ ...repo, path, ref: STATE_BRANCH })
  } catch (error) {
    if (error.status === 404) return { checkpoint: null, sha: null }
    throw error
  }
  const data = response.data
  if (!data || data.type !== 'file' || !data.sha || data.encoding !== 'base64' || !data.content) {
    throw new Error('Malformed main-red checkpoint file')
  }
  const parsed = JSON.parse(Buffer.from(data.content, 'base64').toString('utf8'))
  return { checkpoint: parseCheckpoint(parsed, workflowId), sha: data.sha }
}

async function writeCheckpoint(github, repo, workflowId, checkpoint, previousSha) {
  parseCheckpoint(checkpoint, workflowId)
  const content = Buffer.from(`${JSON.stringify(checkpoint)}\n`, 'utf8').toString('base64')
  const args = {
    ...repo, path: checkpointPath(workflowId), branch: STATE_BRANCH,
    message: `chore(main-red-watch): record workflow ${workflowId} through ${checkpoint.run_id}/${checkpoint.attempt}`,
    content,
  }
  if (previousSha) args.sha = previousSha
  // Contents API SHA is a compare-and-swap. A competing writer fails and must
  // reread/replay; never overwrite a newer cursor with an older one.
  const response = await github.rest.repos.createOrUpdateFileContents(args)
  if (!response.data?.content?.sha) throw new Error('Checkpoint write returned no content SHA')
  return response.data.content.sha
}

module.exports = {
  STATE_BRANCH, checkpointPath, parseCheckpoint, ensureStateBranch, readCheckpoint, writeCheckpoint,
}
