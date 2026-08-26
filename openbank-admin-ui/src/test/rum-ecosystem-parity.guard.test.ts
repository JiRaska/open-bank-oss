import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const repo = resolve(process.cwd(), '..')
const read = (path: string) => readFileSync(resolve(repo, path), 'utf8')

describe('mobile RUM ecosystem parity', () => {
  it('does not regress to the pre-iOS attribute claim after mobile PR #555', () => {
    const sources = [
      read('docs/runbooks/0013-rum-cardinality.md'),
      read('openbank-infra/gitops/components/observability/dashboard-openbank-rum.yaml'),
      read('openbank-infra/gitops/components/observability/cronjob-rum-attribute-audit.yaml'),
    ].join('\n')

    expect(sources).not.toContain('does not currently set')
    expect(sources).not.toContain('currently never set by the app')
    expect(sources).toContain('Current Android and iOS source sends bounded')
    expect(sources).toContain('Current Android/iOS source also emits bounded')
    expect(sources).toContain('Current Android/iOS source sets them')
  })

  it('labels the Tempo result as trace count and Prometheus values as counter increments', () => {
    const page = read('openbank-admin-ui/src/app/system/tests/page.tsx')
    expect(page).toContain("client.rum.source === 'tempo' ? 'traces' : 'span-counter increments'")
    expect(page).toContain('error span-counter increments')
  })
})
