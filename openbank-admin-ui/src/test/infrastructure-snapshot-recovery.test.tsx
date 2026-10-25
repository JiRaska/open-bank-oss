// SPDX-License-Identifier: Apache-2.0

import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import InfrastructurePage from '@/app/infrastructure/page'

const json = (body: unknown, status = 200) => Response.json(body, { status })

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('infrastructure snapshot recovery', () => {
  it('shows declared application and Helm provenance separately from live health probes', async () => {
    const applicationInventory = {
      gitops: {
        applications: [
          {
            kind: 'application', name: 'ledger', namespace: 'accounts', sourceFile: 'openbank-infra/gitops/apps/ledger.yaml',
            sources: [{ kind: 'helm-chart', chart: 'ledger-chart' }], workloadMapping: 'unknown',
          },
          {
            kind: 'application', name: 'policy', namespace: 'platform', sourceFile: 'openbank-infra/gitops/apps/policy.yaml',
            sources: [{ kind: 'manifest-path', path: 'openbank-infra/gitops/components/policy' }, { kind: 'unknown' }], workloadMapping: 'unknown',
          },
          {
            kind: 'unknown', name: null, namespace: null, sourceFile: 'openbank-infra/gitops/apps/legacy.yaml',
            sources: [{ kind: 'unknown' }], workloadMapping: 'unknown',
          },
        ],
      },
      iac: {
        helmReleaseDeclarations: [{
          environment: 'sandbox', sourceFile: 'openbank-infra/aws/modules/ledger/main.tf',
          address: 'module.ledger.helm_release.operator', instanceExpansion: 'unknown',
        }],
        moduleTraversalGaps: [{ environment: 'sandbox', modulePath: 'module.dynamic', reason: 'external-or-dynamic-source' }],
      },
    }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/infra/status')) {
        return json({ postgres: { id: 'postgres', status: 'UP', latencyMs: 8, checkedAt: '2026-09-09T08:00:00Z' } })
      }
      if (url.endsWith('/api/platform-versions')) return json({ inventory: applicationInventory })
      return json({ error: 'unavailable' }, 503)
    }))

    render(<LanguageProvider><InfrastructurePage /></LanguageProvider>)

    expect(await screen.findByText('Declared GitOps applications')).toBeInTheDocument()
    fireEvent.click(screen.getByText('GitOps application declarations (3)'))
    expect(await screen.findByText('ledger')).toBeInTheDocument()
    expect(screen.getByText('openbank-infra/gitops/apps/ledger.yaml')).toBeInTheDocument()
    expect(screen.getByText('Helm chart: ledger-chart')).toBeInTheDocument()
    expect(screen.getByText('Manifest path: openbank-infra/gitops/components/policy')).toBeInTheDocument()
    expect(screen.getAllByText('Unknown source')).toHaveLength(2)
    expect(screen.getAllByText('Unknown; declaration only')).toHaveLength(3)
    fireEvent.click(screen.getByText('IaC Helm release declarations (1) · module traversal gaps: 1'))
    expect(screen.getByText('module.ledger.helm_release.operator')).toBeInTheDocument()
    expect(screen.getByText('Unknown; declaration is not an instance')).toBeInTheDocument()
    expect(screen.getByText(/IaC inventory is partial: some modules could not be traversed/)).toBeInTheDocument()
    expect(screen.getByText(/sandbox: module.dynamic/)).toBeInTheDocument()
    const postgres = screen.getByText('PostgreSQL')
    expect(within(postgres.closest('.card') as HTMLElement).getByText('UP')).toBeInTheDocument()
    expect(screen.getByText('Source inventory of declarations. It does not prove deployment, runtime, or platform completeness; live probes are shown above.')).toBeInTheDocument()
  })

  it('reports legacy snapshots without typed inventories as unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/infra/status')) return json({})
      if (url.endsWith('/api/platform-versions')) return json({ inventory: { gitops: { apps: [], components: [] }, iac: {} } })
      return json({ error: 'unavailable' }, 503)
    }))

    render(<LanguageProvider><InfrastructurePage /></LanguageProvider>)

    expect(await screen.findByText('Declared application inventory is unavailable.')).toBeInTheDocument()
    expect(screen.getByText('Helm declaration inventory is unavailable.')).toBeInTheDocument()
  })

  it('keeps the last verified health evidence when refresh fails', async () => {
    let statusCalls = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/infra/status')) {
        statusCalls += 1
        return statusCalls === 1
          ? json({ postgres: { id: 'postgres', status: 'UP', latencyMs: 8, checkedAt: '2026-09-09T08:00:00Z' } })
          : json({ error: 'unavailable' }, 503)
      }
      return json({ error: 'unavailable' }, 503)
    }))

    render(<LanguageProvider><InfrastructurePage /></LanguageProvider>)

    const postgres = await screen.findByText('PostgreSQL')
    expect(within(postgres.closest('.card') as HTMLElement).getByText('UP')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Refresh infrastructure status' }))

    await waitFor(() => expect(screen.getByText(/last verified infrastructure snapshot/i)).toBeInTheDocument())
    expect(within(postgres.closest('.card') as HTMLElement).getByText('UP')).toBeInTheDocument()
  })

  it('rejects malformed refresh evidence without replacing the verified snapshot', async () => {
    let statusCalls = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (!String(input).endsWith('/api/infra/status')) return json({ error: 'unavailable' }, 503)
      statusCalls += 1
      return statusCalls === 1
        ? json({ postgres: { id: 'postgres', status: 'UP', latencyMs: 8, checkedAt: '2026-09-09T08:00:00Z' } })
        : json({ postgres: { id: 'postgres', status: 'HEALTHY', latencyMs: 1, checkedAt: '2026-09-09T08:01:00Z' } })
    }))

    render(<LanguageProvider><InfrastructurePage /></LanguageProvider>)
    const postgres = await screen.findByText('PostgreSQL')
    expect(within(postgres.closest('.card') as HTMLElement).getByText('UP')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Refresh infrastructure status' }))

    await waitFor(() => expect(screen.getByText(/last verified infrastructure snapshot/i)).toBeInTheDocument())
    expect(within(postgres.closest('.card') as HTMLElement).getByText('UP')).toBeInTheDocument()
  })
})
