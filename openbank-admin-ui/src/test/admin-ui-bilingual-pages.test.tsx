// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// The EN switch must render the reported pages fully in English and the CS switch fully in
// Czech. Regulatory renders from its { cs, en } catalogue; the cluster dossier's generated
// artefact must carry both languages for every user-visible field it emits.
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RegulatoryPage from '@/app/regulatory/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { isBilingual, localize } from '@/lib/i18n/bilingual'

const CZECH = /[ěščřžůťďňĚŠČŘŽŮŤĎŇ]/

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('regulatory catalogue follows the language switch', () => {
  it('EN: report list carries no Czech', () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })))
    render(<LanguageProvider initialLanguage="en"><RegulatoryPage /></LanguageProvider>)
    expect(screen.getByText('SDAT — Payment statistics')).toBeInTheDocument()
    expect(screen.getByText('CNB — Liquidity coverage (LCR)')).toBeInTheDocument()
    expect(screen.queryByText('Implementovaný náhled')).not.toBeInTheDocument()
    const cards = Array.from(document.querySelectorAll('.card')).map(c => c.textContent ?? '')
    const czech = cards.filter(text => CZECH.test(text.replace(/FAÚ|ČNB/g, '')))
    expect(czech).toEqual([])
  })

  it('CS: report list is Czech', () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })))
    render(<LanguageProvider initialLanguage="cs"><RegulatoryPage /></LanguageProvider>)
    expect(screen.getByText('SDAT — Platební statistika')).toBeInTheDocument()
    expect(screen.getAllByText(/CNB · Měsíčně/).length).toBeGreaterThan(0)
    expect(screen.queryByText('SDAT — Payment statistics')).not.toBeInTheDocument()
  })
})

describe('cluster-topology.json is bilingual', () => {
  const topo = JSON.parse(readFileSync(path.resolve(import.meta.dirname, '..', '..', 'cluster-topology.json'), 'utf8'))

  it('every security layer, anatomy step and plan row carries { cs, en }', () => {
    for (const l of topo.securityLayers) for (const k of ['label', 'analogy', 'summary']) expect(isBilingual(l[k]), `${l.id}.${k}`).toBe(true)
    for (const s of topo.imageAnatomy.steps) for (const k of ['label', 'detail']) expect(isBilingual(s[k]), `${s.id}.${k}`).toBe(true)
    for (const r of topo.planVsReality) for (const k of ['item', 'plan', 'reality']) expect(isBilingual(r[k])).toBe(true)
    for (const g of topo.groups) expect(isBilingual(g.blurb), g.id).toBe(true)
    for (const n of topo.namespaces) expect(isBilingual(n.role), n.name).toBe(true)
  })

  it('the English projection has no Czech text', () => {
    const en = localize(topo, 'en')
    const pieces = [
      ...en.securityLayers.flatMap((l: Record<string, string>) => [l.label, l.analogy, l.summary]),
      ...en.imageAnatomy.steps.flatMap((s: Record<string, string>) => [s.label, s.detail]),
      ...en.planVsReality.flatMap((r: Record<string, string>) => [r.item, r.plan, r.reality]),
      ...en.groups.map((g: Record<string, string>) => g.blurb),
      ...en.namespaces.map((n: Record<string, string>) => n.role),
    ]
    expect(pieces.filter(p => CZECH.test(p))).toEqual([])
    expect(localize(topo, 'cs').securityLayers[0].analogy).toBe('Ostraha a turniket u vchodu do banky.')
  })
})
