// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Derive the zero-trust security posture from the REAL platform manifests, so
// the /docs/zero-trust map is governance-as-code (ADR-0029) — never a hand-drawn
// claim. Parses three source-of-truth files and emits security-graph.json:
//   - k8s/base/istio.yaml                  → mTLS mode, JWT rule, L7 authz
//   - k8s/base/network-policies.yaml       → L3/L4 default-deny + allow-list
//   - gitops/components/kyverno/verify-images-policy.yaml → admission / supply chain
//
// Honest by construction: every status flag below is read from the manifest
// (STRICT, Audit, the actual ports/issuer/audiences). The narrative copy that
// frames each layer is authored; the FACTS are derived. A file that cannot be
// parsed degrades that layer to status:"unknown" — never a fabricated "enforced".
//
// Usage: node scripts/generate-security-graph.mjs [--repo <path>] [--out <file>]

import { readFileSync, writeFileSync } from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import { parseAllDocuments } from 'yaml'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
const getArg = (flag, dflt) => {
  const i = args.indexOf(flag)
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt
}
const REPO = path.resolve(getArg('--repo', path.resolve(__dirname, '..', '..')))
const OUT = path.resolve(getArg('--out', path.resolve(__dirname, '..', 'security-graph.json')))

const INFRA = path.join(REPO, 'openbank-infra')
const ISTIO_FILE = path.join(INFRA, 'k8s', 'base', 'istio.yaml')
const NETPOL_FILE = path.join(INFRA, 'k8s', 'base', 'network-policies.yaml')
const KYVERNO_FILE = path.join(INFRA, 'gitops', 'components', 'kyverno', 'verify-images-policy.yaml')

function loadDocs(file) {
  try {
    return parseAllDocuments(readFileSync(file, 'utf-8'))
      .map(d => d.toJSON())
      .filter(Boolean)
  } catch {
    return null
  }
}

const byKind = (docs, kind) => (docs ?? []).filter(d => d?.kind === kind)

// ── 1) Istio: mTLS, JWT, L7 authorization ───────────────────────────────────
function deriveIstio() {
  const docs = loadDocs(ISTIO_FILE)
  if (!docs) return { available: false }

  const peers = byKind(docs, 'PeerAuthentication')
  const strict = peers.find(p => p?.spec?.mtls?.mode === 'STRICT')
  const exceptions = peers
    .filter(p => p?.spec?.mtls?.mode && p.spec.mtls.mode !== 'STRICT')
    .map(p => ({
      name: p.metadata?.name ?? null,
      mode: p.spec.mtls.mode,
      selector: p.spec?.selector?.matchLabels ?? null,
    }))

  const reqAuth = byKind(docs, 'RequestAuthentication')[0]
  const jwtRule = reqAuth?.spec?.jwtRules?.[0]

  const authz = byKind(docs, 'AuthorizationPolicy')[0]
  const rules = authz?.spec?.rules ?? []
  const requiresJwt = rules.some(r =>
    (r?.from ?? []).some(f => (f?.source?.requestPrincipals ?? []).length > 0))
  const allowsMeshSa = rules.some(r =>
    (r?.from ?? []).some(f => (f?.source?.principals ?? []).some(p => /\/sa\//.test(p))))

  return {
    available: true,
    mtls: {
      mode: strict ? 'STRICT' : (peers[0]?.spec?.mtls?.mode ?? 'unknown'),
      strict: Boolean(strict),
      exceptions,
    },
    jwt: jwtRule
      ? { issuer: jwtRule.issuer ?? null, audiences: jwtRule.audiences ?? [], present: true }
      : { present: false },
    l7: {
      action: authz?.spec?.action ?? null,
      // ALLOW-only with a requestPrincipals["*"] rule == default-deny for
      // anything without a valid principal (Istio drops unmatched requests).
      defaultDeny: authz?.spec?.action === 'ALLOW' && (requiresJwt || allowsMeshSa),
      requiresJwt,
      allowsMeshServiceAccounts: allowsMeshSa,
    },
  }
}

// ── 2) NetworkPolicy: L3/L4 default-deny + allow-list ───────────────────────
function deriveNetwork() {
  const docs = loadDocs(NETPOL_FILE)
  if (!docs) return { available: false }
  const pols = byKind(docs, 'NetworkPolicy')

  const defaultDeny = pols.some(p =>
    Object.keys(p?.spec?.podSelector ?? {}).length === 0 &&
    (p?.spec?.policyTypes ?? []).includes('Ingress') &&
    (p?.spec?.policyTypes ?? []).includes('Egress') &&
    !p?.spec?.ingress && !p?.spec?.egress)

  // Flatten egress allow targets into {label, ports} for a readable panel.
  const egress = []
  for (const p of pols) {
    for (const rule of p?.spec?.egress ?? []) {
      const ports = (rule.ports ?? []).map(pt => pt.port).filter(Boolean)
      for (const to of rule.to ?? []) {
        const sel = to.podSelector?.matchLabels ?? to.namespaceSelector?.matchLabels
        const ipBlock = to.ipBlock?.cidr
        if (ipBlock) { egress.push({ target: `internet:${ipBlock}`, ports }); continue }
        if (!sel) continue
        const label = Object.values(sel).join('/')
        // Skip pure DNS / kube-system noise from the readable data-plane list.
        if (label.includes('kube-system')) continue
        egress.push({ target: label, ports })
      }
    }
  }

  const internetOptIn = pols.some(p =>
    p?.spec?.podSelector?.matchLabels?.['openbank.io/allow-internet-egress'] === 'true')

  const ingress = []
  for (const p of pols) {
    for (const rule of p?.spec?.ingress ?? []) {
      const ports = (rule.ports ?? []).map(pt => pt.port).filter(Boolean)
      ingress.push({ policy: p.metadata?.name ?? null, ports })
    }
  }

  return { available: true, defaultDeny, egressTargets: egress, ingressRules: ingress, internetEgress: internetOptIn ? 'opt-in' : 'open' }
}

// ── 3) Kyverno: admission / supply-chain verification ───────────────────────
function deriveSupplyChain() {
  const docs = loadDocs(KYVERNO_FILE)
  if (!docs) return { available: false }
  const policy = byKind(docs, 'ClusterPolicy')[0]
  const rule = policy?.spec?.rules?.[0]
  const verify = rule?.verifyImages?.[0]
  const rekor = verify?.attestors?.[0]?.entries?.[0]?.keyless?.rekor?.url ?? null
  return {
    available: true,
    engine: 'Kyverno',
    policy: policy?.metadata?.name ?? null,
    mode: policy?.spec?.validationFailureAction ?? 'unknown', // "Audit" today
    imagePattern: verify?.imageReferences?.[0] ?? null,
    rekor,
    // Honest maturity framing: Audit-only until images are signed in CI.
    enforced: policy?.spec?.validationFailureAction === 'Enforce',
  }
}

const istio = deriveIstio()
const network = deriveNetwork()
const supplyChain = deriveSupplyChain()

const out = {
  schema: 'openbank.security-posture/v1',
  source: 'derived from openbank-infra: k8s/base/istio.yaml, k8s/base/network-policies.yaml, gitops/components/kyverno/verify-images-policy.yaml',
  collectedAt: new Date().toISOString(),
  istio,
  network,
  supplyChain,
  available: Boolean(istio.available || network.available),
}

writeFileSync(OUT, JSON.stringify(out, null, 2) + '\n')
console.log(`── security posture →  ${path.relative(REPO, OUT)}`)
console.log(`   mTLS: ${istio.mtls?.mode ?? '?'} (exceptions: ${istio.mtls?.exceptions?.length ?? 0}) ` +
  `| JWT: ${istio.jwt?.present ? 'yes' : 'no'} | L7 default-deny: ${istio.l7?.defaultDeny ?? '?'}`)
console.log(`   NetPol default-deny: ${network.defaultDeny ?? '?'} | egress targets: ${network.egressTargets?.length ?? 0} ` +
  `| internet: ${network.internetEgress ?? '?'}`)
console.log(`   Kyverno: ${supplyChain.policy ?? 'none'} mode=${supplyChain.mode ?? '?'} enforced=${supplyChain.enforced ?? '?'}`)
