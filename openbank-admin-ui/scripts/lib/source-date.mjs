// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
// Deterministic provenance timestamp for COMMITTED derived artifacts (issue #2621).
//
// The problem it solves: three artifacts are generated into the repo and committed
// (cluster-topology.json, infra-lifecycle.json, infra-vulns.json). Each carried
// `new Date().toISOString()`, so every regeneration rewrote that line with a different
// value — which means any two PRs that regenerate them conflict BY CONSTRUCTION, not
// because their content disagrees but because they ran at different instants. That is
// exactly what put #2247 on a rebase treadmill and what turned #2618 DIRTY the moment
// it was cut; the conflicting hunk was the timestamp, not the image tag.
//
// The fix is the reproducible-builds one: make the artifact a pure function of its
// inputs. The stamp is the commit time of the newest INPUT the generator read, so two
// runs off the same base produce byte-identical bytes and only genuine content
// differences can conflict. It is also strictly more truthful — "as of the commit this
// was derived from" is the staleness an operator needs, whereas "when CI happened to
// run" advances while nothing changed, i.e. lies in the reassuring direction.
//
// Deliberately Node built-ins only (no node_modules) — same constraint the three
// generators are written under.

import { execFileSync } from 'child_process'

/**
 * Commit time of the newest input, as an ISO-8601 UTC string.
 *
 * Resolution order:
 *  1. SOURCE_DATE_EPOCH (the reproducible-builds convention) if set and numeric.
 *  2. `git log -1 --format=%cI -- <paths>` in `repo`.
 *  3. `null` — the honest "unknown", already a valid value in every consumer
 *     (build-push-admin-ui.sh writes `"generatedAt": null` on generator failure and
 *     the BFF fallbacks render it). NEVER fall back to the wall clock: that would
 *     reintroduce the non-determinism this module exists to remove.
 *
 * @param {string} repo  repository root
 * @param {string[]} paths  repo-relative pathspecs the generator read
 * @returns {string|null}
 */
export function sourceDate(repo, paths = []) {
  const epoch = process.env.SOURCE_DATE_EPOCH
  if (epoch && /^\d+$/.test(epoch.trim())) {
    return new Date(Number(epoch.trim()) * 1000).toISOString()
  }
  const args = ['-C', repo, 'log', '-1', '--format=%cI']
  if (paths.length) args.push('--', ...paths)
  let raw
  try {
    raw = execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
  } catch {
    return null // not a git checkout (e.g. a bare Docker build context)
  }
  if (!raw) return null // pathspec matched nothing tracked
  const d = new Date(raw)
  return Number.isNaN(d.getTime()) ? null : d.toISOString()
}

function git(repo, args) {
  return execFileSync('git', ['-C', repo, ...args],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
}

// All three generators walk GitOps. A commit changing only image: lines cannot
// alter topology's kinds/namespaces/security context. The lifecycle and Grype
// projections additionally read image refs named in the registry: those pins
// remain semantic inputs and must advance provenance.
function isInertImagePinCommit(repo, commit, registryImageGreps) {
  const parent = `${commit}^`
  const files = git(repo, ['diff', '--name-only', '-z', parent, commit]).split('\0').filter(Boolean)
  if (!files.length || files.some(f => !f.startsWith('openbank-infra/gitops/') || !/\.ya?ml$/.test(f))) {
    return false
  }
  const imageLine = /^\s*image:\s*\S+\s*$/
  for (const file of files) {
    const before = git(repo, ['show', `${parent}:${file}`]).split('\n')
    const after = git(repo, ['show', `${commit}:${file}`]).split('\n')
    if (before.length !== after.length) return false
    const edits = before.map((line, i) => [line, after[i]]).filter(([a, b]) => a !== b)
    if (!edits.length || edits.some(([a, b]) => !imageLine.test(a) || !imageLine.test(b))) {
      return false
    }
    if (edits.some(([a, b]) => registryImageGreps.some(grep => a.includes(grep) || b.includes(grep)))) {
      return false
    }
  }
  return true
}

/** Date of the newest semantic repository input, skipping proven inert image pins.
 * Never trust a committed generatedAt/scannedAt field: derived JSON may be stale
 * or edited. If history is unavailable, retain the conservative latest-input date.
 */
export function sourceDateForSemanticInputs(repo, paths, registryImageGreps = []) {
  if (process.env.SOURCE_DATE_EPOCH) return sourceDate(repo, paths)
  let revision = 'HEAD'
  for (let i = 0; i < 200; i++) {
    let commit
    try {
      commit = git(repo, ['log', '-1', '--format=%H', revision, '--', ...paths])
      if (!commit) return null
      let inert = false
      try { inert = isInertImagePinCommit(repo, commit, registryImageGreps) } catch { /* root or shallow history */ }
      if (!inert) {
        const raw = git(repo, ['show', '-s', '--format=%cI', commit])
        const parsed = new Date(raw)
        return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString()
      }
      revision = `${commit}^`
    } catch {
      return sourceDate(repo, paths)
    }
  }
  return sourceDate(repo, paths)
}
