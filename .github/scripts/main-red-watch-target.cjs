// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { spawnSync } = require('node:child_process')

const WORKFLOW = 'Auto-update armed PRs'
const NAME = 'auto-update-target-evidence-'
const MARKER = /<!-- main-red-target-pr:(\d+) -->/g
const ANY_MARKER = /<!-- main-red-target-pr:[^>]*-->/g

function targetPrs(body) {
  if (typeof body !== 'string') return null
  const matches = [...body.matchAll(MARKER)]
  if ([...body.matchAll(ANY_MARKER)].length !== matches.length) return null
  if (!matches.length) return null // Legacy issues lack a target; job/step green is insufficient.
  const prs = matches.map(match => Number(match[1]))
  if (prs.some(n => !Number.isSafeInteger(n) || n < 1) || new Set(prs).size !== prs.length) return null
  return prs
}

function withTargets(body, prs) {
  if (!Array.isArray(prs) || prs.some(n => !Number.isSafeInteger(n) || n < 1)) {
    throw new Error('Malformed recovery targets')
  }
  const existing = targetPrs(body) || []
  const all = [...new Set([...existing, ...prs])].sort((a, b) => a - b)
  const clean = body.replace(MARKER, '').trimEnd()
  return all.length ? `${clean}\n${all.map(n => `<!-- main-red-target-pr:${n} -->`).join('\n')}` : clean
}

function validateEvidence(report, verdict) {
  if (!report || report.version !== 1 || report.complete !== true || report.dry_run !== false ||
      report.run_id !== verdict.run_id || report.attempt !== verdict.attempt ||
      report.main_sha !== verdict.head_sha || !/^[a-f0-9]{40}$/.test(report.main_sha) ||
      !Array.isArray(report.targets)) throw new Error('Malformed or mismatched target evidence')
  const seen = new Set()
  for (const target of report.targets) {
    if (!target || !Number.isSafeInteger(target.pr) || target.pr < 1 || seen.has(target.pr) ||
        !/^[a-f0-9]{40}$/.test(target.head_sha) ||
        typeof target.budget_validated !== 'boolean' || typeof target.history_error !== 'boolean' ||
        (target.budget_validated && (!Number.isSafeInteger(target.update_count) || target.update_count < 0)) ||
        (!target.budget_validated && target.update_count !== null) ||
        (target.budget_validated && target.history_error)) {
      throw new Error('Malformed target entry')
    }
    seen.add(target.pr)
  }
  return report
}

function targetsCovered(body, report) {
  const targets = targetPrs(body)
  if (!targets || !report) return false
  const byPr = new Map(report.targets.map(target => [target.pr, target]))
  return targets.every(n => byPr.get(n)?.budget_validated === true)
}

async function loadTargetEvidence(github, repo, verdict) {
  if (verdict.workflow !== WORKFLOW) return null
  const response = await github.rest.actions.listWorkflowRunArtifacts({
    ...repo, run_id: verdict.run_id, per_page: 100,
  })
  const data = response.data
  if (!Number.isSafeInteger(data?.total_count) || !Array.isArray(data.artifacts) ||
      data.total_count !== data.artifacts.length || data.artifacts.length > 100) {
    throw new Error('Incomplete target-evidence artifact inventory')
  }
  const matches = data.artifacts.filter(item => item.name === `${NAME}${verdict.attempt}` && !item.expired)
  if (!matches.length) return null
  if (matches.length !== 1 || !Number.isSafeInteger(matches[0].id)) {
    throw new Error('Ambiguous target-evidence artifact')
  }
  const archive = await github.rest.actions.downloadArtifact({
    ...repo, artifact_id: matches[0].id, archive_format: 'zip',
  })
  const bytes = Buffer.from(archive.data)
  if (!bytes.length || bytes.length > 1024 * 1024) throw new Error('Invalid target-evidence archive size')
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'main-red-target-'))
  try {
    const zip = path.join(dir, 'evidence.zip')
    fs.writeFileSync(zip, bytes)
    const listing = spawnSync('unzip', ['-Z1', zip], { encoding: 'utf8', timeout: 5000 })
    if (listing.status !== 0 || listing.stdout.trim() !== 'auto-update-target-report.json') {
      throw new Error('Malformed target-evidence archive')
    }
    const extracted = spawnSync('unzip', ['-p', zip, 'auto-update-target-report.json'],
      { encoding: 'utf8', maxBuffer: 1024 * 1024, timeout: 5000 })
    if (extracted.status !== 0) throw new Error('Unreadable target-evidence report')
    return validateEvidence(JSON.parse(extracted.stdout), verdict)
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
}

module.exports = { WORKFLOW, targetPrs, withTargets, validateEvidence, targetsCovered, loadTargetEvidence }
