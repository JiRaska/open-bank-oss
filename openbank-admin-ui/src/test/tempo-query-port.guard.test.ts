// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const root = path.resolve(process.cwd(), '..')

describe('Tempo query API port parity', () => {
  it('keeps the BFF and sandbox Admin UI on the same query port as Grafana', () => {
    const route = readFileSync(path.join(process.cwd(), 'src/app/api/tempo/[...path]/route.ts'), 'utf8')
    const admin = readFileSync(path.join(root, 'openbank-infra/gitops/components/admin-ui/admin-ui.yaml'), 'utf8')
    const grafana = readFileSync(path.join(root, 'openbank-infra/gitops/apps/kube-prometheus-stack.yaml'), 'utf8')

    expect(route).toContain("return 'http://tempo:3200'")
    expect(route).toContain("'http://localhost:3200'")
    expect(admin).toContain('value: http://tempo.observability.svc:3200')
    expect(grafana).toContain('url: http://tempo.observability.svc:3200')
    expect(route).not.toContain('tempo:3100')
    expect(admin).not.toContain('tempo.observability.svc:3100')
  })
})
