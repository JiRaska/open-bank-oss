// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Derive the zero-trust security posture from the REAL platform manifests, so
// the /docs/zero-trust map is governance-as-code (ADR-0029) — never a hand-drawn
// claim. Emits security-graph.json from two source-of-truth trees:
//   - gitops/components/*/network-policies.yaml (+ temporal's)  → L3/L4 ingress
//     coverage, per real GitOps-derived NetworkPolicies (gen-network-policies.py)
//   - gitops/components/kyverno/verify-images-policy.yaml       → admission / supply chain
//
// Honest by construction: every status flag below is read from a manifest that
// is actually wired into ArgoCD (no ArgoCD Application or Terraform resource
// references openbank-infra/k8s/base/{istio,network-policies}.yaml — that tree
// is a standalone kind/minikube-only playground, k8s/README.md, and ADR-0098
// states outright that no service mesh runs here). A file that cannot be parsed
// degrades that layer to status:"unknown"/available:false — never a fabricated
// "enforced". istio.* is unconditionally `available: false`: there is nothing
// live to derive it from, so a static parse of an unwired manifest is not an
// honest substitute (#1666, #1667, #1710). If a mesh is ever actually installed,
// this should probe live Istio CRDs at deploy time, not a repo-parsed manifest.
//
// Usage: node scripts/generate-security-graph.mjs [--repo <path>] [--out <file>]

import { readFileSync, writeFileSync, readdirSync } from 'fs'
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
const COMPONENTS_DIR = path.join(INFRA, 'gitops', 'components')
const KYVERNO_FILE = path.join(COMPONENTS_DIR, 'kyverno', 'verify-images-policy.yaml')

// Kinds gen-network-policies.py's callers actually run pods under (ADR-0098
// migrated ten money-path services from Deployment to Rollout; both remain).
const WORKLOAD_KINDS = new Set(['Deployment', 'Rollout', 'StatefulSet'])

function parseYamlFile(file) {
  try {
    return parseAllDocuments(readFileSync(file, 'utf-8')).map(d => d.toJSON()).filter(Boolean)
  } catch {
    return []
  }
}

function listComponentDirs() {
  try {
    return readdirSync(COMPONENTS_DIR, { withFileTypes: true })
      .filter(e => e.isDirectory())
      .map(e => e.name)
      .sort()
  } catch {
    return []
  }
}

const appName = d =>
  d?.spec?.selector?.matchLabels?.['app.kubernetes.io/name'] ??
  d?.metadata?.labels?.['app.kubernetes.io/name']

// ── NetworkPolicy: real per-namespace ingress coverage + egress posture ─────
function deriveNetwork() {
  const dirs = listComponentDirs()
  if (dirs.length === 0) return { available: false }

  const gaps = []
  const egress = []
  const ingress = []
  let totalWorkloads = 0
  const egressRestrictedApps = new Set()
  const optInLabeledApps = new Set()

  for (const ns of dirs) {
    const dirPath = path.join(COMPONENTS_DIR, ns)
    let files
    try {
      files = readdirSync(dirPath).filter(f => f.endsWith('.yaml') && f !== 'kustomization.yaml')
    } catch {
      continue
    }
    const docs = files.flatMap(f => parseYamlFile(path.join(dirPath, f)))

    const workloadNames = new Set(
      docs.filter(d => WORKLOAD_KINDS.has(d?.kind)).map(appName).filter(Boolean),
    )
    if (workloadNames.size === 0) continue
    totalWorkloads += workloadNames.size

    const netpols = docs.filter(d => d?.kind === 'NetworkPolicy')
    const coveredNames = new Set(
      netpols.map(p => p?.spec?.podSelector?.matchLabels?.['app.kubernetes.io/name']).filter(Boolean),
    )
    for (const name of workloadNames) {
      if (!coveredNames.has(name)) gaps.push({ namespace: ns, service: name })
    }

    for (const p of netpols) {
      const selectorName = p?.spec?.podSelector?.matchLabels?.['app.kubernetes.io/name']
      if (selectorName && (p?.spec?.policyTypes ?? []).includes('Egress')) {
        egressRestrictedApps.add(selectorName)
      }
      for (const rule of p?.spec?.egress ?? []) {
        const ports = (rule.ports ?? []).map(pt => pt.port).filter(Boolean)
        for (const to of rule.to ?? []) {
          const sel = to.podSelector?.matchLabels ?? to.namespaceSelector?.matchLabels
          const ipBlock = to.ipBlock?.cidr
          if (ipBlock) { egress.push({ target: `internet:${ipBlock}`, ports }); continue }
          if (!sel) continue
          const label = Object.values(sel).join('/')
          if (label.includes('kube-system')) continue
          egress.push({ target: label, ports })
        }
      }
      for (const rule of p?.spec?.ingress ?? []) {
        const ports = (rule.ports ?? []).map(pt => pt.port).filter(Boolean)
        ingress.push({ policy: p.metadata?.name ?? null, namespace: ns, ports })
      }
    }
    // openbank.io/allow-internet-egress is a pod-template label (the Deployment/
    // Rollout side of the opt-in), not a NetworkPolicy selector — track which
    // apps actually carry it, then check below whether it's backed by a real
    // egress-restricting policy for that SAME app (not just present somewhere).
    for (const d of docs) {
      if (!WORKLOAD_KINDS.has(d?.kind)) continue
      if (d?.spec?.template?.metadata?.labels?.['openbank.io/allow-internet-egress'] === 'true') {
        const name = appName(d)
        if (name) optInLabeledApps.add(name)
      }
    }
  }

  // Honest: the opt-in label only means something if the labeled app's own
  // egress is actually restricted by a matching policy. Today it isn't (e.g.
  // notification-service carries the label with no egress-restricting policy
  // of its own) — a handful of unrelated hardened exceptions exist elsewhere
  // (e.g. the RUM gateway, ADR-0088 D4b) but they don't implement this label's
  // pattern, so the fleet-wide posture is genuinely "open", not "opt-in".
  const optInIsReal = optInLabeledApps.size > 0 &&
    [...optInLabeledApps].every(name => egressRestrictedApps.has(name))

  return {
    available: true,
    defaultDeny: gaps.length === 0,
    coverage: { total: totalWorkloads, covered: totalWorkloads - gaps.length, gaps },
    egressTargets: egress,
    ingressRules: ingress,
    internetEgress: optInIsReal ? 'opt-in' : 'open',
    // Apps with a real egress-restricting NetworkPolicy today, whether or not
    // they carry the opt-in label (e.g. the RUM gateway's bespoke lockdown).
    egressRestrictedApps: [...egressRestrictedApps].sort(),
  }
}

// ── Kyverno: admission / supply-chain verification ──────────────────────────
function deriveSupplyChain() {
  const docs = parseYamlFile(KYVERNO_FILE)
  if (docs.length === 0) return { available: false }
  const policy = docs.find(d => d?.kind === 'ClusterPolicy')
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

// No service mesh runs in the sandbox — see the module comment. Nothing to
// derive; a static parse of the unwired k8s/base/istio.yaml manifest is not a
// live signal (that is exactly the bug #1666/#1667/#1710 fixed/track).
const istio = {
  available: false,
  deployed: false,
  note: 'No service mesh is deployed to the sandbox (ADR-0098). ' +
    'openbank-infra/k8s/base/istio.yaml exists but is wired into neither ArgoCD nor Terraform.',
}
const network = deriveNetwork()
const supplyChain = deriveSupplyChain()

const out = {
  schema: 'openbank.security-posture/v1',
  source: 'derived from openbank-infra: gitops/components/*/network-policies.yaml, ' +
    'gitops/components/kyverno/verify-images-policy.yaml (istio: no mesh deployed, see note)',
  collectedAt: new Date().toISOString(),
  istio,
  network,
  supplyChain,
  available: Boolean(network.available || supplyChain.available),
}

writeFileSync(OUT, JSON.stringify(out, null, 2) + '\n')
console.log(`── security posture →  ${path.relative(REPO, OUT)}`)
console.log(`   Istio: not deployed (${istio.note})`)
console.log(`   NetPol ingress coverage: ${network.coverage?.covered ?? '?'}/${network.coverage?.total ?? '?'} ` +
  `(default-deny: ${network.defaultDeny ?? '?'}) | egress: ${network.internetEgress ?? '?'}`)
if (network.coverage?.gaps?.length) {
  console.log(`   Gaps: ${network.coverage.gaps.map(g => `${g.namespace}/${g.service}`).join(', ')}`)
}
console.log(`   Kyverno: ${supplyChain.policy ?? 'none'} mode=${supplyChain.mode ?? '?'} enforced=${supplyChain.enforced ?? '?'}`)
