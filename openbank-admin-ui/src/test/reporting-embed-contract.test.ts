// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { parse } from 'yaml'
import { describe, expect, it, vi } from 'vitest'
import { NextRequest } from 'next/server'

vi.mock('@/auth', () => ({ auth: (handler: unknown) => handler }))

describe('reporting embedding boundary', () => {
  it('allows only the configured identity provider in frames, including client navigation from another page', async () => {
    const { default: proxy } = await import('@/proxy')
    const middleware = proxy as unknown as (request: NextRequest) => Response
    for (const pathname of ['/reporting', '/accounts']) {
      const response = middleware(new NextRequest(`https://admin.example.test${pathname}`))
      const csp = response.headers.get('Content-Security-Policy') ?? ''
      expect(csp.split('; ').find((value) => value.startsWith('frame-src'))).toBe("frame-src 'self' https://kc.open-bank.tech")
      expect(csp).toContain("frame-ancestors 'self'")
      expect(csp).toContain("object-src 'none'")
      expect(response.status).toBe(307) // framing never bypasses the session gate
    }
  })
  it('makes every period-based dashboard query obey the dashboard range', () => {
    const cm = parse(readFileSync(path.resolve(process.cwd(), '../openbank-infra/gitops/components/observability/dashboard-openbank-business-warehouse.yaml'), 'utf8'))
    const dashboard = JSON.parse(cm.data['openbank-business-warehouse.json'])
    const queries = dashboard.panels.flatMap((panel: { targets?: { rawSql: string }[] }) => panel.targets ?? []).map((target: { rawSql: string }) => target.rawSql)
    expect(queries.length).toBeGreaterThan(10)
    for (const query of queries) {
      if (query.includes('maxOrNull(occurred_at)')) continue // freshness is a current watermark
      expect(query).toMatch(/\$__(date|time)Filter\(/)
      expect(query).not.toMatch(/today\(\) -|INTERVAL \d+ DAY/)
    }
  })
})
