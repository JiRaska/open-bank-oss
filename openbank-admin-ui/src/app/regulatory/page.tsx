// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

'use client'

import { useState } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { FileText, Send, CheckCircle2, Clock, AlertTriangle, ExternalLink, Calendar, Check, Eye, X, Table as TableIcon, FileJson, FileSpreadsheet } from 'lucide-react'

type Report = (typeof REPORTS)[number]

// A single, shared shape for what an export contains — used both to render the
// visual preview table and to serialise to JSON/CSV, so the operator sees
// exactly what will be downloaded (no drift between preview and file).
interface ExportRow {
  field: string
  value: string
}

function buildExportRows(report: Report): ExportRow[] {
  const meta: ExportRow[] = [
    { field: 'ID výkazu', value: report.id },
    { field: 'Název', value: report.name },
    { field: 'Autorita', value: report.authority },
    { field: 'Regulace', value: report.regulation },
    { field: 'Kód SDAT', value: report.sdatCode },
    { field: 'Frekvence', value: report.frequency },
    { field: 'Termín', value: report.deadline },
    { field: 'Stav', value: report.status },
    { field: 'Poslední podání', value: report.lastSubmitted },
    { field: 'Příští termín', value: report.nextDue },
  ]
  const fields: ExportRow[] = report.fields.map((f) => ({ field: f, value: '—' }))
  return [...meta, ...fields]
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
    lastSubmitted: '2026-05-15',
    nextDue: '2026-06-15',
    status: 'pending',
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
    lastSubmitted: '2026-05-15',
    nextDue: '2026-06-15',
    status: 'pending',
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
    lastSubmitted: '2026-05-10',
    nextDue: 'Ad-hoc',
    status: 'ok',
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
    lastSubmitted: '2026-04-30',
    nextDue: '2026-07-30',
    status: 'ok',
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
    lastSubmitted: '2026-04-30',
    nextDue: '2026-07-30',
    status: 'ok',
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
    lastSubmitted: '2026-03-28',
    nextDue: '2027-03-31',
    status: 'ok',
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
    lastSubmitted: '2025-06-28',
    nextDue: '2026-06-30',
    status: 'pending',
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
    lastSubmitted: '—',
    nextDue: 'Ad-hoc',
    status: 'ok',
    description: 'Hlášení závažných ICT incidentů dle DORA — počáteční, průběžné a závěrečné hlášení',
    sdatCode: 'DORA-INC',
    fields: ['Klasifikace incidentu', 'Dopad na zákazníky', 'RTO/RPO', 'Příčina', 'Nápravná opatření'],
  },
]

const STATUS_CONFIG = {
  ok:      { label: 'Podáno',    color: '#16a34a', bg: '#f0fdf4', icon: <CheckCircle2 size={13} /> },
  pending: { label: 'Čeká',      color: '#d97706', bg: '#fffbeb', icon: <Clock size={13} /> },
  overdue: { label: 'Po termínu', color: '#dc2626', bg: '#fef2f2', icon: <AlertTriangle size={13} /> },
}

export default function RegulatoryPage() {
  const [selected, setSelected] = useState<string | null>(null)
  const { t } = useLanguage()
  const [generating, setGenerating] = useState<string | null>(null)
  const [downloadMessage, setDownloadMessage] = useState<string | null>(null)
  // The export now opens a visual preview first (operator visually checks the
  // table before committing to a download) — `preview` holds the report whose
  // export is being inspected.
  const [preview, setPreview] = useState<Report | null>(null)

  async function generateReport(id: string) {
    setGenerating(id)
    await new Promise(r => setTimeout(r, 1500))
    setGenerating(null)
    alert(`Report ${id} vygenerován (demo — v produkci by se odeslal přes API)`)
  }

  function openPreview(id: string, e: React.MouseEvent) {
    e.stopPropagation()
    const report = REPORTS.find(r => r.id === id)
    if (report) setPreview(report)
  }

  function exportJson(report: Report) {
    const rows = buildExportRows(report)
    const data = {
      ...Object.fromEntries(rows.map(r => [r.field, r.value])),
      keyFields: report.fields,
      exportedAt: new Date().toISOString(),
    }
    triggerDownload(
      `report_${report.sdatCode}_${new Date().toISOString().slice(0, 10)}.json`,
      JSON.stringify(data, null, 2),
      'application/json',
    )
    flashDownloaded(report.id)
  }

  function exportCsv(report: Report) {
    const rows = buildExportRows(report)
    const header = `${csvCell(t('Pole', 'Field'))},${csvCell(t('Hodnota', 'Value'))}`
    const body = rows.map(r => `${csvCell(r.field)},${csvCell(r.value)}`).join('\n')
    triggerDownload(
      `report_${report.sdatCode}_${new Date().toISOString().slice(0, 10)}.csv`,
      `${header}\n${body}\n`,
      'text/csv;charset=utf-8',
    )
    flashDownloaded(report.id)
  }

  function flashDownloaded(id: string) {
    setDownloadMessage(id)
    setTimeout(() => setDownloadMessage(null), 3000)
  }

  const overdue = REPORTS.filter(r => r.status === 'overdue').length
  const pending = REPORTS.filter(r => r.status === 'pending').length

  const authorities = REPORTS.reduce((acc, r) => {
    const key = r.authority.includes('CNB') ? 'ČNB' : r.authority.includes('FAÚ') ? 'FAÚ' : r.authority.includes('ECB') ? 'ECB' : 'Ostatní'
    acc[key] = (acc[key] || 0) + 1
    return acc
  }, {} as Record<string, number>)

  const sortedByDate = [...REPORTS].filter(r => r.nextDue !== 'Ad-hoc').sort((a, b) => a.nextDue.localeCompare(b.nextDue))
  const upcoming = sortedByDate.slice(0, 3)

  return (
    <div>
      <div className="page-header">
        <div>
          <div className="breadcrumb">
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Regulatorní výkaznictví', 'Regulatory Reporting')}</span>
          </div>
          <h1 className="page-title" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <FileText size={18} style={{ color: 'var(--accent)' }} />
            {t('Regulatorní výkaznictví', 'Regulatory Reporting')}
          </h1>
          <p className="page-subtitle">{t('CNB SDAT · FAÚ · COREP/FINREP · ECB · FATCA/CRS · DORA ICT incidenty', 'CNB SDAT · FAÚ · COREP/FINREP · ECB · FATCA/CRS · DORA ICT incidents')}</p>
        </div>
        <a href="https://www.cnb.cz/cs/dohled-financni-trh/vykaznictvi/" target="_blank" rel="noreferrer" className="btn btn-secondary">
          <ExternalLink size={13} />
          {t('CNB Výkaznictví', 'CNB Reporting')}
        </a>
      </div>

      {/* Alert banner for overdue */}
      {overdue > 0 && (
        <div style={{ padding: '12px 16px', marginBottom: '16px', background: '#fef2f2', border: '1px solid #fecaca', borderRadius: 'var(--r-lg)', display: 'flex', gap: '10px', alignItems: 'center' }}>
          <AlertTriangle size={16} style={{ color: '#dc2626', flexShrink: 0 }} />
          <div style={{ fontSize: '13px', color: '#991b1b', fontWeight: 600 }}>
            {overdue} výkaz{overdue > 1 ? 'y jsou' : ' je'} po termínu podání! Okamžitě kontaktujte compliance oddělení.
          </div>
        </div>
      )}

      {/* Summary */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))', gap: '12px', marginBottom: '20px' }}>
        {[
          { label: t('Celkem výkazů', 'Total Reports'), value: REPORTS.length, color: 'var(--accent)' },
          { label: t('Podáno', 'Submitted'), value: REPORTS.filter(r => r.status === 'ok').length, color: '#16a34a' },
          { label: t('Čeká', 'Pending'), value: pending, color: '#d97706' },
          { label: t('Po termínu', 'Overdue'), value: overdue, color: '#dc2626' },
        ].map(s => (
          <div key={s.label} className="card" style={{ padding: '14px 16px' }}>
            <div style={{ fontSize: '22px', fontWeight: 700, color: s.color }}>{s.value}</div>
            <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '2px' }}>{s.label}</div>
          </div>
        ))}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: '16px', marginBottom: '24px' }}>
        <div className="card" style={{ padding: '16px' }}>
          <h3 style={{ fontSize: '13px', fontWeight: 700, marginBottom: '12px', color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '6px' }}><CheckCircle2 size={14} /> {t('Rozložení stavů', 'Status breakdown')}</h3>
          <div style={{ display: 'flex', height: '20px', borderRadius: '6px', overflow: 'hidden', marginBottom: '16px' }}>
            {['ok', 'pending', 'overdue'].map(st => {
              const count = REPORTS.filter(r => r.status === st).length;
              if (count === 0) return null;
              const percent = (count / REPORTS.length) * 100;
              const color = STATUS_CONFIG[st as keyof typeof STATUS_CONFIG].color;
              return <div key={st} style={{ width: `${percent}%`, background: color }} title={`${STATUS_CONFIG[st as keyof typeof STATUS_CONFIG].label}: ${count}`} />
            })}
          </div>
          <div style={{ display: 'flex', gap: '16px', fontSize: '12px' }}>
            {['ok', 'pending', 'overdue'].map(st => {
              const count = REPORTS.filter(r => r.status === st).length;
              const cfg = STATUS_CONFIG[st as keyof typeof STATUS_CONFIG];
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
          <h3 style={{ fontSize: '13px', fontWeight: 700, marginBottom: '16px', color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '6px' }}><Calendar size={14} /> {t('Nejbližší termíny', 'Upcoming deadlines')}</h3>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
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
          const cfg = STATUS_CONFIG[report.status as keyof typeof STATUS_CONFIG]
          const isSelected = selected === report.id
          return (
            <div key={report.id} className="card" style={{ overflow: 'hidden', borderLeft: `3px solid ${cfg.color}` }}>
              <div style={{ padding: '14px 16px', display: 'flex', alignItems: 'center', gap: '12px', cursor: 'pointer', flexWrap: 'wrap' }}
                onClick={() => setSelected(s => s === report.id ? null : report.id)}>
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
                  <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{t('Příští termín', 'Next due')}</div>
                  <div style={{ fontSize: '12px', fontWeight: 600, color: report.status === 'overdue' ? '#dc2626' : 'var(--text-primary)', display: 'flex', alignItems: 'center', gap: '4px' }}>
                    <Calendar size={11} />
                    {report.nextDue}
                  </div>
                </div>

                {/* Actions */}
                <div style={{ display: 'flex', gap: '6px', flexShrink: 0 }} onClick={e => e.stopPropagation()}>
                  <button className="btn btn-secondary" style={{ fontSize: '11px', padding: '5px 10px' }}
                    onClick={(e) => openPreview(report.id, e)}>
                    {downloadMessage === report.id ? <><Check size={11} style={{ color: '#16a34a' }} /> {t('Staženo', 'Downloaded')}</> : <><Eye size={11} /> {t('Náhled exportu', 'Preview export')}</>}
                  </button>
                  <button className="btn btn-primary" style={{ fontSize: '11px', padding: '5px 10px' }}
                    onClick={() => generateReport(report.id)} disabled={generating === report.id}>
                    {generating === report.id ? <><Clock size={11} className="animate-spin" /> {t('Generuji…', 'Generating…')}</> : <><Send size={11} /> {t('Odeslat SDAT', 'Submit SDAT')}</>}
                  </button>
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
                        <strong>{t('Poslední podání:', 'Last submitted:')}</strong> {report.lastSubmitted}
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

                  {/* SDAT integration note */}
                  <div style={{ padding: '10px 12px', background: '#eff6ff', border: '1px solid #bfdbfe', borderRadius: '6px', fontSize: '12px', color: '#1e40af' }}>
                    <strong>{t('SDAT integrace:', 'SDAT integration:')}</strong> {t('Výkaz se odesílá přes CNB SDAT systém (https://sdat.cnb.cz).', 'Report is submitted via CNB SDAT system (https://sdat.cnb.cz).')}
                    {t('Kód výkazu:', 'Report code:')} <code style={{ fontFamily: 'JetBrains Mono, monospace', background: '#dbeafe', padding: '1px 4px', borderRadius: '3px' }}>{report.sdatCode}</code>.
                    {t('Formát: XML dle CNB XSD schématu. Autentizace: certifikát vydaný CNB.', 'Format: XML per CNB XSD schema. Authentication: certificate issued by CNB.')}
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
              <button className="btn btn-secondary" style={{ padding: '5px', flexShrink: 0 }} onClick={() => setPreview(null)} aria-label={t('Zavřít', 'Close')}>
                <X size={15} />
              </button>
            </div>

            {/* Visual control table */}
            <div style={{ overflowY: 'auto', padding: '0' }}>
              <table className="data-table" style={{ width: '100%' }}>
                <thead>
                  <tr>
                    <th style={{ width: '42%' }}>{t('Pole', 'Field')}</th>
                    <th>{t('Hodnota', 'Value')}</th>
                  </tr>
                </thead>
                <tbody>
                  {buildExportRows(preview).map((row, i) => (
                    <tr key={i}>
                      <td style={{ fontSize: '12px', color: 'var(--text-secondary)', fontWeight: 500 }}>{row.field}</td>
                      <td style={{ fontSize: '12px', color: 'var(--text-primary)', fontFamily: row.value === '—' ? 'inherit' : 'JetBrains Mono, monospace' }}>{row.value}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Footer: note + export actions */}
            <div style={{ padding: '14px 20px', borderTop: '1px solid var(--border)', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '12px', flexWrap: 'wrap' }}>
              <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', maxWidth: '320px' }}>
                {t(
                  'Hodnoty polic výkazu „—“ se v produkci doplní z dat služeb; zde slouží náhled ke kontrole struktury.',
                  'Report-field values shown as “—” are populated from service data in production; this preview verifies the structure.',
                )}
              </div>
              <div style={{ display: 'flex', gap: '8px', flexShrink: 0 }}>
                <button className="btn btn-secondary" style={{ fontSize: '12px' }} onClick={() => exportCsv(preview)}>
                  <FileSpreadsheet size={13} /> {t('Export CSV', 'Export CSV')}
                </button>
                <button className="btn btn-primary" style={{ fontSize: '12px' }} onClick={() => exportJson(preview)}>
                  <FileJson size={13} /> {t('Export JSON', 'Export JSON')}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
