// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Process manifests must validate against ProcessSchema and the derived score
// must be sane. loadProcess() throws (Zod) on a malformed/drifted manifest, so
// this fails the same way `next build` does — the manifest is the contract.
import { describe, it, expect } from 'vitest'
import { loadAllProcesses, listProcessSlugs, loadProcess } from '@/lib/docs/process/load'
import { overallScore } from '@/lib/docs/process/schema'

describe('process manifests', () => {
  it('every manifest validates against ProcessSchema', () => {
    const procs = loadAllProcesses() // loadProcess throws on an invalid manifest
    expect(procs.length).toBeGreaterThan(0)
  })

  it('ships the auth-flow process', () => {
    expect(listProcessSlugs()).toContain('auth-flow')
    const auth = loadProcess('auth-flow')
    expect(auth.slug).toBe('auth-flow')
    expect(auth.story.length).toBeGreaterThan(0)
    expect(auth.controls.length).toBeGreaterThan(0)
  })

  it('derives a weighted score in 0..100 for every process', () => {
    for (const p of loadAllProcesses()) {
      const score = overallScore(p.controls)
      expect(score, p.slug).toBeGreaterThanOrEqual(0)
      expect(score, p.slug).toBeLessThanOrEqual(100)
    }
  })

  it('keeps tech-node ids unique within a process', () => {
    for (const p of loadAllProcesses()) {
      const ids = p.tech.flatMap((z) => z.nodes.map((n) => n.id))
      expect(new Set(ids).size, p.slug).toBe(ids.length)
    }
  })
})
