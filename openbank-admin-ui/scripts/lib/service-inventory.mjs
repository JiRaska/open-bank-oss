// SPDX-License-Identifier: Apache-2.0

import fs from 'fs'
import path from 'path'
import { parse } from 'yaml'

/**
 * Released JVM services are the authoritative test inventory. Deriving this from
 * release-please removes the three hand-maintained service lists that drifted as
 * the fleet grew. Non-JVM components do not have Gradle/JUnit/Kover evidence and
 * therefore do not belong on the service-test page.
 */
export function releasedJvmServices(repoRoot) {
  const config = JSON.parse(fs.readFileSync(path.join(repoRoot, 'release-please-config.json'), 'utf8'))
  return Object.keys(config.packages ?? {})
    .filter(component => component.startsWith('openbank-'))
    .filter(component => fs.existsSync(path.join(repoRoot, component, 'build.gradle.kts')))
    .filter(component => fs.existsSync(path.join(repoRoot, component, 'src', 'test')))
    .sort()
}

/** rules.yaml owns the money-path classification; the UI must never copy it. */
export function moneyPathServices(repoRoot) {
  const yaml = fs.readFileSync(path.join(repoRoot, 'openbank-libs', 'governance', 'rules.yaml'), 'utf8')
  const values = parse(yaml)?.money_path_services
  if (!Array.isArray(values) || values.some(value => typeof value !== 'string')) {
    throw new Error('rules.yaml money_path_services must be a string list')
  }
  return values
}
