// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
// Pure derivation of the platform versions the admin UI displays (EKS minor, bootstrap node
// group shape, Loki chart + pinned app version, EKS support lifecycle). Every value is PARSED
// from the file that decides it; a value that cannot be parsed THROWS. There is deliberately no
// default anywhere in this file: a guessed version is exactly the defect this replaces.
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { parse } from 'yaml'

export const SOURCES = {
  variables: 'openbank-infra/aws/envs/sandbox-substrate/variables.tf',
  main: 'openbank-infra/aws/envs/sandbox-substrate/main.tf',
  loki: 'openbank-infra/gitops/apps/loki.yaml',
  lifecycle: 'openbank-infra/aws/finops/eks-version-lifecycle.json',
}

const strip = text => text.replace(/^\s*(#|\/\/).*$/gm, '')

function only(text, re, what, file) {
  const matches = [...strip(text).matchAll(re)]
  if (matches.length !== 1) {
    throw new Error(`${file}: expected exactly one ${what}, found ${matches.length}`)
  }
  return matches[0]
}

export function parseKubernetesVersion(variablesTf) {
  const m = only(
    variablesTf,
    /variable\s+"kubernetes_version"\s*\{[^}]*?default\s*=\s*"(\d+\.\d+)"/g,
    'kubernetes_version default',
    SOURCES.variables,
  )
  return m[1]
}

export function parseNodeGroup(mainTf) {
  const types = only(mainTf, /node_instance_types\s*=\s*\[\s*"([a-z0-9.]+)"\s*\]/g, 'node_instance_types', SOURCES.main)[1]
  const int = key => Number(only(mainTf, new RegExp(`${key}\\s*=\\s*(\\d+)`, 'g'), key, SOURCES.main)[1])
  return {
    instanceType: types,
    desiredSize: int('node_desired_size'),
    minSize: int('node_min_size'),
    maxSize: int('node_max_size'),
  }
}

export function parseLoki(lokiYaml) {
  const app = parse(lokiYaml)
  const src = app?.spec?.source
  const chartVersion = src?.targetRevision
  const appVersion = src?.helm?.valuesObject?.loki?.image?.tag
  if (src?.chart !== 'loki' || !/^\d+\.\d+\.\d+$/.test(String(chartVersion ?? ''))) {
    throw new Error(`${SOURCES.loki}: spec.source.targetRevision is not a loki chart semver`)
  }
  if (!/^\d+\.\d+\.\d+$/.test(String(appVersion ?? ''))) {
    throw new Error(`${SOURCES.loki}: loki.image.tag (pinned app version) is not a semver`)
  }
  return { chartVersion: String(chartVersion), appVersion: String(appVersion) }
}

export function parseLifecycle(json, kubernetesVersion) {
  const lifecycle = JSON.parse(json)
  if (!lifecycle?.versions || !lifecycle.versions[kubernetesVersion]) {
    throw new Error(`${SOURCES.lifecycle}: no lifecycle row for EKS ${kubernetesVersion}`)
  }
  if (!lifecycle._meta?.last_refreshed) throw new Error(`${SOURCES.lifecycle}: _meta.last_refreshed missing`)
  return lifecycle
}

export function derivePlatformVersions(repoRoot) {
  const read = rel => readFileSync(path.join(repoRoot, rel), 'utf8')
  const kubernetesVersion = parseKubernetesVersion(read(SOURCES.variables))
  const nodeGroup = parseNodeGroup(read(SOURCES.main))
  const loki = parseLoki(read(SOURCES.loki))
  const lifecycle = parseLifecycle(read(SOURCES.lifecycle), kubernetesVersion)
  return {
    schema: 'openbank.platform-versions/v1',
    kubernetesVersion,
    nodeGroup,
    loki,
    eksLifecycle: lifecycle,
    sources: {
      kubernetesVersion: SOURCES.variables,
      nodeGroup: SOURCES.main,
      loki: SOURCES.loki,
      eksLifecycle: SOURCES.lifecycle,
    },
  }
}
