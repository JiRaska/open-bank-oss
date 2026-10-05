// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

vi.mock('next/headers', () => ({ cookies: vi.fn() }))
vi.mock('next/link', () => ({
  default: ({ children, href }: { children: ReactNode; href: string }) => <a href={href}>{children}</a>,
}))
vi.mock('@/components/docs/MermaidEnhancer', () => ({
  MermaidEnhancer: ({ children }: { children: ReactNode }) => <>{children}</>,
}))
vi.mock('@/components/docs/MarkdownView', () => ({
  MarkdownView: ({ markdown }: { markdown: string }) => <div>{markdown}</div>,
}))
vi.mock('@/lib/services/docs', () => ({
  loadDocsIndex: vi.fn(),
  loadDocsDocument: vi.fn(),
}))

import { cookies } from 'next/headers'
import ServiceDocsPage from '@/app/services/[name]/docs/[[...slug]]/page'
import { loadDocsDocument, loadDocsIndex, type DocsIndex } from '@/lib/services/docs'

const index: DocsIndex = {
  service: 'openbank-example-service',
  source: 'live',
  requestedLang: 'en',
  availableLanguages: ['en'],
  items: [{ slug: 'index', title: 'Example service' }],
}

async function renderPage() {
  render(await ServiceDocsPage({
    params: Promise.resolve({ name: 'example', slug: ['index'] }),
    searchParams: Promise.resolve({}),
  }))
}

describe('service documentation state', () => {
  beforeEach(() => {
    vi.mocked(cookies).mockResolvedValue({ get: vi.fn() } as never)
    vi.mocked(loadDocsDocument).mockResolvedValue(null)
  })
  afterEach(() => {
    cleanup()
    vi.resetAllMocks()
  })

  it('labels a missing endpoint as unavailable, not a missing source document', async () => {
    vi.mocked(loadDocsIndex).mockResolvedValue(null)
    await renderPage()
    expect(screen.getByText('Documentation endpoint unavailable')).toBeInTheDocument()
    expect(screen.queryByText('Document not found')).not.toBeInTheDocument()
  })

  it('labels an empty response from a reachable service as missing source documents', async () => {
    vi.mocked(loadDocsIndex).mockResolvedValue({ ...index, items: [] })
    await renderPage()
    expect(screen.getByText('Source documents missing')).toBeInTheDocument()
    expect(screen.queryByText('Documentation endpoint unavailable')).not.toBeInTheDocument()
  })

  it('labels a missing slug in a populated index as a missing document', async () => {
    vi.mocked(loadDocsIndex).mockResolvedValue(index)
    await renderPage()
    expect(screen.getByText('Document not found')).toBeInTheDocument()
    expect(screen.queryByText('Documentation endpoint unavailable')).not.toBeInTheDocument()
  })
})
