// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
'use client'

import Link from 'next/link'
import {
  FileSignature, Circle, CheckCircle, Hash,
  FileText, ShieldAlert, Info,
} from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'

const ACCENT = '#6366f1'
const INK = 'var(--text-primary)'
const SUB = 'var(--text-secondary)'

export default function DocumentManagementDocsPage() {
  const { t } = useLanguage()

  return (
    <div>
      <DocsPageHeader
        crumbs={<>
          <span>OpenBank</span><span className="breadcrumb-sep">/</span>
          <span>{t('Dokumentace', 'Docs')}</span><span className="breadcrumb-sep">/</span>
          <span className="breadcrumb-current">{t('Správa dokumentů', 'Document Management')}</span>
        </>}
        title={t('Správa dokumentů, šablon a e-podpisu', 'Document Management, Templating & E-Signature')}
        subtitle={t(
            'Nová hraniční doména (openbank-document-service): šablony → generování PDF → uložení → podpisová ceremonie → audit → downstream konzumenti.',
            'A new bounded context (openbank-document-service): templates → PDF generation → storage → signature ceremony → audit → downstream consumers.',
          )}
        icon={<FileSignature aria-hidden="true" size={18} style={{ color: ACCENT }} />}
      />

      {/* Status strip */}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 16 }}>
        <Pill color="#059669" bg="#ecfdf5" border="#6ee7b7" Icon={CheckCircle} label={t('Fáze 1 (AdES/PAdES-B): ve výstavbě', 'Phase 1 (AdES/PAdES-B): shipping now')} />
        <Pill color="#94a3b8" bg="#f8fafc" border="#cbd5e1" Icon={Circle} label={t('Fáze 2 (QES/QSeal/HSM): plánováno', 'Phase 2 (QES/QSeal/HSM): planned')} />
        <Link href="/docs/adr/0161-object-storage-standard-for-application-documents" style={{ textDecoration: 'none' }}>
          <Pill color={ACCENT} bg="var(--accent-bg)" border="var(--accent-border)" Icon={Hash} label="ADR-0161" />
        </Link>
        <Link href="/docs/adr/0162-document-management-templating-and-e-signature-architecture" style={{ textDecoration: 'none' }}>
          <Pill color={ACCENT} bg="var(--accent-bg)" border="var(--accent-border)" Icon={Hash} label="ADR-0162" />
        </Link>
        <Pill color="#0891b2" bg="#ecfeff" border="#a5f3fc" Icon={ShieldAlert} label={t('mimo peněžní cestu', 'non-money-path')} />
      </div>

      {/* What it is */}
      <Section title={t('Co to je', 'What it is')}>
        <p style={{ color: SUB, fontSize: 14, lineHeight: 1.6, margin: 0 }}>
          {t(
            'Platforma umí přesouvat peníze, prověřovat klienty a vést nesmazatelný audit hash-chain, ale dosud neuměla vyprodukovat dokument, který člověk podepíše. Chybí čtyři schopnosti: (1) šablonování — nikde neexistuje šablonovací engine, (2) generování PDF — statement-service dnes vrací text/plain, (3) e-podpis — neexistuje podpisová ceremonie, jen SCA (schválení zařízením) a audit hash-chain, (4) úložiště dokumentů — dispute-service otevřeně přiznává „no blob storage“, party-service ukládá soubory jako BYTEA s poznámkou „nahradit S3“. Šablony se sice váží na produkty (product-catalog má TermsAndConditions.url), ale mnoho dokumentů produktově vázané není — výpisy, GDPR export, korespondence sporů, KYC formuláře. Proto je to vlastní ohraničená doména, ne rozšíření product-catalogu.',
            'The platform can move money, screen customers, and keep a tamper-evident audit hash-chain — but it could not, until now, produce a document a human signs. Four capabilities were missing: (1) templating — no template engine existed anywhere, (2) PDF generation — statement-service today emits text/plain, (3) e-signature — no signing ceremony existed, only SCA (device approval) and the audit hash-chain, (4) a document store — dispute-service says outright "no blob storage is implemented", party-service stores files as BYTEA with a "replace with S3" TODO. Templates do bind to products (product-catalog carries a TermsAndConditions.url), but many documents are not product-bound — statements, GDPR exports, dispute correspondence, KYC forms. That is why this is its own bounded context, not an extension of product-catalog.',
          )}
        </p>
      </Section>

      {/* Architecture flow */}
      <Section
        title={t('Architektura toku dokumentu', 'Document flow architecture')}
        subtitle={t(
          'Od editace šablony v admin-ui po reakci navazujících služeb na podepsanou událost — vlastní JSX/SVG diagram (žádný Mermaid.js, žádný screenshot), rekonstruující tok z ADR-0162.',
          'From editing a template in admin-ui to a downstream service reacting to the signed event — a hand-built JSX/SVG diagram (no Mermaid.js, no screenshot), recreating the flow described in ADR-0162.',
        )}
      >
        <ArchitectureFlowDiagram />
      </Section>

      {/* Why not money-path */}
      <Section title={t('Proč to není na peněžní cestě', 'Why this is not money-path')}>
        <div className="card" style={{ padding: 14, background: 'var(--accent-bg)', border: '1px solid var(--accent-border)', display: 'flex', gap: 10 }}>
          <Info size={16} style={{ color: ACCENT, flexShrink: 0, marginTop: 1 }} />
          <div style={{ fontSize: 13, color: INK, lineHeight: 1.6 }}>
            {t(
              'openbank-document-service nikdy nesedí jako blokující brána na cestě uvolnění peněz. Ceremonie po dokončení emituje domácí události (DOCUMENT_SIGNED, SIGNATURE_CEREMONY_COMPLETED) přes Kafka — navazující služby (lending, account-opening) na ně reagují asynchronně, nikoli synchronním voláním, které by čekalo na podpis. Toto je stejné rozvázání jako u ADR-0086 (podepsaná smlouva je událost, ne inline autorizační volání) a drží službu na „lehkých kolejích“ governance: nemá stejné SLA/HA nároky jako platební rail, degradace je otázka dostupnosti dokumentu, ne výpadku platby.',
              'openbank-document-service never sits as a blocking gate on the fund-release path. Once a ceremony completes it emits domain events (DOCUMENT_SIGNED, SIGNATURE_CEREMONY_COMPLETED) over Kafka — downstream consumers (lending, account-opening) react to them asynchronously rather than via a synchronous call that would block on a signature. This is the same decoupling as ADR-0086 (a signed-contract event, not an inline authorization call) and keeps the service on the "light governance rails": it does not carry the same SLA/HA bar as a payment rail — degradation here is a document-availability event, not a payment outage.',
            )}
          </div>
        </div>
      </Section>

      {/* Two-tier signature model + onboarding */}
      <Section
        title={t('Dva podpisy, ne jeden: klientův podpis vs. bankovní pečeť', 'Two signatures, not one: the client signature vs. the bank seal')}
        subtitle={t(
          'eIDAS rozlišuje el. podpis fyzické osoby (Art. 3(10)) a el. pečeť právnické osoby (Art. 3(25)) — ADR-0162 D4 (pokračování) tohle nyní odráží kryptograficky, ne jen v auditním záznamu.',
          'eIDAS distinguishes a natural person\'s electronic signature (Art. 3(10)) from a legal entity\'s electronic seal (Art. 3(25)) — ADR-0162 D4 (continued) now reflects that cryptographically, not just in the audit trail.',
        )}
      >
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
          <div className="card" style={{ flex: '1 1 320px', padding: 14, background: 'var(--surface-2)' }}>
            <div style={{ fontSize: 13, fontWeight: 700, color: INK, marginBottom: 6 }}>
              {t('Klient — jednorázový certifikát', 'Client — one-time certificate')}
            </div>
            <p style={{ fontSize: 12.5, color: SUB, lineHeight: 1.6, margin: 0 }}>
              {t(
                'Při každém podpisu vydá OpenBao PKI engine (pki-document-signing, stejný vzor jako pki-agent z ADR-0031) čerstvý certifikát jen pro tento úkon. Soukromý klíč se nikam neukládá — použije se a zahodí. Důvěryhodnost stojí na vydávající CA bezpečně uložené v OpenBao, ne na životnosti jednoho certifikátu.',
                'Every signing act gets a fresh certificate from OpenBao\'s PKI secrets engine (pki-document-signing, the same pattern as pki-agent from ADR-0031). The private key is never persisted anywhere — used once, then discarded. Trust rests on the issuing CA staying safely in OpenBao, not on any one leaf certificate\'s lifetime.',
              )}
            </p>
          </div>
          <div className="card" style={{ flex: '1 1 320px', padding: 14, background: 'var(--surface-2)' }}>
            <div style={{ fontSize: 13, fontWeight: 700, color: INK, marginBottom: 6 }}>
              {t('Banka — stabilní organizační pečeť', 'The bank — stable organizational seal')}
            </div>
            <p style={{ fontSize: 12.5, color: SUB, lineHeight: 1.6, margin: 0 }}>
              {t(
                'Naproti tomu bankovní pečeť používá dlouhodobý certifikát uložený jako OpenBao KV secret, promítnutý přes ExternalSecrets Operator do PKCS12 keystore — stejný vzor, jaký document-service už používá pro Kafka mTLS. Aplikuje se jako poslední vrstva, až po podpisu všech signatářů.',
                'The bank\'s seal, by contrast, uses a long-lived certificate stored as an OpenBao KV secret, projected via ExternalSecrets Operator into a PKCS12 keystore — the same pattern document-service already uses for its Kafka mTLS identity. It is applied as the last layer, only after every signer has decided.',
              )}
            </p>
          </div>
        </div>
        <p style={{ fontSize: 12.5, color: SUB, lineHeight: 1.6, margin: '12px 0 0' }}>
          {t(
            'Vizuální podoba podpisu (razítko/obrázek na stránce) zatím není — obě vrstvy jsou čistě kryptografická PAdES anotace, bez dopadu na právní platnost. Vědomé TODO, ne opomenutí.',
            'There is no visual signature appearance yet (a stamp/image on the page) — both layers are pure cryptographic PAdES annotations with no bearing on legal validity. A deliberate TODO, not an oversight.',
          )}
        </p>
      </Section>

      {/* Onboarding integration */}
      <Section
        title={t('Napojení na onboarding — první reálný volající', 'Onboarding integration — the first real caller')}
        subtitle={t(
          'Šablonování a e-podpis existovaly, ale nic je reálně nevolalo — jen přímé API/admin-ui. Založení účtu je první byznysový tok, který to využívá.',
          'Templating and e-signature existed, but nothing real called them — only direct API/admin-ui. Account opening is the first business flow that actually uses it.',
        )}
      >
        <div className="card" style={{ padding: 14, background: 'var(--accent-bg)', border: '1px solid var(--accent-border)', display: 'flex', gap: 10 }}>
          <Info size={16} style={{ color: ACCENT, flexShrink: 0, marginTop: 1 }} />
          <div style={{ fontSize: 13, color: INK, lineHeight: 1.6 }}>
            {t(
              'document-service si sám odebírá existující událost account.created (stejné téma jako balance-service pro založení nulového zůstatku) — account-service se o dokumentech vůbec nemusí dozvědět. Po přijetí události se dotáže product-catalogu na TermsAndConditions.documentTemplateCode daného produktu a pokud existuje, vyrenderuje aktuální PUBLISHED verzi šablony (bez pevné verze — viz politika řešení verzí níže) a otevře podpisovou ceremonii pro majitele účtu. Idempotentní vůči opakovanému doručení Kafky.',
              'document-service subscribes to the existing account.created event itself (the same topic balance-service already consumes for zero-balance initialization) — account-service never needs to know documents exist. On receiving the event it asks product-catalog for the product\'s TermsAndConditions.documentTemplateCode and, if bound, renders the current PUBLISHED template version (no pinned version — see the version-resolution policy below) and opens a signature ceremony for the account holder. Idempotent against Kafka\'s at-least-once redelivery.',
            )}
          </div>
        </div>
      </Section>

      {/* Phased rollout */}
      <Section
        title={t('Fázovaný rozjezd kryptografického zapečetění', 'Phased cryptographic sealing rollout')}
        subtitle={t('Ceremonie (kdo podepisuje co, v jakém pořadí) je hotová od fáze 1 — mění se jen úroveň kryptografické záruky.', 'The ceremony (who signs what, in what order) is built from phase 1 — only the cryptographic assurance level changes.')}
      >
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13, minWidth: 640 }}>
            <thead>
              <tr style={{ textAlign: 'left', color: SUB, background: 'var(--surface-2)' }}>
                <th style={th}>{t('Fáze', 'Phase')}</th>
                <th style={th}>{t('Úroveň podpisu', 'Signature level')}</th>
                <th style={th}>{t('Mechanismus', 'Mechanism')}</th>
                <th style={th}>{t('Stav', 'Status')}</th>
              </tr>
            </thead>
            <tbody>
              <tr style={{ borderTop: '1px solid var(--border)' }}>
                <td style={{ ...td, fontWeight: 700, color: INK }}>{t('Fáze 1', 'Phase 1')}</td>
                <td style={{ ...td, color: SUB }}>{t('Zdokonalený el. podpis (AdES)', 'Advanced electronic signature (AdES)')}</td>
                <td style={{ ...td, color: SUB }}>{t('Server aplikuje PAdES-B pečeť organizačním certifikátem + SCA-vázaný audit hash-chain jako důkaz', 'Server-applied PAdES-B seal with an organizational certificate + SCA-bound audit-chain evidence')}</td>
                <td style={td}><span style={{ fontSize: 11, fontWeight: 700, color: '#059669' }}>{t('ve výstavbě', 'shipping now')}</span></td>
              </tr>
              <tr style={{ borderTop: '1px solid var(--border)' }}>
                <td style={{ ...td, fontWeight: 700, color: INK }}>{t('Fáze 2', 'Phase 2')}</td>
                <td style={{ ...td, color: SUB }}>{t('Kvalifikovaný el. podpis (QES/QSeal)', 'Qualified signature (QES/QSeal)')}</td>
                <td style={{ ...td, color: SUB }}>{t('EU DSS (referenční eIDAS knihovna) produkuje PAdES-LTA, klíč v HSM/OpenBao (aktivuje dlouho odloženou ADR-0007 úschovu)', 'EU DSS (the eIDAS reference library) producing PAdES-LTA, keyed by HSM/OpenBao custody (activates the long-parked ADR-0007 QSeal custody)')}</td>
                <td style={td}><span style={{ fontSize: 11, fontWeight: 700, color: '#64748b' }}>{t('plánováno', 'planned')}</span></td>
              </tr>
            </tbody>
          </table>
        </div>
      </Section>

      {/* Docs / references */}
      <Section title={t('Dokumenty', 'Documents')}>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <Link href="/docs/adr/0162-document-management-templating-and-e-signature-architecture" style={linkBtn}>ADR-0162 — {t('rozhodnutí architektury', 'architecture decision')}</Link>
          <Link href="/docs/adr/0161-object-storage-standard-for-application-documents" style={linkBtn}>ADR-0161 — {t('standard úložiště objektů', 'object-storage standard')}</Link>
          <Link href="/document-templates" style={linkBtn}>
            <FileText size={12} style={{ marginRight: 4, verticalAlign: '-2px' }} />
            {t('Konzole šablon dokumentů', 'Document Templates console')}
          </Link>
        </div>
      </Section>
    </div>
  )
}

// ── Architecture flow diagram (hand-built JSX + SVG overlay, no Mermaid.js) ─────
// Recreates the ADR-0162 mermaid flowchart as absolutely-positioned node boxes
// (easy multi-line text) with a single SVG line-overlay for the connectors —
// same "hand-drawn diagram, not a screenshot" house convention as qrlesspay's
// inline <SequenceDiagram>, adapted for a branching flowchart rather than a
// two-actor sequence.
function ArchitectureFlowDiagram() {
  const { t } = useLanguage()
  const W = 800, H = 970

  type Node = { id: string; x: number; y: number; w: number; h: number; color: string; titleCs: string; titleEn: string; subCs: string; subEn: string }
  const nodes: Node[] = [
    { id: 'onboarding',      x: 20,  y: 104, w: 200, h: 64, color: '#16a34a', titleCs: 'Událost account.created', titleEn: 'account.created event', subCs: 'account-service → onboarding (ADR-0086)', subEn: 'account-service → onboarding trigger (ADR-0086)' },
    { id: 'data',            x: 20,  y: 198, w: 200, h: 64, color: '#94a3b8', titleCs: 'Business data', titleEn: 'Business data', subCs: 'produkt / party / úvěr', subEn: 'product / party / loan' },
    { id: 'editor',          x: 250, y: 10,  w: 280, h: 64, color: ACCENT,    titleCs: 'Editor šablon v admin-ui', titleEn: 'Admin-ui template editor', subCs: 'ROLE_COMPLIANCE · textarea + náhled', subEn: 'ROLE_COMPLIANCE · textarea + preview' },
    { id: 'registry',        x: 250, y: 104, w: 280, h: 64, color: ACCENT,    titleCs: 'Registr šablon', titleEn: 'Template registry', subCs: 'DRAFT → PUBLISHED → RETIRED, 1 aktuální/kód', subEn: 'DRAFT → PUBLISHED → RETIRED, 1 current/code' },
    { id: 'render',          x: 250, y: 198, w: 280, h: 64, color: '#0284c7', titleCs: 'Vykreslení', titleEn: 'Render', subCs: 'TemplateRenderPort · bez pevné verze = aktuální', subEn: 'TemplateRenderPort · no pinned version = current' },
    { id: 'pdf',             x: 250, y: 292, w: 280, h: 64, color: '#0284c7', titleCs: 'PDF', titleEn: 'PDF', subCs: 'WeasyPrint výchozí · Gotenberg volitelně', subEn: 'WeasyPrint default · Gotenberg opt-in' },
    { id: 'store',           x: 560, y: 292, w: 200, h: 64, color: '#64748b', titleCs: 'Objektové úložiště', titleEn: 'Object store', subCs: 'S3 WORM / Postgres (ADR-0161)', subEn: 'S3 WORM / Postgres (ADR-0161)' },
    { id: 'ceremony',        x: 250, y: 386, w: 280, h: 64, color: '#7c3aed', titleCs: 'Podpisová ceremonie', titleEn: 'Signature ceremony', subCs: 'orchestrace Temporal, více podepisujících', subEn: 'Temporal-orchestrated, multi-signer' },
    { id: 'signer',          x: 250, y: 480, w: 280, h: 64, color: '#7c3aed', titleCs: 'Vazba podepisujícího', titleEn: 'Signer binding', subCs: 'SCA (ADR-0021) + souhlas', subEn: 'SCA (ADR-0021) + consent' },
    { id: 'client_signature', x: 250, y: 574, w: 280, h: 74, color: '#059669', titleCs: 'ClientSignatureIssuerPort', titleEn: 'ClientSignatureIssuerPort', subCs: 'el. podpis klienta · jednorázový cert', subEn: 'client\'s e-signature · one-time cert' },
    { id: 'openbao_pki',     x: 560, y: 574, w: 200, h: 64, color: '#64748b', titleCs: 'OpenBao PKI engine', titleEn: 'OpenBao PKI engine', subCs: 'pki-document-signing (vzor ADR-0031)', subEn: 'pki-document-signing (ADR-0031 pattern)' },
    { id: 'bank_seal',       x: 250, y: 678, w: 280, h: 74, color: '#059669', titleCs: 'SignatureSealPort', titleEn: 'SignatureSealPort', subCs: 'el. pečeť banky · stabilní cert, aplikuje se poslední', subEn: 'the bank\'s e-seal · stable cert, applied last' },
    { id: 'openbao_kv',      x: 560, y: 678, w: 200, h: 64, color: '#64748b', titleCs: 'OpenBao KV', titleEn: 'OpenBao KV', subCs: 'stabilní keystore banky (ESO)', subEn: 'stable bank keystore (ESO-projected)' },
    { id: 'audit',           x: 560, y: 782, w: 200, h: 64, color: '#64748b', titleCs: 'Audit hash-chain', titleEn: 'Audit hash-chain', subCs: 'ADR-0133 nepopiratelnost', subEn: 'ADR-0133 non-repudiation' },
    { id: 'kafka',           x: 250, y: 782, w: 280, h: 64, color: '#f59e0b', titleCs: 'Kafka událost', titleEn: 'Kafka event', subCs: 'DOCUMENT_SIGNED · CEREMONY_COMPLETED', subEn: 'DOCUMENT_SIGNED · CEREMONY_COMPLETED' },
    { id: 'lend',            x: 250, y: 876, w: 280, h: 64, color: '#16a34a', titleCs: 'Lending / založení účtu', titleEn: 'Lending / account-opening', subCs: 'reagují asynchronně, nikdy neblokují', subEn: 'react, non-blocking (never a money-path gate)' },
  ]
  const byId = Object.fromEntries(nodes.map(n => [n.id, n]))
  const cx = (n: Node) => n.x + n.w / 2
  const bottom = (n: Node) => ({ x: cx(n), y: n.y + n.h })
  const top = (n: Node) => ({ x: cx(n), y: n.y })
  const left = (n: Node) => ({ x: n.x, y: n.y + n.h / 2 })
  const right = (n: Node) => ({ x: n.x + n.w, y: n.y + n.h / 2 })

  return (
    <div style={{ position: 'relative', width: '100%', overflowX: 'auto' }}>
      <div style={{ position: 'relative', width: W, height: H, margin: '0 auto' }}>
        <svg viewBox={`0 0 ${W} ${H}`} width={W} height={H} style={{ position: 'absolute', top: 0, left: 0, pointerEvents: 'none' }}>
          <title>{t('Diagram toku správy dokumentů', 'Document management flow diagram')}</title>
          <defs>
            <marker id="dm-ah" markerWidth="9" markerHeight="9" refX="6.5" refY="3" orient="auto">
              <path d="M0,0 L0,6 L7,3 z" fill="context-stroke" />
            </marker>
          </defs>
          {/* main spine */}
          <Arrow a={bottom(byId.editor)} b={top(byId.registry)} color={ACCENT} label={t('BFF · ADR-0056', 'BFF · ADR-0056')} />
          <Arrow a={bottom(byId.registry)} b={top(byId.render)} color={ACCENT} />
          <Arrow a={right(byId.data)} b={left(byId.render)} color="#94a3b8" label={t('sloučit pole', 'merge fields')} />
          {/* Onboarding is the second (event-driven) trigger into render, alongside the
              admin-ui/API-driven path above — an elbowed connector, ADR-0162 D7. */}
          <path
            d={`M ${bottom(byId.onboarding).x} ${bottom(byId.onboarding).y} L ${bottom(byId.onboarding).x} 230 L ${left(byId.render).x} 230`}
            fill="none" stroke="#16a34a" strokeWidth={2} strokeDasharray="5 4" markerEnd="url(#dm-ah)"
          />
          <text x={bottom(byId.onboarding).x + 4} y={188} fontSize={11} fill="#16a34a">
            {t('vyvolá render', 'triggers render')}
          </text>
          <Arrow a={bottom(byId.render)} b={top(byId.pdf)} color="#0284c7" />
          <Arrow a={right(byId.pdf)} b={left(byId.store)} color="#64748b" label="ObjectStorePort" />
          <Arrow a={bottom(byId.pdf)} b={top(byId.ceremony)} color="#7c3aed" />
          <Arrow a={bottom(byId.ceremony)} b={top(byId.signer)} color="#7c3aed" label={t('vazba SCA', 'SCA bind')} />
          <Arrow a={bottom(byId.signer)} b={top(byId.client_signature)} color="#059669" label={t('SIGNED → ihned', 'SIGNED → immediately')} />
          <Arrow a={left(byId.openbao_pki)} b={right(byId.client_signature)} color="#64748b" label={t('vydá cert', 'issues cert')} />
          <Arrow a={bottom(byId.client_signature)} b={top(byId.bank_seal)} color="#059669" label={t('poslední signatář → pečeť', 'last signer → seal')} />
          <Arrow a={left(byId.openbao_kv)} b={right(byId.bank_seal)} color="#64748b" label={t('keystore', 'keystore')} />
          <Arrow a={right(byId.bank_seal)} b={left(byId.audit)} color="#64748b" label={t('hash-chain', 'hash-chain')} />
          {/* Kafka event fires off the ceremony itself once complete, not off the
              seal detail — an elbowed connector on the left routes around
              signer/seal to stay faithful to the ADR-0162 mermaid edge
              `CE -->|event DOCUMENT_SIGNED| K`. */}
          <path
            d={`M ${left(byId.ceremony).x} ${left(byId.ceremony).y} L 120 ${left(byId.ceremony).y} L 120 ${left(byId.kafka).y} L ${left(byId.kafka).x} ${left(byId.kafka).y}`}
            fill="none" stroke="#f59e0b" strokeWidth={2} strokeDasharray="5 4" markerEnd="url(#dm-ah)"
          />
          <text x={124} y={(left(byId.ceremony).y + left(byId.kafka).y) / 2 - 6} fontSize={11} fill="#b45309">
            {t('událost po dokončení, neblokující', 'event on completion, non-blocking')}
          </text>
          <Arrow a={bottom(byId.kafka)} b={top(byId.lend)} color="#16a34a" label={t('konzumuje', 'consumed by')} />
        </svg>
        {nodes.map(n => (
          <div key={n.id} style={{
            position: 'absolute', left: n.x, top: n.y, width: n.w, height: n.h,
            borderRadius: 10, background: 'var(--surface-1)', border: '1px solid var(--border)',
            borderLeft: `4px solid ${n.color}`, padding: '8px 12px', display: 'flex', flexDirection: 'column',
            justifyContent: 'center', boxShadow: '0 1px 3px rgba(0,0,0,0.06)',
          }}>
            <div style={{ fontSize: 12.5, fontWeight: 700, color: INK }}>{t(n.titleCs, n.titleEn)}</div>
            <div style={{ fontSize: 10.5, color: SUB, marginTop: 2, lineHeight: 1.35 }}>{t(n.subCs, n.subEn)}</div>
          </div>
        ))}
      </div>
    </div>
  )
}

function Arrow({ a, b, color, label }: { a: { x: number; y: number }; b: { x: number; y: number }; color: string; label?: string }) {
  return (
    <g>
      <line x1={a.x} y1={a.y} x2={b.x} y2={b.y} stroke={color} strokeWidth={2} markerEnd="url(#dm-ah)" />
      {label && (
        <text x={(a.x + b.x) / 2 + (a.x === b.x ? 8 : 0)} y={(a.y + b.y) / 2 - 4} fontSize={11} fill={color} textAnchor={a.x === b.x ? 'start' : 'middle'}>
          {label}
        </text>
      )}
    </g>
  )
}

// ── small UI helpers (same house style as docs/qrlesspay/page.tsx) ─────────────
function Section({ title, subtitle, children }: { title: string; subtitle?: string; children: React.ReactNode }) {
  return (
    <div className="card" style={{ padding: 20, marginBottom: 16 }}>
      <h2 style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)', margin: 0 }}>{title}</h2>
      {subtitle && <p style={{ fontSize: 12.5, color: 'var(--text-secondary)', margin: '4px 0 14px' }}>{subtitle}</p>}
      {!subtitle && <div style={{ height: 12 }} />}
      {children}
    </div>
  )
}

function Pill({ color, bg, border, Icon, label }: { color: string; bg: string; border: string; Icon: React.ElementType; label: string }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 11.5, fontWeight: 700, color, background: bg, border: `1px solid ${border}`, padding: '3px 10px', borderRadius: 20 }}>
      <Icon size={12} /> {label}
    </span>
  )
}

const th: React.CSSProperties = { padding: '10px 14px', fontWeight: 700, fontSize: 12 }
const td: React.CSSProperties = { padding: '10px 14px', verticalAlign: 'top' }
const linkBtn: React.CSSProperties = { fontSize: 12.5, fontWeight: 600, color: ACCENT, background: 'var(--accent-bg)', border: '1px solid var(--accent-border)', padding: '6px 12px', borderRadius: 8, textDecoration: 'none', display: 'inline-flex', alignItems: 'center' }
