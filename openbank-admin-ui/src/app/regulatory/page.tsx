// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import Link from 'next/link'
import { useState } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure, svcUrl, type BffFailure } from '@/lib/services/bff'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { blockReasonCopy, evaluateExportReadiness, type BalanceVerdict } from '@/lib/regulatory/exportReadiness'
import { Ban } from 'lucide-react'
import { FileText, CheckCircle2, AlertTriangle, ExternalLink, Calendar, Check, Eye, X, Table as TableIcon, FileJson, FileSpreadsheet, RefreshCw } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'

type Report = (typeof REPORTS)[number]

// A single, shared shape for what an export contains — used both to render the
// visual preview table and to serialise to JSON/CSV, so the operator sees
// exactly what will be downloaded (no drift between preview and file).
interface ExportRow {
  field: string
  value: string
}

interface RegulatoryCell {
  rowRef: string
  colRef: string
  value: number
  currency: string
  label?: string
  isDataGap?: boolean
  gapReason?: string | null
}

interface RegulatoryTemplate {
  templateId: string
  period: string
  cells: RegulatoryCell[]
  isBalanced?: boolean
  /** Which of the two independent balance verdicts objected (finrep, issue #6011). */
  balanceVerdict?: BalanceVerdict
  hasDataGaps?: boolean
}

type PreviewData =
  | { status: 'idle' | 'loading' }
  | { status: 'unavailable'; kind: BffFailure }
  | { status: 'no-periods' }
  | { status: 'unsupported' }
  | { status: 'ready'; templates: RegulatoryTemplate[]; evidence: 'FROZEN' | 'LIVE_PREVIEW' }

type TemplateLoadResult = { template: RegulatoryTemplate } | { kind: BffFailure }

const TEMPLATE_PATHS: Record<string, string[]> = {
  'cnb-finrep': [
    '/api/v1/finrep/templates/F01.01',
    '/api/v1/finrep/templates/F01.02',
    '/api/v1/finrep/templates/F01.03',
    '/api/v1/finrep/templates/F02.00',
  ],
  'cnb-capital': ['/api/v1/corep/templates/C_01.00'],
}

// This is the canonical environment tag already embedded in the browser bundle. Unknown
// environments fail safe as non-production: missing deployment metadata must never remove a
// TEST_ONLY mark from a regulatory artefact.
const DEPLOYMENT_ENVIRONMENT = process.env.NEXT_PUBLIC_GLITCHTIP_ENVIRONMENT?.trim() || 'unknown'
const IS_TEST_ENVIRONMENT = DEPLOYMENT_ENVIRONMENT !== 'production'

const CELL_LABELS: Record<string, string> = {
  'F01.01:r0010:c0010': 'Hotovost, centrální banky a vklady na požádání',
  'F01.01:r0040:c0010': 'Ostatní vklady na požádání',
  'F01.01:r0181:c0010': 'Finanční aktiva v naběhlé hodnotě',
  'F01.01:r0183:c0010': 'Úvěry a pohledávky',
  'F01.01:r0360:c0010': 'Ostatní aktiva',
  'F01.01:r0380:c0010': 'Celková aktiva',
  'F01.02:r0110:c0010': 'Finanční závazky v naběhlé hodnotě',
  'F01.02:r0120:c0010': 'Vklady klientů',
  'F01.02:r0240:c0010': 'Daňové závazky',
  'F01.02:r0250:c0010': 'Splatná daň',
  'F01.02:r0300:c0010': 'Celkové závazky',
  'F01.03:r0010:c0010': 'Kapitál',
  'F01.03:r0020:c0010': 'Splacený kapitál',
  'F01.03:r0040:c0010': 'Emisní ážio',
  'F01.03:r0070:c0010': 'Ostatní vydané kapitálové nástroje',
  'F01.03:r0190:c0010': 'Nerozdělený zisk',
  'F01.03:r0210:c0010': 'Ostatní rezervy',
  'F01.03:r0250:c0010': 'Zisk nebo ztráta vlastníků mateřské společnosti',
  'F01.03:r0300:c0010': 'Celkový vlastní kapitál',
  'F01.03:r0310:c0010': 'Celkový vlastní kapitál a závazky',
  'F02.00:r0010:c0010': 'Úrokové výnosy',
  'F02.00:r0051:c0010': 'Úrokové výnosy z aktiv v naběhlé hodnotě',
  'F02.00:r0090:c0010': 'Úrokové náklady',
  'F02.00:r0120:c0010': 'Úrokové náklady ze závazků v naběhlé hodnotě',
  'F02.00:r0200:c0010': 'Výnosy z poplatků a provizí',
  'F02.00:r0310:c0010': 'Kurzové rozdíly',
  'F02.00:r0355:c0010': 'Čistý provozní výnos',
  'F02.00:r0460:c0010': 'Znehodnocení finančních aktiv',
  'F02.00:r0491:c0010': 'Znehodnocení aktiv v naběhlé hodnotě',
  'F02.00:r0610:c0010': 'Zisk před zdaněním z pokračujících činností',
  'F02.00:r0630:c0010': 'Zisk po zdanění z pokračujících činností',
  'F02.00:r0670:c0010': 'Zisk / ztráta za období',
  'F02.00:r0690:c0010': 'Zisk / ztráta vlastníků mateřské společnosti',
}

function cellLabel(template: RegulatoryTemplate, cell: RegulatoryCell): string {
  return cell.label ?? CELL_LABELS[`${template.templateId}:${cell.rowRef}:${cell.colRef}`] ?? `${cell.rowRef} / ${cell.colRef}`
}

function money(value: number, currency: string): string {
  return new Intl.NumberFormat('cs-CZ', { style: 'currency', currency, maximumFractionDigits: 2 }).format(value)
}

function lastCompletedMonthEnd(): string {
  const now = new Date()
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 0)).toISOString().slice(0, 10)
}

function buildExportRows(report: Report, data: PreviewData): ExportRow[] {
  const meta: ExportRow[] = [
    { field: 'Klasifikace artefaktu', value: IS_TEST_ENVIRONMENT ? 'TEST_ONLY' : 'INTERNÍ REGULATORNÍ NÁHLED' },
    { field: 'Prostředí', value: DEPLOYMENT_ENVIRONMENT },
    {
      field: 'Povolené použití',
      value: IS_TEST_ENVIRONMENT
        ? 'TESTOVACÍ DATA — NESMÍ BÝT ODESLÁNO REGULÁTOROVI'
        : 'Interní kontrola; odeslání regulátorovi není připojeno',
    },
    { field: 'ID výkazu', value: report.id },
    { field: 'Název', value: report.name },
    { field: 'Autorita', value: report.authority },
    { field: 'Regulace', value: report.regulation },
    { field: 'Kód SDAT', value: report.sdatCode },
    { field: 'Frekvence', value: report.frequency },
    { field: 'Termín', value: report.deadline },
    { field: 'Datový zdroj', value: TEMPLATE_PATHS[report.id] ? 'Implementovaný náhled — data se ověřují při načtení' : 'Katalog — datový zdroj není připojen' },
    { field: 'Odeslání regulátorovi', value: 'Není připojeno' },
    { field: 'Příští termín', value: report.nextDue },
  ]
  if (data.status === 'ready') {
    const rendered = data.templates.flatMap(template => [
      { field: `Šablona ${template.templateId}`, value: `Období ${template.period}${template.isBalanced === false ? ' · nevyvážená' : ''}${template.hasDataGaps ? ' · obsahuje datové mezery' : ''}` },
      ...template.cells.map((cell) => ({
        field: `${template.templateId} · ${cellLabel(template, cell)}`,
        value: `${money(cell.value, cell.currency)}${cell.isDataGap ? ' · DATOVÁ MEZERA' : ''}`,
      })),
    ])
    const source = data.evidence === 'FROZEN'
      ? 'finrep-service ← zmrazená ledger předvaha (FROZEN / LINES_V1)'
      : 'finrep-service ← živá ledger předvaha (PRACOVNÍ NÁHLED, měnitelná)'
    return [...meta, { field: 'Zdroj', value: source }, ...rendered]
  }
  const message = data.status === 'loading' || data.status === 'idle'
    ? 'Načítám…'
    : data.status === 'unsupported'
      ? 'Zatím není napojeno na datový zdroj'
      : 'Zdroj dat není dostupný'
  return [...meta, ...report.fields.map((field) => ({ field, value: message }))]
}

// RFC-4180-ish CSV escaping: wrap in quotes and double any embedded quote.
function csvCell(s: string): string {
  return `"${String(s).replace(/"/g, '""')}"`
}

function triggerDownload(filename: string, content: string, mime: string) {
  const blob = new Blob([content], { type: mime })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

const REPORTS = [
  {
    id: 'sdat-payments',
    name: 'SDAT — Platební statistika',
    authority: 'CNB',
    regulation: 'Vyhláška č. 314/2013 Sb.',
    frequency: 'Měsíčně',
    deadline: '15. den následujícího měsíce',
    nextDue: '2026-06-15',
    description: 'Statistika platebního styku — počty a objemy plateb dle kanálu, typu a měny',
    sdatCode: 'PLAT',
    fields: ['Počet transakcí', 'Objem v CZK', 'Počet SEPA plateb', 'Počet domácích plateb', 'Počet přeshraničních plateb'],
  },
  {
    id: 'sdat-accounts',
    name: 'SDAT — Statistika vkladů',
    authority: 'CNB',
    regulation: 'Vyhláška č. 314/2013 Sb.',
    frequency: 'Měsíčně',
    deadline: '15. den následujícího měsíce',
    nextDue: '2026-06-15',
    description: 'Statistika vkladů a úvěrů — zůstatky, úrokové sazby, počty účtů',
    sdatCode: 'VKLA',
    fields: ['Celkové vklady CZK', 'Celkové vklady EUR', 'Průměrná úroková sazba', 'Počet aktivních účtů'],
  },
  {
    id: 'aml-far',
    name: 'FAÚ — Hlášení podezřelých obchodů (SAR)',
    authority: 'FAÚ (Finanční analytický úřad)',
    regulation: 'Zákon č. 253/2008 Sb. (AML zákon)',
    frequency: 'Ad-hoc (do 24h od zjištění)',
    deadline: 'Do 24 hodin',
    nextDue: 'Ad-hoc',
    description: 'Hlášení podezřelých obchodů a transakcí dle AML zákona',
    sdatCode: 'SAR',
    fields: ['ID zákazníka', 'Popis podezřelé aktivity', 'Výše transakce', 'Datum zjištění', 'Kontaktní osoba MLRO'],
  },
  {
    id: 'cnb-capital',
    name: 'CNB — Kapitálová přiměřenost (COREP)',
    authority: 'CNB',
    regulation: 'CRR/CRD IV (EU 575/2013)',
    frequency: 'Čtvrtletně',
    deadline: '30 dní po konci čtvrtletí',
    nextDue: '2026-07-30',
    description: 'Common Reporting — kapitálová přiměřenost, rizikové expozice, pákový poměr',
    sdatCode: 'COREP',
    fields: ['Tier 1 kapitál', 'Tier 2 kapitál', 'RWA', 'CAR ratio', 'Leverage ratio'],
  },
  {
    id: 'cnb-finrep',
    name: 'CNB — Finanční výkazy (FINREP)',
    authority: 'CNB',
    regulation: 'IAS/IFRS + EBA ITS',
    frequency: 'Čtvrtletně',
    deadline: '30 dní po konci čtvrtletí',
    nextDue: '2026-07-30',
    description: 'Financial Reporting — rozvaha, výkaz zisku a ztráty, podrozvahové položky',
    sdatCode: 'FINREP',
    fields: ['Aktiva celkem', 'Závazky celkem', 'Vlastní kapitál', 'Čistý úrokový výnos', 'Provozní náklady'],
  },
  {
    id: 'ecb-payments',
    name: 'ECB — Statistika platebního styku',
    authority: 'ECB / ČNB',
    regulation: 'ECB/2013/43 Regulation',
    frequency: 'Ročně',
    deadline: '31. března',
    nextDue: '2027-03-31',
    description: 'Statistika platebního styku pro ECB — počty a hodnoty platebních transakcí',
    sdatCode: 'ECB-PAYM',
    fields: ['Počet platebních účtů', 'Počet karet', 'Objem bezhotovostních plateb', 'Počet ATM'],
  },
  {
    id: 'fatca-report',
    name: 'FATCA — Hlášení US osob',
    authority: 'Finanční správa ČR / IRS',
    regulation: 'FATCA (US zákon) + CRS OECD',
    frequency: 'Ročně',
    deadline: '30. června',
    nextDue: '2026-06-30',
    description: 'Hlášení účtů US osob a entit finančnímu úřadu pro přeposlání IRS',
    sdatCode: 'FATCA',
    fields: ['Jméno US osoby', 'TIN (US daňové číslo)', 'Zůstatek účtu', 'Příjmy z US zdrojů'],
  },
  {
    id: 'dora-ict',
    name: 'DORA — ICT Incident Reporting',
    authority: 'CNB / EBA',
    regulation: 'DORA (EU 2022/2554) — od 17.1.2025',
    frequency: 'Ad-hoc (do 4h od zjištění)',
    deadline: 'Počáteční hlášení do 4 hodin',
    nextDue: 'Ad-hoc',
    description: 'Hlášení závažných ICT incidentů dle DORA — počáteční, průběžné a závěrečné hlášení',
    sdatCode: 'DORA-INC',
    fields: ['Klasifikace incidentu', 'Dopad na zákazníky', 'RTO/RPO', 'Příčina', 'Nápravná opatření'],
  },
]

const DATA_SOURCE_CONFIG = {
  implemented: { label: 'Implementovaný náhled', color: '#2563eb', icon: <FileText size={13} /> },
  catalog: { label: 'Katalog — bez zdroje', color: '#6b7280', icon: <AlertTriangle size={13} /> },
}

function dataSourceOf(report: Report): keyof typeof DATA_SOURCE_CONFIG {
  return TEMPLATE_PATHS[report.id] ? 'implemented' : 'catalog'
}

export default function RegulatoryPage() {
  const [selected, setSelected] = useState<string | null>(null)
  const { t } = useLanguage()
  const [downloadMessage, setDownloadMessage] = useState<string | null>(null)
  // The export now opens a visual preview first (operator visually checks the
  // table before committing to a download) — `preview` holds the report whose
  // export is being inspected.
  const [preview, setPreview] = useState<Report | null>(null)
  const [previewData, setPreviewData] = useState<PreviewData>({ status: 'idle' })
  const [reportingDate, setReportingDate] = useState('')
  const [reportingPeriods, setReportingPeriods] = useState<string[]>([])
  const [reportingEvidence, setReportingEvidence] = useState<'FROZEN' | 'LIVE_PREVIEW'>('FROZEN')

  async function loadPreview(
    report: Report,
    requestedAsOf?: string,
    requestedEvidence: 'FROZEN' | 'LIVE_PREVIEW' = 'FROZEN',
  ) {
    const paths = TEMPLATE_PATHS[report.id]
    if (!paths) {
      setPreviewData({ status: 'unsupported' })
      return
    }
    setPreviewData({ status: 'loading' })
    try {
      let asOf = requestedAsOf
      let evidence = requestedEvidence
      if (!asOf) {
        const periodsResponse = await fetch(svcUrl('finrep-service', '/api/v1/finrep/periods'), {
          cache: 'no-store', signal: AbortSignal.timeout(15_000),
        })
        if (!periodsResponse.ok) {
          setPreviewData({ status: 'unavailable', kind: await classifyBffFailure(periodsResponse) })
          return
        }
        const available = await periodsResponse.json() as { latest: string | null; periods: string[] }
        setReportingPeriods(available.periods)
        if (!available.latest) {
          asOf = lastCompletedMonthEnd()
          evidence = 'LIVE_PREVIEW'
          setReportingPeriods([asOf])
          setReportingDate(asOf)
          setReportingEvidence(evidence)
        } else {
          asOf = available.latest
          setReportingDate(asOf)
          setReportingEvidence(evidence)
        }
      }
      const results: TemplateLoadResult[] = await Promise.all(paths.map(async (path): Promise<TemplateLoadResult> => {
        const response = await fetch(svcUrl('finrep-service', path, { asOf, evidence }), {
          cache: 'no-store', signal: AbortSignal.timeout(15_000),
        })
        return response.ok
          ? { template: await response.json() as RegulatoryTemplate }
          : { kind: await classifyBffFailure(response) }
      }))
      const failed = results.find((result): result is { kind: BffFailure } => 'kind' in result)
      if (failed) {
        setPreviewData({ status: 'unavailable', kind: failed.kind })
        return
      }
      setPreviewData({
        status: 'ready',
        evidence,
        templates: results.filter((result): result is { template: RegulatoryTemplate } => 'template' in result).map(result => result.template),
      })
    } catch {
      setPreviewData({ status: 'unavailable', kind: 'unreachable' })
    }
  }

  function openPreview(id: string, e: React.MouseEvent) {
    e.stopPropagation()
    const report = REPORTS.find(r => r.id === id)
    if (report) {
      setPreview(report)
      void loadPreview(report)
    }
  }

  function exportJson(report: Report) {
    // Guarded at the handler, not only by the disabled button: a disabled attribute is a UI
    // affordance, not a control. Both paths must refuse the same inputs (issue #5904).
    if (!evaluateExportReadiness(previewData).ok) return
    const rows = buildExportRows(report, previewData)
    const data = {
      ...Object.fromEntries(rows.map(r => [r.field, r.value])),
      keyFields: report.fields,
      exportedAt: new Date().toISOString(),
    }
    triggerDownload(
      `${IS_TEST_ENVIRONMENT ? 'TEST_ONLY_' : ''}report_${report.sdatCode}_${new Date().toISOString().slice(0, 10)}.json`,
      JSON.stringify(data, null, 2),
      'application/json',
    )
    flashDownloaded(report.id)
  }

  function exportCsv(report: Report) {
    if (!evaluateExportReadiness(previewData).ok) return
    const rows = buildExportRows(report, previewData)
    const header = `${csvCell(t('Pole', 'Field'))},${csvCell(t('Hodnota', 'Value'))}`
    const body = rows.map(r => `${csvCell(r.field)},${csvCell(r.value)}`).join('\n')
    triggerDownload(
      `${IS_TEST_ENVIRONMENT ? 'TEST_ONLY_' : ''}report_${report.sdatCode}_${new Date().toISOString().slice(0, 10)}.csv`,
      `${header}\n${body}\n`,
      'text/csv;charset=utf-8',
    )
    flashDownloaded(report.id)
  }

  function flashDownloaded(id: string) {
    setDownloadMessage(id)
    setTimeout(() => setDownloadMessage(null), 3000)
  }

  // Whether an export may be produced at all. Derived from the SAME `previewData` the operator is
  // looking at, so the buttons can never disagree with the table above them (issue #5904).
  const exportReadiness = evaluateExportReadiness(previewData)

  const implementedPreviewCount = REPORTS.filter(r => dataSourceOf(r) === 'implemented').length
  const catalogueOnlyCount = REPORTS.length - implementedPreviewCount

  const authorities = REPORTS.reduce((acc, r) => {
    const key = r.authority.includes('CNB') ? 'ČNB' : r.authority.includes('FAÚ') ? 'FAÚ' : r.authority.includes('ECB') ? 'ECB' : 'Ostatní'
    acc[key] = (acc[key] || 0) + 1
    return acc
  }, {} as Record<string, number>)

  const today = new Date().toISOString().slice(0, 10)
  const upcoming = [...REPORTS]
    .filter(r => r.nextDue !== 'Ad-hoc' && r.nextDue >= today)
    .sort((a, b) => a.nextDue.localeCompare(b.nextDue))
    .slice(0, 3)

  return (
    <div>
      <PageHeader
        icon={<FileText size={18} aria-hidden="true" />}
        title={t('Regulatorní výkaznictví', 'Regulatory Reporting')}
        subtitle={t('CNB SDAT · FAÚ · COREP/FINREP · ECB · FATCA/CRS · DORA ICT incidenty', 'CNB SDAT · FAÚ · COREP/FINREP · ECB · FATCA/CRS · DORA ICT incidents')}
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Regulatorní výkaznictví', 'Regulatory Reporting')}</span></div>}
        actions={<a href="https://www.cnb.cz/cs/dohled-financni-trh/vykaznictvi/" target="_blank" rel="noreferrer" className="btn btn-secondary">
          <ExternalLink size={13} aria-hidden="true" />
          {t('CNB Výkaznictví', 'CNB Reporting')}
        </a>}
      />

      {/* Summary */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))', gap: '12px', marginBottom: '20px' }}>
        {[
          { label: t('Katalog výkazů', 'Report catalogue'), value: REPORTS.length, color: 'var(--accent)' },
          { label: t('Implementovaný náhled', 'Implemented preview'), value: implementedPreviewCount, color: '#2563eb' },
          { label: t('Bez datového zdroje', 'No data source'), value: catalogueOnlyCount, color: '#6b7280' },
          { label: t('Napojené odesílání', 'Connected submission'), value: 0, color: '#d97706' },
        ].map(s => (
          <div key={s.label} className="card" style={{ padding: '14px 16px' }}>
            <div style={{ fontSize: '22px', fontWeight: 700, color: s.color }}>{s.value}</div>
            <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '2px' }}>{s.label}</div>
          </div>
        ))}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: '16px', marginBottom: '24px' }}>
        <div className="card" style={{ padding: '16px' }}>
          <h3 style={{ fontSize: '13px', fontWeight: 700, marginBottom: '12px', color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '6px' }}><FileText size={14} /> {t('Pokrytí implementovanými náhledy', 'Implemented preview coverage')}</h3>
          <div style={{ display: 'flex', height: '20px', borderRadius: '6px', overflow: 'hidden', marginBottom: '16px' }}>
            {(['implemented', 'catalog'] as const).map(st => {
              const count = REPORTS.filter(r => dataSourceOf(r) === st).length
              if (count === 0) return null;
              const percent = (count / REPORTS.length) * 100;
              const color = DATA_SOURCE_CONFIG[st].color;
              return <div key={st} style={{ width: `${percent}%`, background: color }} title={`${DATA_SOURCE_CONFIG[st].label}: ${count}`} />
            })}
          </div>
          <div style={{ display: 'flex', gap: '16px', fontSize: '12px' }}>
            {(['implemented', 'catalog'] as const).map(st => {
              const count = REPORTS.filter(r => dataSourceOf(r) === st).length;
              const cfg = DATA_SOURCE_CONFIG[st];
              return (
                <div key={st} style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                  <div style={{ width: '10px', height: '10px', borderRadius: '2px', background: cfg.color }} />
                  <span style={{ color: 'var(--text-secondary)' }}>{cfg.label}</span>
                  <span style={{ fontWeight: 600 }}>{count}</span>
                </div>
              )
            })}
          </div>
        </div>

        <div className="card" style={{ padding: '16px' }}>
          <h3 style={{ fontSize: '13px', fontWeight: 700, marginBottom: '16px', color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '6px' }}><FileText size={14} /> {t('Dle autority', 'By authority')}</h3>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
            {Object.entries(authorities).sort((a, b) => b[1] - a[1]).map(([auth, count]) => {
              const percent = (count / REPORTS.length) * 100;
              return (
                <div key={auth} style={{ display: 'flex', alignItems: 'center', gap: '10px', fontSize: '12px' }}>
                  <div style={{ width: '45px', fontWeight: 600, color: 'var(--text-primary)' }}>{auth}</div>
                  <div style={{ flex: 1, height: '6px', background: 'var(--border)', borderRadius: '3px', overflow: 'hidden' }}>
                    <div style={{ width: `${percent}%`, height: '100%', background: 'var(--accent)', borderRadius: '3px' }} />
                  </div>
                  <div style={{ width: '20px', textAlign: 'right', color: 'var(--text-secondary)', fontWeight: 500 }}>{count}</div>
                </div>
              )
            })}
          </div>
        </div>

        <div className="card" style={{ padding: '16px' }}>
          <h3 style={{ fontSize: '13px', fontWeight: 700, marginBottom: '16px', color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '6px' }}><Calendar size={14} /> {t('Budoucí katalogové termíny', 'Future catalogue deadlines')}</h3>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
            {upcoming.length === 0 && <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>{t('V katalogu není evidován žádný budoucí termín.', 'No future deadline is recorded in the catalogue.')}</div>}
            {upcoming.map((report) => (
              <div key={report.id} style={{ display: 'flex', alignItems: 'flex-start', gap: '10px' }}>
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', minHeight: '28px', marginTop: '3px' }}>
                  <div style={{ width: '8px', height: '8px', borderRadius: '50%', border: '2px solid var(--accent)', background: 'var(--surface-1)' }} />
                  <div style={{ width: '2px', flex: 1, background: 'var(--border)', marginTop: '4px', minHeight: '12px' }} />
                </div>
                <div style={{ flex: 1 }}>
                  <div style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-primary)', marginBottom: '2px' }}>{report.nextDue}</div>
                  <div style={{ fontSize: '11px', color: 'var(--text-secondary)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: '200px' }} title={report.name}>{report.name}</div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Reports list */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
        {REPORTS.map(report => {
          const source = dataSourceOf(report)
          const cfg = DATA_SOURCE_CONFIG[source]
          const isSelected = selected === report.id
          return (
            <div key={report.id} className="card" style={{ overflow: 'hidden', borderLeft: `3px solid ${cfg.color}` }}>
              <div role="button" tabIndex={0} aria-expanded={isSelected}
                aria-label={`${report.name} — ${isSelected ? t('Sbalit detail', 'Collapse details') : t('Rozbalit detail', 'Expand details')}`}
                style={{ padding: '14px 16px', display: 'flex', alignItems: 'center', gap: '12px', cursor: 'pointer', flexWrap: 'wrap' }}
                onClick={() => setSelected(s => s === report.id ? null : report.id)}
                onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setSelected(s => s === report.id ? null : report.id) } }}>
                {/* Status */}
                <span style={{ color: cfg.color, flexShrink: 0 }}>{cfg.icon}</span>

                {/* Name + authority */}
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
                    <span style={{ fontSize: '13px', fontWeight: 700 }}>{report.name}</span>
                    <span style={{ fontSize: '10px', padding: '2px 6px', background: `${cfg.color}15`, color: cfg.color, borderRadius: '4px', border: `1px solid ${cfg.color}30`, fontWeight: 600 }}>
                      {cfg.label}
                    </span>
                    <span style={{ fontSize: '10px', color: 'var(--text-tertiary)', fontFamily: 'JetBrains Mono, monospace' }}>{report.sdatCode}</span>
                  </div>
                  <div style={{ fontSize: '12px', color: 'var(--text-secondary)', marginTop: '2px' }}>{report.authority} · {report.frequency}</div>
                </div>

                {/* Next due */}
                <div style={{ textAlign: 'right', flexShrink: 0 }}>
                  <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{report.nextDue !== 'Ad-hoc' && report.nextDue < today ? t('Katalogový termín (historický)', 'Catalogue deadline (historical)') : t('Katalogový termín', 'Catalogue deadline')}</div>
                  <div style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-primary)', display: 'flex', alignItems: 'center', gap: '4px' }}>
                    <Calendar size={11} />
                    {report.nextDue}
                  </div>
                </div>

                {/* Actions */}
                <div style={{ display: 'flex', gap: '6px', flexShrink: 0 }} onClick={e => e.stopPropagation()}>
                  {source === 'implemented' ? (
                    <button className="btn btn-secondary" style={{ fontSize: '11px', padding: '5px 10px' }}
                      onClick={(e) => openPreview(report.id, e)}>
                      {downloadMessage === report.id ? <><Check size={11} style={{ color: '#16a34a' }} /> {t('Staženo', 'Downloaded')}</> : <><Eye size={11} /> {t('Náhled exportu', 'Preview export')}</>}
                    </button>
                  ) : (
                    <span role="status" style={{ fontSize: '11px', padding: '5px 10px', color: 'var(--text-tertiary)' }}>
                      {t('Náhled není dostupný', 'Preview unavailable')}
                    </span>
                  )}
                </div>
              </div>

              {/* Detail */}
              {isSelected && (
                <div style={{ padding: '16px', borderTop: '1px solid var(--border)', background: 'var(--surface-2)' }}>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px', marginBottom: '12px' }}>
                    <div>
                      <div style={{ fontSize: '11px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-tertiary)', marginBottom: '6px' }}>{t('Popis', 'Description')}</div>
                      <div style={{ fontSize: '13px', color: 'var(--text-primary)' }}>{report.description}</div>
                      <div style={{ marginTop: '8px', fontSize: '12px', color: 'var(--text-secondary)' }}>
                        <strong>{t('Regulace:', 'Regulation:')}</strong> {report.regulation}
                      </div>
                      <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
                        <strong>{t('Termín:', 'Deadline:')}</strong> {report.deadline}
                      </div>
                      <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
                        <strong>{t('Datový zdroj:', 'Data source:')}</strong> {source === 'implemented'
                          ? t('implementovaný náhled přes finrep-service; dostupnost se ověřuje při načtení', 'implemented preview through finrep-service; availability is checked on load')
                          : t('zatím nenapojeno', 'not connected yet')}
                      </div>
                    </div>
                    <div>
                      <div style={{ fontSize: '11px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-tertiary)', marginBottom: '6px' }}>{t('Klíčová pole výkazu', 'Key report fields')}</div>
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
                        {report.fields.map((f, i) => (
                          <div key={i} style={{ fontSize: '12px', color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '6px' }}>
                            <CheckCircle2 size={11} style={{ color: '#16a34a', flexShrink: 0 }} />
                            {f}
                          </div>
                        ))}
                      </div>
                    </div>
                  </div>

                  {/* Reporting transport is deliberately not represented as implemented. */}
                  <div style={{ padding: '10px 12px', background: '#eff6ff', border: '1px solid #bfdbfe', borderRadius: '6px', fontSize: '12px', color: '#1e40af' }}>
                    <strong>{t('Stav odeslání:', 'Submission status:')}</strong> {t('Přenos k regulátorovi zatím není v Admin UI ani v backendu napojen. Kód výkazu je jen katalogová informace, ne důkaz podání.', 'Regulator transmission is not connected in the Admin UI or backend yet. The report code is catalogue metadata, not proof of submission.')}
                    {' '}{t('Kód výkazu:', 'Report code:')} <code style={{ fontFamily: 'JetBrains Mono, monospace', background: '#dbeafe', padding: '1px 4px', borderRadius: '3px' }}>{report.sdatCode}</code>.
                  </div>
                </div>
              )}
            </div>
          )
        })}
      </div>

      {/* Export preview — visual control before download. Shows the exact
          field/value table that will be serialised, plus JSON and CSV export. */}
      {preview && (
        <div
          onClick={() => setPreview(null)}
          style={{
            position: 'fixed', inset: 0, zIndex: 1000,
            background: 'rgba(0,0,0,0.45)', display: 'flex',
            alignItems: 'center', justifyContent: 'center', padding: '24px',
          }}
        >
          <div
            onClick={e => e.stopPropagation()}
            className="card"
            style={{
              width: '100%', maxWidth: '680px', maxHeight: '86vh',
              display: 'flex', flexDirection: 'column', overflow: 'hidden', padding: 0,
            }}
          >
            {/* Modal header */}
            <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '12px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '10px', minWidth: 0 }}>
                <TableIcon size={16} style={{ color: 'var(--accent)', flexShrink: 0 }} />
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {t('Náhled exportu', 'Export preview')} · {preview.sdatCode}
                  </div>
                  <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{preview.name}</div>
                </div>
              </div>
              <button type="button" className="btn btn-secondary" style={{ padding: '5px', flexShrink: 0 }} onClick={() => setPreview(null)} aria-label={t('Zavřít náhled exportu', 'Close export preview')}>
                <X size={15} aria-hidden="true" />
              </button>
            </div>

            {TEMPLATE_PATHS[preview.id] && (
              <div style={{ padding: '10px 20px', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '10px', flexWrap: 'wrap', background: 'var(--surface-2)' }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '12px', color: 'var(--text-secondary)' }}>
                  {t('Referenční datum', 'Reference date')}
                  <select
                    value={reportingDate}
                    onChange={e => setReportingDate(e.target.value)}
                    style={{ font: 'inherit', color: 'var(--text-primary)', border: '1px solid var(--border)', borderRadius: '4px', padding: '4px 6px', background: 'var(--surface-1)' }}
                  >
                    {reportingPeriods.map(period => <option key={period} value={period}>{period}</option>)}
                  </select>
                </label>
                <button type="button" className="btn btn-secondary" style={{ fontSize: '12px' }} onClick={() => void loadPreview(preview, reportingDate || undefined, reportingEvidence)} disabled={previewData.status === 'loading' || !reportingDate} aria-busy={previewData.status === 'loading'} aria-label={t('Načíst data pro náhled', 'Load preview data')}>
                  <RefreshCw size={13} aria-hidden="true" className={previewData.status === 'loading' ? 'animate-spin' : ''} />
                  {t('Načíst data', 'Load data')}
                </button>
              </div>
            )}

            {IS_TEST_ENVIRONMENT && (
              <div role="status" data-testid="test-data-watermark" style={{ padding: '10px 20px', color: '#991b1b', background: '#fef2f2', borderBottom: '1px solid #fecaca', fontSize: '12px', fontWeight: 700 }}>
                {DEPLOYMENT_ENVIRONMENT.toUpperCase()} / {t('TESTOVACÍ DATA — náhled ani stažený soubor nesmí být odeslán regulátorovi.', 'TEST DATA — neither this preview nor a downloaded file may be submitted to a regulator.')}
              </div>
            )}

            {/* Visual control table */}
            <div style={{ overflowY: 'auto', padding: '0' }}>
              {previewData.status === 'unavailable' ? (
                <DataUnavailable kind={previewData.kind} service="FINREP / COREP service" feature={t('regulatorní šablony', 'regulatory templates')} lang="cs" dense />
              ) : (
                <>
                {previewData.status === 'ready' && previewData.evidence === 'LIVE_PREVIEW' && (
                  <div role="status" style={{ padding: '12px 20px', color: '#92400e', background: '#fffbeb', borderBottom: '1px solid #fde68a', fontSize: '12px' }}>
                    <strong>{t('Pracovní náhled skutečných hodnot', 'Working preview of actual values')}</strong>
                    {' — '}{t('období ještě není zapečetěné. Hodnoty se mohou změnit; finální regulatorní export zůstává zablokovaný.', 'the period is not sealed yet. Values may change; final regulatory export remains blocked.')}
                  </div>
                )}
                <table className="data-table" style={{ width: '100%' }}>
                  <thead>
                    <tr>
                      <th style={{ width: '42%' }}>{t('Pole', 'Field')}</th>
                      <th>{t('Hodnota', 'Value')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {buildExportRows(preview, previewData).map((row, i) => (
                      <tr key={i}>
                        <td style={{ fontSize: '12px', color: 'var(--text-secondary)', fontWeight: 500 }}>{row.field}</td>
                        <td style={{ fontSize: '12px', color: row.value.includes('DATOVÁ MEZERA') ? '#b45309' : 'var(--text-primary)', fontFamily: row.value === '—' ? 'inherit' : 'JetBrains Mono, monospace' }}>{row.value}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                </>
              )}
            </div>

            {/* Footer: note + export actions */}
            <div style={{ padding: '14px 20px', borderTop: '1px solid var(--border)', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '12px', flexWrap: 'wrap' }}>
              <div
                role="status"
                data-testid="export-readiness"
                style={{ fontSize: '11px', color: 'var(--text-tertiary)', maxWidth: '320px' }}
              >
                {exportReadiness.ok ? (
                  <div style={{ display: 'flex', gap: '8px', alignItems: 'flex-start', marginBottom: '8px', color: 'var(--success)' }}>
                    <CheckCircle2 size={14} aria-hidden="true" style={{ flexShrink: 0, marginTop: '1px' }} />
                    <strong>{t('Připraveno pro interní export', 'Ready for internal export')}</strong>
                  </div>
                ) : (() => {
                  const copy = blockReasonCopy(exportReadiness.reason, exportReadiness.templateIds, t('cs', 'en') as 'cs' | 'en')
                  return (
                    <div
                      data-testid="export-blocked"
                      data-block-reason={exportReadiness.reason}
                      style={{ display: 'flex', gap: '8px', alignItems: 'flex-start', marginBottom: '8px', color: 'var(--danger)' }}
                    >
                      <Ban size={14} aria-hidden="true" style={{ flexShrink: 0, marginTop: '1px' }} />
                      <span>
                        <strong style={{ display: 'block' }}>{copy.title}</strong>
                        <span style={{ color: 'var(--text-tertiary)' }}>{copy.detail}</span>
                      </span>
                    </div>
                  )
                })()}
                {!exportReadiness.ok && (exportReadiness.reason === 'no_closed_periods' || exportReadiness.reason === 'provisional_data') && (
                  <Link href="/day-end?tab=regulatory" style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', marginBottom: '8px', color: 'var(--accent)', fontWeight: 700 }}>
                    {t('Otevřít regulatorní uzávěrku', 'Open regulatory close')} <ExternalLink size={11} aria-hidden="true" />
                  </Link>
                )}
                {previewData.status === 'unsupported'
                  ? t('Tento katalogový výkaz zatím nemá implementovaný datový zdroj ani odeslání. Nezobrazuje fiktivní hodnoty.', 'This catalogue report has no implemented data source or submission path yet. It does not show fictional values.')
                  : t('FINREP/COREP se při načtení ověřují ve finrep-service nad ledger trial balance; při nedostupnosti se hodnoty nezobrazí. ClickHouse ani ČNB XBRL/SDAT přenos nejsou součástí tohoto náhledu.', 'FINREP/COREP are verified on load from finrep-service over the ledger trial balance; values are not shown when unavailable. ClickHouse and ČNB XBRL/SDAT transmission are not part of this preview.')}
              </div>
              <div style={{ display: 'flex', gap: '8px', flexShrink: 0 }}>
                <button type="button" className="btn btn-secondary" aria-label={t('Exportovat náhled jako CSV', 'Export preview as CSV')} style={{ fontSize: '12px' }} onClick={() => exportCsv(preview)} disabled={!exportReadiness.ok}>
                  <FileSpreadsheet size={13} aria-hidden="true" /> {t('Export CSV', 'Export CSV')}
                </button>
                <button type="button" className="btn btn-primary" aria-label={t('Exportovat náhled jako JSON', 'Export preview as JSON')} style={{ fontSize: '12px' }} onClick={() => exportJson(preview)} disabled={!exportReadiness.ok}>
                  <FileJson size={13} aria-hidden="true" /> {t('Export JSON', 'Export JSON')}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
