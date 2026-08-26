import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const workflow = readFileSync(resolve(process.cwd(), '../.github/workflows/admin-ui-deploy.yml'), 'utf8')

describe('Test Intelligence history pagination', () => {
  it('collects 30 snapshots from their owning deploy runs instead of noisy repo artifact pages', () => {
    const historyStage = workflow.slice(
      workflow.indexOf('- name: Stage Test Intelligence deployment history'),
      workflow.indexOf('- name:', workflow.indexOf('- name: Stage Test Intelligence deployment history') + 1),
    )

    expect(historyStage).not.toContain('for page in 1 2 3 4 5')
    expect(historyStage).toContain('[ "${snapshot_count}" -lt 30 ]')
    expect(historyStage).toContain('actions/workflows/admin-ui-deploy.yml/runs?branch=main&status=success&per_page=100')
    expect(historyStage).toContain('actions/runs/${deploy_run_id}/artifacts?per_page=100')
    expect(historyStage).not.toContain('/actions/artifacts?')
    expect(historyStage).toContain("awk '!seen[$0]++'")
    expect(historyStage).toContain('| head -30')
  })
})
