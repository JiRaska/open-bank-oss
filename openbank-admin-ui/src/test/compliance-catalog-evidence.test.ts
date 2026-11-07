import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { loadComplianceCatalog } from '../lib/governance/compliance'

let tempDir: string | undefined
const originalControls = process.env.OPENBANK_COMPLIANCE_CONTROLS
const originalPosture = process.env.OPENBANK_SECURITY_GRAPH

async function prepare(posture: unknown) {
  tempDir = await mkdtemp(path.join(tmpdir(), 'openbank-compliance-'))
  const controlsPath = path.join(tempDir, 'compliance-controls.yaml')
  const posturePath = path.join(tempDir, 'security-graph.json')
  await writeFile(controlsPath, [
    'frameworks:',
    '  - id: TEST',
    '    name: Test framework',
    'controls:',
    '  - id: authored-enforced',
    '    title: Authored enforced control',
    '    category: Test',
    '    frameworks: [TEST]',
    '    references: []',
    '    evidence: Evidence text',
    '    status: enforced',
    '    derivedFrom: supplyChain.enforced',
  ].join('\n'))
  if (posture !== undefined) await writeFile(posturePath, JSON.stringify(posture))
  process.env.OPENBANK_COMPLIANCE_CONTROLS = controlsPath
  process.env.OPENBANK_SECURITY_GRAPH = posturePath
}

afterEach(async () => {
  if (originalControls === undefined) delete process.env.OPENBANK_COMPLIANCE_CONTROLS
  else process.env.OPENBANK_COMPLIANCE_CONTROLS = originalControls
  if (originalPosture === undefined) delete process.env.OPENBANK_SECURITY_GRAPH
  else process.env.OPENBANK_SECURITY_GRAPH = originalPosture
  if (tempDir) await rm(tempDir, { recursive: true, force: true })
  tempDir = undefined
})

describe('compliance catalogue derived evidence', () => {
  it('does not preserve an authored enforced status when its derived signal is missing', async () => {
    await prepare({ schema: 'openbank.security-posture/v1', source: 'fixture source', network: {}, collectedAt: '2026-10-19T06:35:32.599Z', supplyChain: {} })
    const catalog = await loadComplianceCatalog()

    expect(catalog?.controls[0]).toMatchObject({
      status: 'unknown', declaredStatus: 'enforced', signalState: 'unknown', live: false,
    })
    expect(catalog?.coverage.TEST).toEqual({ enforced: 0, total: 1 })
    expect(catalog?.posture).toEqual({ source: 'fixture source', collectedAt: '2026-10-19T06:35:32.599Z' })
  })

  it('keeps a source-manifest false input separate from operational status', async () => {
    await prepare({ schema: 'openbank.security-posture/v1', source: 'fixture source', network: {}, collectedAt: '2026-10-19T06:35:32.599Z', supplyChain: { enforced: false } })
    const catalog = await loadComplianceCatalog()

    expect(catalog?.controls[0]).toMatchObject({
      status: 'unknown', declaredStatus: 'enforced', signalState: 'false', live: false,
    })
    expect(catalog?.coverage.TEST).toEqual({ enforced: 0, total: 1 })
  })

  it('does not promote a positive source-manifest input to operational enforcement', async () => {
    await prepare({ schema: 'openbank.security-posture/v1', source: 'fixture source', network: {}, collectedAt: '2026-10-19T06:35:32.599Z', supplyChain: { enforced: true } })
    const catalog = await loadComplianceCatalog()

    expect(catalog?.controls[0]).toMatchObject({
      status: 'unknown', declaredStatus: 'enforced', signalState: 'true', live: false,
    })
    expect(catalog?.coverage.TEST).toEqual({ enforced: 0, total: 1 })
  })

  it('does not accept malformed non-boolean signal values as evidence', async () => {
    await prepare({ schema: 'openbank.security-posture/v1', source: 'fixture source', network: {}, collectedAt: null, supplyChain: { enforced: 'true' } })
    const catalog = await loadComplianceCatalog()

    expect(catalog?.controls[0]).toMatchObject({ status: 'unknown', declaredStatus: 'enforced', signalState: 'unknown' })
    expect(catalog?.posture?.collectedAt).toBeNull()
  })
})
