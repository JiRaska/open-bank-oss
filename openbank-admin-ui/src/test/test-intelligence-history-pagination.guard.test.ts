import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const workflow = readFileSync(resolve(process.cwd(), '../.github/workflows/admin-ui-deploy.yml'), 'utf8')

describe('Test Intelligence history pagination', () => {
  it('collects toward the 30-snapshot requirement instead of stopping after five noisy repo pages', () => {
    const historyStage = workflow.slice(
      workflow.indexOf('- name: Stage Test Intelligence deployment history'),
      workflow.indexOf('- name:', workflow.indexOf('- name: Stage Test Intelligence deployment history') + 1),
    )

    expect(historyStage).not.toContain('for page in 1 2 3 4 5')
    expect(historyStage).toContain('[ "${snapshot_count}" -lt 30 ]')
    expect(historyStage).toContain('[ "${page}" -le 100 ]')
    expect(workflow).toContain("awk '!seen[$0]++'")
    expect(workflow).toContain('| head -30')
  })
})
