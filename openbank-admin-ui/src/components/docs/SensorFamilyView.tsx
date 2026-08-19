// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Renderer shared by every /docs/sensors/<family> subpage. Server component
// (ADR-0056) — the language comes from the cookie the page already read, so
// there is no client state and no hydration cost.
//
// One renderer rather than five hand-written pages: the five subpages differ
// only in which family they select, and a per-page layout would drift the day
// a field is added to SensorEntry.

import Link from 'next/link'
import { ChevronLeft, CheckCircle2, CircleDashed, Circle, Apple, Smartphone } from 'lucide-react'
import {
  FAMILY_META, sensorsByFamily, statusCounts,
  type SensorFamily, type SensorEntry, type Platform,
} from '@/lib/docs/sensors'
import { STATUS_META, type Status } from '@/lib/docs/status'

const STATUS_ICON: Record<Status, React.ElementType> = {
  live: CheckCircle2,
  partial: CircleDashed,
  planned: Circle,
}

const PLATFORM_LABEL: Record<Platform, { label: string; Icon: React.ElementType }> = {
  ios: { label: 'iOS', Icon: Apple },
  android: { label: 'Android', Icon: Smartphone },
}

function StatusPill({ status, lang }: { status: Status; lang: 'cs' | 'en' }) {
  const meta = STATUS_META[status]
  const Icon = STATUS_ICON[status]
  return (
    <span
      style={{
        display: 'inline-flex', alignItems: 'center', gap: 5, padding: '3px 9px',
        borderRadius: 20, fontSize: 11, fontWeight: 600,
        color: meta.color, background: meta.bg, border: `1px solid ${meta.border}`,
      }}
    >
      <Icon size={12} />
      {meta.label[lang]}
    </span>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'minmax(120px, 170px) 1fr', gap: 12, padding: '7px 0', borderTop: '1px solid var(--border)' }}>
      <div style={{ fontSize: 11, fontWeight: 600, textTransform: 'uppercase', letterSpacing: '.04em', color: 'var(--text-tertiary)' }}>
        {label}
      </div>
      <div style={{ fontSize: 13, color: 'var(--text-secondary)', lineHeight: 1.55 }}>{children}</div>
    </div>
  )
}

function SensorCard({ entry, lang }: { entry: SensorEntry; lang: 'cs' | 'en' }) {
  const t = (cs: string, en: string) => (lang === 'cs' ? cs : en)
  const bi = (b: { cs: string; en: string } | null) => (b ? b[lang] : null)

  return (
    <div className="card" style={{ padding: 18, marginBottom: 14 }} id={entry.id}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', marginBottom: 4 }}>
        <h3 style={{ fontSize: 15, fontWeight: 650, margin: 0 }}>{entry.title[lang]}</h3>
        <StatusPill status={entry.status} lang={lang} />
        {entry.platforms.map(p => {
          const { label, Icon } = PLATFORM_LABEL[p]
          return (
            <span key={p} className="badge badge-neutral" style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 11 }}>
              <Icon size={11} /> {label}
            </span>
          )
        })}
      </div>

      <p style={{ fontSize: 13, color: 'var(--text-secondary)', lineHeight: 1.6, margin: '6px 0 12px' }}>
        {entry.useCase[lang]}
      </p>

      <Field label={t('Signál', 'Signal')}>{entry.signal[lang]}</Field>
      <Field label={t('Jak se vyvolá', 'How it is invoked')}>{entry.invocation[lang]}</Field>
      <Field label={t('Zkratka', 'Shortcut')}>
        {bi(entry.shortcut) ?? <span style={{ color: 'var(--text-tertiary)' }}>{t('Žádná', 'None')}</span>}
      </Field>
      <Field label={t('Kde v aplikaci', 'Where in the app')}>{entry.where[lang]}</Field>
      <Field label={t('Nastavení', 'Setting')}>
        {bi(entry.setting) ?? <span style={{ color: 'var(--text-tertiary)' }}>{t('Nenastavuje se', 'Nothing to configure')}</span>}
      </Field>
      <Field label={t('Oprávnění', 'Permission')}>
        {bi(entry.permission) ?? <span style={{ color: 'var(--text-tertiary)' }}>—</span>}
      </Field>
      <Field label={t('K čemu je to dobré', 'Why it is worth having')}>{entry.value[lang]}</Field>
      <Field label={t('Mez / co chybí', 'Limit / what is missing')}>
        <span style={{ color: 'var(--warning-text)' }}>{entry.gap[lang]}</span>
      </Field>
      <Field label={t('Implementace', 'Implementation')}>
        <code style={{ fontSize: 11.5, wordBreak: 'break-all' }}>{entry.source}</code>
        <div style={{ fontSize: 11, color: 'var(--text-tertiary)', marginTop: 3 }}>
          {t('v repozitáři openbank-app', 'in the openbank-app repository')}
        </div>
      </Field>
    </div>
  )
}

export function SensorFamilyView({ family, lang }: { family: SensorFamily; lang: 'cs' | 'en' }) {
  const t = (cs: string, en: string) => (lang === 'cs' ? cs : en)
  const meta = FAMILY_META[family]
  const entries = sensorsByFamily(family)
  const counts = statusCounts(entries)

  return (
    <div>
      <div className="page-header">
        <div>
          <div className="breadcrumb">
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <Link href="/docs" style={{ color: 'inherit', textDecoration: 'none' }}>{t('Dokumentace', 'Docs')}</Link>
            <span className="breadcrumb-sep">/</span>
            <Link href="/docs/sensors" style={{ color: 'inherit', textDecoration: 'none' }}>{t('Senzory', 'Sensors')}</Link>
            <span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{meta.title[lang]}</span>
          </div>
          <h1 className="page-title">{meta.title[lang]}</h1>
          <p style={{ fontSize: 13, color: 'var(--text-secondary)', maxWidth: '60rem', lineHeight: 1.6, marginTop: 6 }}>
            {meta.blurb[lang]}
          </p>
        </div>
      </div>

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 16 }}>
        <Link href="/docs/sensors" style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 12, color: 'var(--text-secondary)', textDecoration: 'none' }}>
          <ChevronLeft size={13} /> {t('Všechny senzory', 'All sensors')}
        </Link>
        <span style={{ color: 'var(--border-strong)' }}>·</span>
        <span style={{ fontSize: 12, color: 'var(--text-tertiary)' }}>
          {entries.length} {t('položek', 'entries')} — {counts.live} live · {counts.partial} {t('částečně', 'partial')} · {counts.planned} {t('plánováno', 'planned')}
        </span>
      </div>

      {entries.map(e => <SensorCard key={e.id} entry={e} lang={lang} />)}
    </div>
  )
}
