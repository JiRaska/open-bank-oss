// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import { useEffect, useState } from 'react'
import { BrainCircuit, ExternalLink, Sparkles } from 'lucide-react'
import { getAgentPersona } from '@/components/agent/AgentIdentity'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { TestAgentFinding } from '@/lib/types/test-intelligence'
import { useSession } from 'next-auth/react'

type AgentGovernance = {
  activePrompt: string | null
  evalEvidence: 'recorded' | 'awaiting-recording' | 'missing-suite' | 'unavailable'
}

const FLAKY_HUNTER_EVAL_BACKLOG_URL = 'https://github.com/JiRaska/open-bank-oss/issues/7040'

function evalEvidenceDetail(state: AgentGovernance['evalEvidence'], t: (cs: string, en: string) => string): string {
  switch (state) {
    case 'recorded': return t('Scénář i ověřený záznam odpovídají aktuálnímu promptu.', 'A scenario suite and verified recording match the current prompt.')
    case 'awaiting-recording': return t('Scénář existuje, ale chybí skutečný záznam modelového běhu; nejde o důkaz kvality.', 'A scenario exists, but a real model-run recording is missing; this is not quality evidence.')
    case 'missing-suite': return t('Pro tento charter není registrovaná eval suite. Agent zůstává poradní, ne automatizační autorita.', 'No eval suite is registered for this charter. The agent remains advisory, never an automation authority.')
    case 'unavailable': return t('Governance snapshot není v tomto prostředí dostupný; stav agenta se z něj nesmí odvozovat.', 'The governance snapshot is unavailable in this environment; agent trust must not be inferred.')
  }
}

export function TestAgentPanel() {
  const { language, t } = useLanguage()
  const persona = getAgentPersona('flaky-test-hunter', language)
  const { data: session } = useSession()
  const canAnalyze = session?.user?.roles?.includes('ROLE_ADMIN') ?? false
  const [findings, setFindings] = useState<TestAgentFinding[]>([])
  const [available, setAvailable] = useState<boolean | null>(null)
  const [analyzing, setAnalyzing] = useState(false)
  const [governance, setGovernance] = useState<AgentGovernance | null>(null)
  useEffect(() => { void fetch('/api/test-intelligence/agents', { cache: 'no-store' }).then(async response => {
    if (!response.ok) { setAvailable(false); return }
    const body = await response.json() as { findings: TestAgentFinding[]; available: boolean; governance?: AgentGovernance }
    setFindings(body.findings); setAvailable(body.available); setGovernance(body.governance ?? null)
  }).catch(() => setAvailable(false)) }, [])
  return <section style={{ marginTop: 20, border: `1px solid ${persona.glow}`, borderRadius: 14, padding: 18, background: `linear-gradient(125deg, ${persona.shell}, var(--surface-1) 58%)` }}>
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16, alignItems: 'flex-start' }}><div style={{ display: 'flex', gap: 12 }}><div style={{ width: 38, height: 38, display: 'grid', placeItems: 'center', borderRadius: 12, color: persona.accent, background: persona.glow }}><BrainCircuit size={20}/></div><div><div style={{ fontSize: 11, color: persona.accent, fontWeight: 750, letterSpacing: '.08em' }}>AI AGENT · {persona.name}</div><h2 style={{ fontSize: 16, margin: '4px 0' }}>{persona.role}</h2><p style={{ margin: 0, color: 'var(--text-secondary)', fontSize: 12 }}>{persona.purpose}</p></div></div><Sparkles size={18} style={{ color: persona.accent }}/></div>
    <div style={{ marginTop: 14, borderTop: '1px solid var(--border)', paddingTop: 12 }}>
      {governance && <div aria-label={t('Evidence správy AI agenta', 'AI agent governance evidence')} style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 12, fontSize: 11 }}>
        <span style={{ border: '1px solid var(--border)', borderRadius: 999, padding: '4px 8px', color: 'var(--text-secondary)' }}>{t('Aktivní prompt', 'Active prompt')}: <strong>{governance.activePrompt ?? t('neověřený', 'unverified')}</strong></span>
        <span style={{ border: '1px solid var(--border)', borderRadius: 999, padding: '4px 8px', color: governance.evalEvidence === 'recorded' ? '#16a34a' : '#d97706' }}>{t('Eval evidence', 'Eval evidence')}: <strong>{governance.evalEvidence}</strong></span>
        <span style={{ flexBasis: '100%', color: 'var(--text-secondary)' }}>{evalEvidenceDetail(governance.evalEvidence, t)}</span>
        {governance.evalEvidence !== 'recorded' && governance.evalEvidence !== 'unavailable' && <a href={FLAKY_HUNTER_EVAL_BACKLOG_URL} target="_blank" rel="noreferrer" style={{ color: persona.accent, fontWeight: 650 }}>
          {t('Otevřít backlog evalů', 'Open evaluation backlog')} <ExternalLink size={12} aria-hidden="true" />
        </a>}
      </div>}
      {canAnalyze && <button className="btn btn-secondary btn-sm" disabled={analyzing} onClick={() => {
        setAnalyzing(true)
        void fetch('/api/test-intelligence/agents', { method: 'POST' }).then(async response => {
          if (!response.ok) { setAvailable(false); return }
          const body = await response.json() as { findings: TestAgentFinding[]; available: boolean; governance?: AgentGovernance }
          setFindings(body.findings); setAvailable(body.available); setGovernance(body.governance ?? null)
        }).catch(() => setAvailable(false)).finally(() => setAnalyzing(false))
      }} style={{ marginBottom: 12 }}><Sparkles size={13}/>{analyzing ? t('Analyzuji snapshot…', 'Analyzing snapshot…') : t('Analyzovat aktuální evidence', 'Analyze current evidence')}</button>}
      {available === null ? <span style={{ color: 'var(--text-tertiary)', fontSize: 12 }}>{t('Načítám agentní nálezy…', 'Loading agent findings…')}</span>
        : !available ? <span style={{ color: 'var(--text-tertiary)', fontSize: 12 }}>{t('Agent v tomto prostředí není dostupný. Měřená evidence výše zůstává úplná a beze změny.', 'The agent is unavailable in this environment. Measured evidence above remains complete and unchanged.')}</span>
          : findings.length === 0 ? <span style={{ color: 'var(--text-secondary)', fontSize: 12 }}>{t('Žádné aktivní nálezy. To není důkaz bezchybnosti; jen aktuální výstup agentova omezeného charteru.', 'No active findings. This is not proof of correctness; only the current output of the agent’s bounded charter.')}</span>
            : <div style={{ display: 'grid', gap: 8 }}>{findings.slice(0, 5).map(finding => <div key={finding.id} style={{ display: 'grid', gridTemplateColumns: '90px 1fr auto', gap: 10, alignItems: 'center', fontSize: 12 }}><span style={{ color: finding.severity === 'CRITICAL' ? '#dc2626' : '#d97706', fontWeight: 700 }}>{finding.severity}</span><span><strong>{finding.component}</strong> · {finding.title}{finding.rootCause && <small style={{ display: 'block', color: 'var(--text-tertiary)', marginTop: 3 }}>{finding.rootCause}</small>}</span>{finding.proposalUrl && <a href={finding.proposalUrl} target="_blank" rel="noreferrer" aria-label={t('Otevřít návrh agenta', 'Open agent proposal')}><ExternalLink size={14}/></a>}</div>)}</div>}
    </div>
  </section>
}
