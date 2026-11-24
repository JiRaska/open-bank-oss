// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
'use client'

// ADR-0383 customer MCP channel explainer (#16606). Every badge on this page comes from
// `resolved`, which the server derived from repository data (mcpChannelControls.ts). The page
// itself never decides that a control is in force.

import Link from 'next/link'
import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { Bot, ChevronLeft, ChevronRight, CheckCircle, Circle, CircleDashed, CircleHelp, ExternalLink, Info, Pause, Play, Printer, ShieldAlert, ShieldCheck, Smartphone } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'
import {
  ATTACKS, CHANNEL_CONTROLS, FLOW_STEPS, RESIDUAL_RISK,
  type AdrFact, type Evidence, type EvidenceStatus, type ResolvedControl, type ResolvedEvidence,
} from '@/lib/docs/mcpChannelControls'

type T = (cs: string, en: string) => string
type Bi = readonly [string, string]

const REPO = 'https://github.com/JiRaska/open-bank-oss'
const STEP_MS = 4000

export interface McpChannelExplainerProps {
  resolved: Record<string, ResolvedControl>
  adr: AdrFact | null
  sourcesAvailable: { gates: boolean; adrs: boolean }
  asOf?: EvidenceAsOf
}

/** Which repository state the badges describe: whatever was baked into this build. */
export interface EvidenceAsOf {
  gateRef: string | null
  gateGeneratedAt: string | null
  adrSource: string | null
  adrSha: string | null
  adrCollectedAt: string | null
  adrImageBuiltAt: string | null
}

const STATUS_RANK: Record<EvidenceStatus, number> = { unknown: 0, planned: 1, partial: 2, active: 3 }

/** A claim spanning several controls is only as delivered as its least-delivered control. */
export function combineStatus(statuses: EvidenceStatus[]): EvidenceStatus {
  if (statuses.length === 0) return 'unknown'
  return statuses.reduce((low, next) => (STATUS_RANK[next] < STATUS_RANK[low] ? next : low))
}

export function McpChannelExplainer({ resolved, adr, sourcesAvailable, asOf }: McpChannelExplainerProps) {
  const { t } = useLanguage()
  const tr = (pair: Bi) => t(pair[0], pair[1])
  const statusOf = (...ids: string[]) => combineStatus(ids.map(id => resolved[id]?.own.status ?? 'unknown'))
  const adrHref = adr ? `/docs/adr/${adr.slug}` : '/docs/adr'

  return (
    <div className="docs-printable">
      <DocsPageHeader
        crumbs={<>
          <span>OpenBank</span><span className="breadcrumb-sep">/</span>
          <Link href="/docs" style={{ color: 'inherit', textDecoration: 'none' }}>{t('Dokumentace', 'Docs')}</Link>
          <span className="breadcrumb-sep">/</span>
          <span className="breadcrumb-current">{t('Kanál MCP pro zákazníky', 'Customer MCP channel')}</span>
        </>}
        title={t('Asistent čte a navrhuje, zákazník schvaluje v aplikaci', 'The assistant reads and proposes, the customer approves in the app')}
        subtitle={t(
          'Kanál MCP pro zákazníky, jak ho navrhuje ADR-0383: zákazník připojí AI asistenta podle svého výběru ke svým datům v bance. Asistent má smět číst a navrhnout platbu, ale nikdy ji autorizovat: to má udělat jen zákazník v bankovní aplikaci se SCA.',
          'The customer MCP channel as ADR-0383 designs it: a customer connects an AI assistant of their choice to their bank data. The assistant is to read and propose a payment, never authorise one: only the customer does that, in the banking app, with SCA.',
        )}
        icon={<Bot aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
        actions={
          <button type="button" className="docs-print-action" onClick={() => window.print()}>
            <Printer size={14} aria-hidden="true" />
            {t('Exportovat PDF', 'Export PDF')}
          </button>
        }
      />

      <DeliveryBanner adr={adr} adrHref={adrHref} sourcesAvailable={sourcesAvailable} asOf={asOf} />

      <Section title={t('Připojení ChatGPT a Claude', 'Connecting ChatGPT and Claude')}>
        <p>{t(
          'Integrace ChatGPT a Claude vychází z hostovaného OAuth toku Tier H podle ADR-0385. Klient zahájí připojení a zákazník v bankovní aplikaci vybere vlastní účty a schválí přístup pomocí SCA. Udělené připojení může v aplikaci odvolat.',
          'The ChatGPT and Claude integrations follow the hosted Tier H OAuth design in ADR-0385. The client starts the connection, then the customer selects their own accounts and approves access using SCA in the banking app. The customer can revoke the connection in the app.',
        )}</p>
        <p>{t(
          'Lokální klienti používají DPoP. U hostovaných klientů banka ověřuje registrovaného klienta a podepsanou vazbu tokenu na schválené připojení. Pro načtení zůstatku vydá bankovní autorizační server krátkodobý token pro konkrétní účet. Služba Balance ověřuje grant, vlastníka účtu a certifikát volající služby.',
          'Local clients use DPoP. For hosted clients the bank verifies the registered client and the signed token binding to the approved connection. For a balance read, the bank authorization server issues a short-lived token for the selected account. Balance verifies the grant, account owner, and caller certificate.',
        )}</p>
        <p>{t(
          'Stav zákaznického vydání ověřujeme ve sdíleném sandboxu. Dokladem je build v TestFlight a úspěšné schválení v mobilu s načtením testovacího zůstatku zvlášť v ChatGPT a Claude.',
          'Customer release is verified in the shared sandbox. The evidence is a TestFlight build and a successful mobile approval and test balance read in both ChatGPT and Claude.',
        )}</p>
      </Section>

      {/* One-screen story */}
      <Section title={t('Příběh na jednu obrazovku', 'The story on one screen')}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(210px, 1fr))', gap: 12 }}>
          <StoryCard status={statusOf('client-admission')} n={1} title={t('Připojí', 'Connect')} body={t('Zákazník připojí asistenta. Banka mu ukáže, co asistent smí, a zákazník to potvrdí v bance.', 'The customer connects the assistant. The bank shows what it may do, and the customer confirms at the bank.')} />
          <StoryCard status={statusOf('dpop', 'grant', 'scoped-read')} n={2} title={t('Čte', 'Read')} body={t('Asistent čte jen data z vlastních účtů, které zákazník vybral při schválení.', 'The assistant reads only the accounts the customer selected during approval.')} />
          <StoryCard status={statusOf('proposal', 'policy', 'downstream-no-write')} n={3} title={t('Navrhne', 'Propose')} body={t('Asistent může platbu jen navrhnout. Peníze se tím nepohnou.', 'The assistant can only propose a payment. No money moves.')} />
          <StoryCard status={statusOf('sca', 'admission')} n={4} title={t('Zákazník schválí', 'Customer approves')} body={t('Platba odejde jen po schválení v bankovní aplikaci se SCA, stejně jako kdyby ji zákazník zadal sám.', 'The payment leaves only after approval in the banking app with SCA, exactly as if the customer had entered it.')} />
        </div>
        <Callout tone="accent">
          <Claim status={statusOf('policy', 'downstream-no-write', 'proposal', 'sca')}>
            <strong>{t('MCP nikdy nemůže autorizovat platbu.', 'MCP can never authorise a payment.')}</strong>{' '}
            {t(
              'Ani zmanipulovaný model, ani škodlivý klient se nedostane přes to, že každá platba z tohoto kanálu potřebuje SCA zákazníka v aplikaci, bez výjimek.',
              'Neither a manipulated model nor a malicious client gets past the rule that every payment from this channel needs the customer\'s SCA in the app, with no exemptions.',
            )}
          </Claim>
        </Callout>
      </Section>

      <FlowStepper resolved={resolved} />

      {/* Defence in depth */}
      <Section
        title={t('Obrana do hloubky', 'Defence in depth')}
        subtitle={t(
          'Každá vrstva: co zastaví, jaký útok porazí a čím se to dokazuje. Stav se odvozuje z repozitáře. Dokud vrstva nemá vlastní důkaz (gate nebo test), její stav je stav dodávky ADR-0383 jako celku; vlastní důkaz má zatím jen CI pojistka (gate mcp-customer-channel-propose-only).',
          'Each layer: what it stops, which attack it defeats and how it is proven. Status is derived from the repository. Until a layer has evidence of its own (a gate or a test), its status is ADR-0383\'s delivery status as a whole; so far only the CI tripwire has its own (gate mcp-customer-channel-propose-only).',
        )}
      >
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12.5 }}>
            <caption className="sr-only">{t('Vrstvy ochrany kanálu MCP pro zákazníky', 'Protection layers of the customer MCP channel')}</caption>
            <thead>
              <tr style={{ textAlign: 'left', color: 'var(--text-secondary)', borderBottom: '1px solid var(--border)' }}>
                <th scope="col" style={th}>{t('Vrstva', 'Layer')}</th>
                <th scope="col" style={th}>{t('Co zastaví', 'What it stops')}</th>
                <th scope="col" style={th}>{t('Útok', 'Attack defeated')}</th>
                <th scope="col" style={th}>{t('Čím se dokazuje (ADR-0383)', 'How it is proven (ADR-0383)')}</th>
                <th scope="col" style={th}>{t('Stav a důkazy', 'Status and evidence')}</th>
              </tr>
            </thead>
            <tbody>
              {CHANNEL_CONTROLS.map(control => {
                const state = resolved[control.id]
                return (
                  <tr key={control.id} id={`layer-${control.id}`} style={{ borderBottom: '1px solid var(--border)' }}>
                    <th scope="row" style={{ ...td, textAlign: 'left', color: 'var(--text-primary)', fontWeight: 700 }}>
                      {tr(control.name)}
                      <div style={{ fontSize: 11, color: 'var(--text-tertiary)', fontWeight: 600 }}>ADR-0383 {control.decision}</div>
                    </th>
                    <td style={{ ...td, color: 'var(--text-secondary)' }}>{tr(control.stops)}</td>
                    <td style={{ ...td, color: 'var(--text-secondary)' }}>{tr(control.attack)}</td>
                    <td style={{ ...td, color: 'var(--text-secondary)', lineHeight: 1.5 }}>{tr(control.proof)}</td>
                    <td style={{ ...td, minWidth: 220 }}>
                      {state && <EvidenceLine resolved={state.own} label={t('Tento kanál', 'This channel')} />}
                      {state && control.foundations.length > 0 && (
                        <div data-foundations style={{ marginTop: 6, paddingTop: 6, borderTop: '1px dashed var(--border)' }}>
                          <div style={{ fontSize: 10.5, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--text-secondary)', marginBottom: 4 }}>
                            {t('Existující části platformy (ne tento kanál)', 'Existing platform pieces (not this channel)')}
                          </div>
                          {control.foundations.map((foundation, index) => (
                            <EvidenceLine key={index} resolved={state.foundations[index]} label={tr(foundation.label)} />
                          ))}
                        </div>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
        <StatusLegend />
      </Section>

      {/* Attack gallery */}
      <Section
        title={t('Galerie útoků', 'Attack gallery')}
        subtitle={t('Co by útočník zkusil, co se stane a která vrstva ho zastaví.', 'What an attacker would try, what happens, and which layer stops it.')}
      >
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 12 }}>
          {ATTACKS.map(attack => (
            <article key={attack.id} className="card" style={{ padding: 14, borderLeft: '3px solid var(--danger)' }}>
              <h3 style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13.5, fontWeight: 700, margin: '0 0 6px', color: 'var(--text-primary)' }}>
                <ShieldAlert size={14} aria-hidden="true" style={{ color: 'var(--danger-text)' }} />{tr(attack.name)}
              </h3>
              <p style={{ fontSize: 12.5, color: 'var(--text-secondary)', margin: '0 0 6px', lineHeight: 1.5 }}>
                <strong style={{ color: 'var(--text-primary)' }}>{t('Pokus: ', 'Attempt: ')}</strong>{tr(attack.scenario)}
              </p>
              <p style={{ fontSize: 12.5, color: 'var(--text-secondary)', margin: '0 0 8px', lineHeight: 1.5 }}>
                <strong style={{ color: 'var(--text-primary)' }}>{t('Výsledek: ', 'Outcome: ')}</strong>
                <Claim status={statusOf(...attack.controls)}>{tr(attack.outcome)}</Claim>
              </p>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {attack.controls.map(id => {
                  const control = CHANNEL_CONTROLS.find(item => item.id === id)
                  const state = resolved[id]
                  return control ? (
                    <a key={id} href={`#layer-${id}`} style={{ textDecoration: 'none' }}>
                      <Tag>{tr(control.name)} · {statusLabel(state?.own.status ?? 'unknown', t)}</Tag>
                    </a>
                  ) : null
                })}
              </div>
            </article>
          ))}
        </div>
        <Callout tone="warning">
          <strong>{t('Zbytkové riziko: ', 'Residual risk: ')}</strong>{tr(RESIDUAL_RISK)}
        </Callout>
      </Section>

      <CustomerViewMock />

      {/* Links */}
      <Section title={t('Odkazy', 'Links')}>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          <Link href={adrHref} style={linkBtn}>ADR-0383</Link>
          <Link href="/docs/threat-models/mcp-service" style={linkBtn}>{t('Model hrozeb mcp-service', 'mcp-service threat model')}</Link>
          <a href={`${REPO}/blob/main/docs/runbooks/svc-mcp.md`} style={linkBtn} target="_blank" rel="noreferrer">
            {t('Runbook mcp-service', 'mcp-service runbook')} <ExternalLink size={11} aria-hidden="true" />
          </a>
          <a href={`${REPO}/issues/16605`} style={linkBtn} target="_blank" rel="noreferrer">
            {t('Pozorovatelnost kanálu (#16605)', 'Channel observability (#16605)')} <ExternalLink size={11} aria-hidden="true" />
          </a>
          <a href={`${REPO}/issues/16590`} style={linkBtn} target="_blank" rel="noreferrer">
            {t('Plán dodávky (#16590)', 'Delivery plan (#16590)')} <ExternalLink size={11} aria-hidden="true" />
          </a>
        </div>
        <p style={{ fontSize: 12, color: 'var(--text-tertiary)', margin: '10px 0 0' }}>
          {t(
            'Model hrozeb popisuje také zákaznické čtení, vazbu na schválený grant a odvolání přístupu.',
            'The threat model also covers customer reads, the approved grant binding, and revocation.',
          )}
        </p>
      </Section>
    </div>
  )
}

// ── Delivery banner: derived from ADR-0383's own front-matter ─────────────────

function DeliveryBanner({ adr, adrHref, sourcesAvailable, asOf }: { adr: AdrFact | null; adrHref: string; sourcesAvailable: { gates: boolean; adrs: boolean }; asOf?: EvidenceAsOf }) {
  const { t } = useLanguage()
  const decision = adr?.decision.toLowerCase() ?? null
  const delivery = adr?.deliveryStatus ?? null
  const deliveryText = delivery === 'shipped' ? t('dodáno', 'shipped')
    : delivery === 'partial' ? t('částečně dodáno', 'partially delivered')
      : delivery === 'planned' ? t('plánováno', 'planned') : t('neznámý', 'unknown')
  const decisionText = decision === 'accepted' ? t('schváleno', 'accepted') : decision === 'proposed' ? t('navrženo, v review', 'proposed, in review') : t('neznámý', 'unknown')
  return (
    <div role="note" className="card" style={{ padding: 14, marginBottom: 16, display: 'flex', gap: 10, alignItems: 'flex-start', background: delivery === 'shipped' ? 'var(--success-bg)' : 'var(--warning-bg)', border: `1px solid ${delivery === 'shipped' ? 'var(--success-border)' : 'var(--warning-border)'}` }}>
      <Info size={16} aria-hidden="true" style={{ color: delivery === 'shipped' ? 'var(--success-text)' : 'var(--warning-text)', flexShrink: 0, marginTop: 2 }} />
      <div style={{ fontSize: 13, color: 'var(--text-primary)', lineHeight: 1.55 }}>
        <div>
          <Link href={adrHref} style={{ color: 'inherit', fontWeight: 700 }}>ADR-0383</Link>
          {' · '}{t('rozhodnutí', 'decision')}: <strong>{decisionText}</strong>
          {' · '}{t('dodávka', 'delivery')}: <strong>{deliveryText}</strong>
        </div>
        {delivery !== 'shipped' && (
          <div style={{ color: 'var(--text-secondary)' }}>
            {t(
              'Rozsah implementace dokládají jednotlivé kontroly níže. Dostupnost pro zákazníky vyžaduje ověření nasazení a celého toku.',
              'The controls below track implementation evidence. Customer availability requires a verified deployment and end-to-end journey.',
            )}
          </div>
        )}
        <AsOfLine asOf={asOf} />
        {(!sourcesAvailable.gates || !sourcesAvailable.adrs) && (
          <div style={{ color: 'var(--text-secondary)' }}>
            {t('Část zdrojových dat (katalog gate nebo registr ADR) v tomto buildu chybí, takže dotčené stavy jsou „neznámé“.', 'Some source data (gate catalog or ADR registry) is missing from this build, so the affected statuses read "unknown".')}
          </div>
        )}
      </div>
    </div>
  )
}

function shortSha(sha: string | null): string | null {
  return sha && /^[a-f0-9]{7,40}$/.test(sha) ? sha.slice(0, 10) : sha
}

function AsOfLine({ asOf }: { asOf?: EvidenceAsOf }) {
  const { t } = useLanguage()
  const none = t('v buildu nezaznamenáno', 'not recorded in this build')
  const adrWhen = asOf?.adrCollectedAt ?? asOf?.adrImageBuiltAt ?? null
  return (
    <div data-as-of="" style={{ color: 'var(--text-secondary)', fontSize: 12, marginTop: 2 }}>
      {t('Stav k tomuto buildu', 'As of this build')}{': '}
      {t('katalog gate', 'gate catalog')} {asOf?.gateGeneratedAt ?? none} @ {shortSha(asOf?.gateRef ?? null) ?? none}
      {' · '}
      {t('korpus ADR', 'ADR corpus')} ({asOf?.adrSource ?? none}) {adrWhen ?? none} @ {shortSha(asOf?.adrSha ?? null) ?? none}
    </div>
  )
}

// ── Stepped / animated flow ───────────────────────────────────────────────────

const REDUCED_MOTION = '(prefers-reduced-motion: reduce)'

function subscribeReducedMotion(onChange: () => void): () => void {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return () => {}
  const query = window.matchMedia(REDUCED_MOTION)
  query.addEventListener?.('change', onChange)
  return () => query.removeEventListener?.('change', onChange)
}

/** Server render and a missing matchMedia both assume reduced motion: auto-play is opt-in. */
function usePrefersReducedMotion(): boolean {
  return useSyncExternalStore(
    subscribeReducedMotion,
    () => typeof window.matchMedia !== 'function' || window.matchMedia(REDUCED_MOTION).matches,
    () => true,
  )
}

export function FlowStepper({ resolved }: { resolved: Record<string, ResolvedControl> }) {
  const { t } = useLanguage()
  const tr = (pair: Bi) => t(pair[0], pair[1])
  const [active, setActive] = useState(0)
  const [playRequested, setPlaying] = useState(false)
  const prevRef = useRef<HTMLButtonElement>(null)
  const nextRef = useRef<HTMLButtonElement>(null)
  const reducedMotion = usePrefersReducedMotion()
  const playing = playRequested && !reducedMotion
  const last = FLOW_STEPS.length - 1

  useEffect(() => {
    if (!playing) return
    const timer = window.setTimeout(() => {
      if (active >= last) setPlaying(false)
      else setActive(active + 1)
    }, STEP_MS)
    return () => window.clearTimeout(timer)
  }, [playing, active, last])

  const step = FLOW_STEPS[active]
  const control = CHANNEL_CONTROLS.find(item => item.id === step.control)
  const status = resolved[step.control]?.own.status ?? 'unknown'

  return (
    <Section
      title={t('Jak to funguje, krok za krokem', 'How it works, step by step')}
      subtitle={t('Od připojení po kontrolní řetězec platby. Krok vyberete tlačítky nebo kliknutím.', 'From connecting to the payment admission chain. Pick a step with the buttons or by clicking it.')}
    >
      <ol aria-label={t('Kroky toku', 'Flow steps')} style={{ listStyle: 'none', padding: 0, margin: '0 0 14px', display: 'flex', flexWrap: 'wrap', gap: 6 }}>
        {FLOW_STEPS.map((item, index) => {
          const current = index === active
          const done = index < active
          return (
            <li key={item.id}>
              <button
                type="button"
                aria-current={current ? 'step' : undefined}
                onClick={() => { setPlaying(false); setActive(index) }}
                className="mcp-flow-step"
                style={{
                  display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12, fontWeight: 700, cursor: 'pointer',
                  padding: '5px 10px', borderRadius: 20,
                  color: current ? 'var(--accent-text)' : done ? 'var(--text-primary)' : 'var(--text-secondary)',
                  background: current ? 'var(--accent-bg)' : 'var(--surface-2)',
                  border: `1px solid ${current ? 'var(--accent-border)' : 'var(--border)'}`,
                  transition: reducedMotion ? 'none' : 'background-color 200ms ease, color 200ms ease',
                }}
              >
                <span aria-hidden="true" style={{ fontVariantNumeric: 'tabular-nums' }}>{index + 1}</span>
                {tr(item.title)}
              </button>
            </li>
          )
        })}
      </ol>

      <div aria-live="polite" className="card" style={{ padding: 16, background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
        <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginBottom: 6 }}>
          <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' }}>
            {t(`Krok ${active + 1} z ${FLOW_STEPS.length}`, `Step ${active + 1} of ${FLOW_STEPS.length}`)}: {tr(step.title)}
            <span style={{ fontWeight: 600, color: 'var(--text-tertiary)', fontSize: 12 }}> · {tr(step.actor)}</span>
          </div>
          <StatusBadge status={status} />
        </div>
        <p style={{ fontSize: 13, color: 'var(--text-secondary)', margin: 0, lineHeight: 1.6 }}><Claim status={status}>{tr(step.body)}</Claim></p>
        {control && (
          <a href={`#layer-${control.id}`} style={{ display: 'inline-block', marginTop: 8, fontSize: 12, fontWeight: 600, color: 'var(--accent-text)' }}>
            {t('Vrstva', 'Layer')}: {tr(control.name)} (ADR-0383 {control.decision})
          </a>
        )}
        <div aria-hidden="true" style={{ height: 4, borderRadius: 4, background: 'var(--border)', marginTop: 12, overflow: 'hidden' }}>
          <div style={{ height: '100%', width: `${((active + 1) / FLOW_STEPS.length) * 100}%`, background: 'var(--accent)', transition: reducedMotion ? 'none' : 'width 300ms ease' }} />
        </div>
      </div>

      <div style={{ display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap', alignItems: 'center' }}>
        <button ref={prevRef} type="button" className="btn btn-secondary" disabled={active === 0}
          onClick={() => {
            const target = Math.max(0, active - 1)
            setPlaying(false); setActive(target)
            // The button is about to disable itself; keep keyboard focus on a live control (WCAG 2.4.3).
            if (target === 0) nextRef.current?.focus()
          }}>
          <ChevronLeft size={14} aria-hidden="true" /> {t('Předchozí', 'Previous')}
        </button>
        <button ref={nextRef} type="button" className="btn btn-secondary" disabled={active === last}
          onClick={() => {
            const target = Math.min(last, active + 1)
            setPlaying(false); setActive(target)
            if (target === last) prevRef.current?.focus()
          }}>
          {t('Další', 'Next')} <ChevronRight size={14} aria-hidden="true" />
        </button>
        {!reducedMotion && (
          <button type="button" className="btn btn-secondary" aria-pressed={playing}
            onClick={() => { if (!playing && active === last) setActive(0); setPlaying(!playing) }}>
            {playing ? <Pause size={14} aria-hidden="true" /> : <Play size={14} aria-hidden="true" />}
            {playing ? t('Pozastavit', 'Pause') : t('Přehrát', 'Play')}
          </button>
        )}
        {reducedMotion && (
          <span style={{ fontSize: 12, color: 'var(--text-tertiary)' }}>
            {t('Automatické přehrávání je vypnuté kvůli nastavení omezení pohybu.', 'Auto-play is off because reduced motion is preferred.')}
          </span>
        )}
      </div>
    </Section>
  )
}

// ── Customer view (illustrative) ──────────────────────────────────────────────

function CustomerViewMock() {
  const { t } = useLanguage()
  return (
    <Section
      title={t('Co vidí zákazník', 'What the customer sees')}
      subtitle={t(
        'Ilustrativní maketa, ne skutečné obrazovky. Zákaznické obrazovky patří do samostatné zákaznické aplikace (ADR-0064) a zatím nejsou postavené. Klient i data jsou smyšlené.',
        'Illustrative mock, not real screens. The customer screens belong to the separate customer app (ADR-0064) and are not built yet. The client and the data are invented.',
      )}
    >
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(230px, 1fr))', gap: 12 }}>
        <Phone label={t('Připojení', 'Connect')}>
          <MockTitle>{t('Připojit „Example Assistant“?', 'Connect "Example Assistant"?')}</MockTitle>
          <MockList items={[
            t('Uvidí zůstatky a pohyby na vybraných účtech', 'Will see balances and transactions on the chosen accounts'),
            t('Může navrhnout platbu; schválíte ji vy, tady', 'May propose a payment; you approve it, here'),
            t('Data odcházejí k poskytovateli klienta', 'Data leaves the bank to the client\'s provider'),
            t('Přístup vyprší za 180 dní', 'Access expires in 180 days'),
          ]} />
          <MockButton primary>{t('Potvrdit se SCA', 'Confirm with SCA')}</MockButton>
        </Phone>
        <Phone label={t('Připojení klienti', 'Connected clients')}>
          <MockTitle>{t('Připojení klienti', 'Connected clients')}</MockTitle>
          <MockRow title="Example Assistant" sub={t('Připojeno 3. 11. · naposledy dnes', 'Connected 3 Nov · last used today')} action={t('Odvolat', 'Revoke')} />
          <MockRow title="Budget Helper" sub={t('Připojeno 12. 9. · naposledy 1. 10.', 'Connected 12 Sep · last used 1 Oct')} action={t('Odvolat', 'Revoke')} />
          <p style={mockNote}>{t('O každém novém připojení dostanete notifikaci.', 'You are notified of every new connection.')}</p>
        </Phone>
        <Phone label={t('Schválení', 'Approval')}>
          <MockTitle>{t('Návrh platby od „Example Assistant“', 'Payment proposal from "Example Assistant"')}</MockTitle>
          <MockField k={t('Částka', 'Amount')} v="120.00 EUR" />
          <MockField k="IBAN" v="XX00 0000 0000 0000 0000" />
          <MockField k={t('Ověření příjemce', 'Payee check')} v={t('Jméno se shoduje', 'Name matches')} />
          <MockField k={t('Zpráva (od asistenta)', 'Reference (from the assistant)')} v={t('Nájem listopad', 'Rent November')} muted />
          <div style={{ display: 'flex', gap: 6, marginTop: 8 }}>
            <MockButton primary>{t('Schválit se SCA', 'Approve with SCA')}</MockButton>
            <MockButton>{t('Odmítnout', 'Reject')}</MockButton>
          </div>
        </Phone>
        <Phone label={t('Odvolání', 'Revoke')}>
          <MockTitle>{t('Odvolat „Example Assistant“?', 'Revoke "Example Assistant"?')}</MockTitle>
          <p style={mockNote}>{t('Přístup skončí hned při dalším volání, i když token ještě neexpiroval. Asistent pak nic nepřečte ani nenavrhne.', 'Access ends on the very next call, even if a token has not expired. The assistant then cannot read or propose anything.')}</p>
          <MockButton danger>{t('Odvolat přístup', 'Revoke access')}</MockButton>
        </Phone>
      </div>
    </Section>
  )
}

// ── Status rendering: the only place a status becomes a word ──────────────────

export function statusLabel(status: EvidenceStatus, t: T): string {
  switch (status) {
    case 'active': return t('platí', 'in force')
    case 'partial': return t('částečně', 'partial')
    case 'planned': return t('plánováno', 'planned')
    default: return t('neznámý', 'unknown')
  }
}

const STATUS_STYLE: Record<EvidenceStatus, { color: string; bg: string; border: string; Icon: React.ElementType }> = {
  active: { color: 'var(--success-text)', bg: 'var(--success-bg)', border: 'var(--success-border)', Icon: CheckCircle },
  partial: { color: 'var(--warning-text)', bg: 'var(--warning-bg)', border: 'var(--warning-border)', Icon: CircleDashed },
  planned: { color: 'var(--text-primary)', bg: 'var(--surface-3)', border: 'var(--border-strong)', Icon: Circle },
  unknown: { color: 'var(--text-secondary)', bg: 'var(--surface-2)', border: 'var(--border)', Icon: CircleHelp },
}

/** `legend` badges explain the scale and carry no data-status, so they are never read as a control's state. */
export function StatusBadge({ status, legend }: { status: EvidenceStatus; legend?: boolean }) {
  const { t } = useLanguage()
  const style = STATUS_STYLE[status]
  const Icon = style.Icon
  return (
    <span data-status={legend ? undefined : status} style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 10.5, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.04em', color: style.color, background: style.bg, border: `1px solid ${style.border}`, padding: '1px 8px', borderRadius: 20, whiteSpace: 'nowrap' }}>
      <Icon size={11} aria-hidden="true" /> {statusLabel(status, t)}
    </span>
  )
}

function evidenceSource(evidence: Evidence, t: T): string {
  if (evidence.kind === 'gate') return `gate ${evidence.gateId}`
  if (evidence.kind === 'adr') return `ADR-${evidence.adr}`
  return t('deklarováno jako plán', 'declared as planned')
}

function basisText(resolved: ResolvedEvidence, t: T): string {
  switch (resolved.basis) {
    case 'gate-enforced': return t('vynucený v gates.yaml', 'enforced in gates.yaml')
    case 'gate-advisory': return t('jen advisory v gates.yaml', 'advisory only in gates.yaml')
    case 'gate-absent': return t('v gates.yaml zatím není', 'not in gates.yaml yet')
    case 'adr-shipped': return t('delivery-status: shipped', 'delivery-status: shipped')
    case 'adr-partial': return t('delivery-status: partial', 'delivery-status: partial')
    case 'adr-planned': return t('delivery-status: planned', 'delivery-status: planned')
    case 'adr-n-a': return t('ADR nemá osu dodávky', 'ADR has no delivery axis')
    case 'adr-absent': return t('ADR v tomto buildu chybí', 'ADR missing from this build')
    case 'declared-planned': return t('plán', 'plan')
    default: return t('zdroj nedostupný', 'source unavailable')
  }
}

function EvidenceLine({ resolved, label }: { resolved: ResolvedEvidence | undefined; label: string }) {
  const { t } = useLanguage()
  if (!resolved) return null
  const issue = resolved.evidence.kind === 'planned' ? resolved.evidence.issue : resolved.status === 'active' ? undefined : resolved.evidence.issue
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2, marginBottom: 8 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
        <StatusBadge status={resolved.status} />
        <span style={{ fontSize: 12, color: 'var(--text-primary)', fontWeight: 600 }}>{label}</span>
      </div>
      <span style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>
        {evidenceSource(resolved.evidence, t)} · {basisText(resolved, t)}
        {issue !== undefined && <> · <a href={`${REPO}/issues/${issue}`} target="_blank" rel="noreferrer" style={{ color: 'var(--accent-text)' }}>#{issue}</a></>}
      </span>
    </div>
  )
}

function StatusLegend() {
  const { t } = useLanguage()
  return (
    <p style={{ fontSize: 12, color: 'var(--text-tertiary)', margin: '10px 0 0', lineHeight: 1.6, display: 'flex', flexWrap: 'wrap', gap: 10, alignItems: 'center' }}>
      <span><StatusBadge status="active" legend /> {t('vynucený gate nebo ADR shipped', 'enforced gate or shipped ADR')}</span>
      <span><StatusBadge status="partial" legend /> {t('advisory gate nebo ADR partial', 'advisory gate or partial ADR')}</span>
      <span><StatusBadge status="planned" legend /> {t('gate zatím neexistuje nebo ADR planned', 'gate does not exist yet or ADR planned')}</span>
      <span><StatusBadge status="unknown" legend /> {t('zdroj v buildu chybí', 'source missing from the build')}</span>
    </p>
  )
}

// ── small UI helpers ──────────────────────────────────────────────────────────

function Section({ title, subtitle, children }: { title: string; subtitle?: string; children: React.ReactNode }) {
  return (
    <section className="card" style={{ padding: 20, marginBottom: 16 }}>
      <h2 style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)', margin: 0 }}>{title}</h2>
      {subtitle ? <p style={{ fontSize: 12.5, color: 'var(--text-secondary)', margin: '4px 0 14px' }}>{subtitle}</p> : <div style={{ height: 12 }} />}
      {children}
    </section>
  )
}

function StoryCard({ n, title, body, status }: { n: number; title: string; body: string; status: EvidenceStatus }) {
  return (
    <div style={{ padding: 14, borderRadius: 10, background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
        <span aria-hidden="true" style={{ width: 22, height: 22, borderRadius: 11, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: 12, fontWeight: 700, color: 'var(--accent-text)', background: 'var(--accent-bg)', border: '1px solid var(--accent-border)' }}>{n}</span>
        <span style={{ fontWeight: 700, fontSize: 13.5, color: 'var(--text-primary)' }}>{title}</span>
      </div>
      <div style={{ fontSize: 12.5, color: 'var(--text-secondary)', lineHeight: 1.5 }}><Claim status={status}>{body}</Claim></div>
    </div>
  )
}

/**
 * A sentence about how the channel behaves. Until the controls behind it are in force per the
 * repository, it is framed as design and carries the derived badge; present tense is earned only
 * by evidence. `data-claim` / `data-framed` let the test hold every such sentence to that rule.
 */
export function Claim({ status, children }: { status: EvidenceStatus; children: React.ReactNode }) {
  const { t } = useLanguage()
  if (status === 'active') return <span data-claim="" data-framed="false">{children}</span>
  return (
    <span data-claim="" data-framed="true">
      <StatusBadge status={status} />{' '}
      <em style={{ fontStyle: 'normal', fontWeight: 700, color: 'var(--text-primary)' }}>{t('Podle návrhu (ADR-0383): ', 'By design (ADR-0383): ')}</em>
      {children}
    </span>
  )
}

function Callout({ tone, children }: { tone: 'accent' | 'warning'; children: React.ReactNode }) {
  const bg = tone === 'accent' ? 'var(--accent-bg)' : 'var(--warning-bg)'
  const border = tone === 'accent' ? 'var(--accent-border)' : 'var(--warning-border)'
  const Icon = tone === 'accent' ? ShieldCheck : Info
  return (
    <div style={{ marginTop: 12, padding: 14, borderRadius: 10, background: bg, border: `1px solid ${border}`, display: 'flex', gap: 10 }}>
      <Icon size={16} aria-hidden="true" style={{ color: tone === 'accent' ? 'var(--accent-text)' : 'var(--warning-text)', flexShrink: 0, marginTop: 1 }} />
      <div style={{ fontSize: 13, color: 'var(--text-primary)', lineHeight: 1.55 }}>{children}</div>
    </div>
  )
}

function Tag({ children }: { children: React.ReactNode }) {
  return <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-secondary)', background: 'var(--surface-2)', border: '1px solid var(--border)', padding: '3px 9px', borderRadius: 20, display: 'inline-block' }}>{children}</span>
}

function Phone({ label, children }: { label: string; children: React.ReactNode }) {
  const { t } = useLanguage()
  return (
    <figure style={{ margin: 0 }}>
      <div style={{ borderRadius: 18, border: '2px solid var(--border-strong)', background: 'var(--surface-1, var(--surface-2))', padding: 12, minHeight: 250, position: 'relative' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
          <Smartphone size={13} aria-hidden="true" style={{ color: 'var(--text-tertiary)' }} />
          <span style={{ fontSize: 10, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--warning-text)', background: 'var(--warning-bg)', border: '1px solid var(--warning-border)', padding: '0 6px', borderRadius: 10 }}>
            {t('ilustrace', 'illustrative')}
          </span>
        </div>
        {children}
      </div>
      <figcaption style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-secondary)', textAlign: 'center', marginTop: 6 }}>{label}</figcaption>
    </figure>
  )
}

function MockTitle({ children }: { children: React.ReactNode }) {
  return <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 8 }}>{children}</div>
}

function MockList({ items }: { items: string[] }) {
  return (
    <ul style={{ margin: '0 0 10px', paddingLeft: 16, fontSize: 12, color: 'var(--text-secondary)', lineHeight: 1.5 }}>
      {items.map(item => <li key={item}>{item}</li>)}
    </ul>
  )
}

function MockRow({ title, sub, action }: { title: string; sub: string; action: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, padding: '8px 0', borderBottom: '1px solid var(--border)' }}>
      <div>
        <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--text-primary)' }}>{title}</div>
        <div style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>{sub}</div>
      </div>
      <span style={{ fontSize: 11.5, fontWeight: 700, color: 'var(--danger-text)' }}>{action}</span>
    </div>
  )
}

function MockField({ k, v, muted }: { k: string; v: string; muted?: boolean }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8, fontSize: 12, padding: '3px 0' }}>
      <span style={{ color: 'var(--text-tertiary)' }}>{k}</span>
      <span style={{ color: muted ? 'var(--text-secondary)' : 'var(--text-primary)', fontWeight: muted ? 400 : 600, fontStyle: muted ? 'italic' : 'normal', textAlign: 'right' }}>{v}</span>
    </div>
  )
}

function MockButton({ children, primary, danger }: { children: React.ReactNode; primary?: boolean; danger?: boolean }) {
  return (
    <span aria-hidden="true" style={{ display: 'inline-block', marginTop: 6, fontSize: 12, fontWeight: 700, padding: '6px 10px', borderRadius: 8,
      color: primary ? 'var(--accent-text)' : danger ? 'var(--danger-text)' : 'var(--text-primary)',
      background: primary ? 'var(--accent-bg)' : danger ? 'var(--danger-bg)' : 'var(--surface-3)',
      border: `1px solid ${primary ? 'var(--accent-border)' : danger ? 'var(--danger-border)' : 'var(--border)'}` }}>
      {children}
    </span>
  )
}

const mockNote: React.CSSProperties = { fontSize: 12, color: 'var(--text-secondary)', lineHeight: 1.5, margin: '8px 0' }
const th: React.CSSProperties = { padding: '10px 12px', fontWeight: 700, fontSize: 12 }
const td: React.CSSProperties = { padding: '10px 12px', verticalAlign: 'top' }
const linkBtn: React.CSSProperties = { display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 12.5, fontWeight: 600, color: 'var(--accent-text)', background: 'var(--accent-bg)', border: '1px solid var(--accent-border)', padding: '6px 12px', borderRadius: 8, textDecoration: 'none' }
