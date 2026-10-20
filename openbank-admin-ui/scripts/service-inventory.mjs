#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
// Shared source of runnable Quarkus application modules for catalog and readiness collectors.
// Keep this detector structural: governance, naming, release registration and deployment are
// evidence about an application, not conditions for including it in the inventory.

import { readdirSync, readFileSync, statSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export function runnableModules(repo) {
  const modules = readdirSync(repo).filter(name => {
    if (!name.startsWith('openbank-')) return false
    const dir = path.join(repo, name)
    try { if (!statSync(dir).isDirectory() || !statSync(path.join(dir, 'src', 'main')).isDirectory()) return false } catch { return false }
    let build
    try { build = readFileSync(path.join(dir, 'build.gradle.kts'), 'utf8') } catch { return false }
    return (/^\s*(?:plugins\s*\{\s*)?id\(["']openbank\.quarkus-service["']\)/m.test(build)
      && (build.includes('project(":openbank-libs-runtime")') || build.includes('project(":openbank-libs")')))
      || /^\s*(?:plugins\s*\{\s*)?alias\(libs\.plugins\.quarkus\)/m.test(build)
  }).sort().map(name => ({
    name,
    short: name.replace(/^openbank-/, '').replace(/-service$/, ''),
  }))
  const seenNames = new Set()
  const seenShorts = new Map()
  for (const entry of modules) {
    if (seenNames.has(entry.name)) throw new Error(`duplicate runnable module name: ${entry.name}`)
    seenNames.add(entry.name)
    const prior = seenShorts.get(entry.short)
    if (prior) {
      throw new Error(`runnable module short-name collision '${entry.short}': ${prior} and ${entry.name}`)
    }
    seenShorts.set(entry.short, entry.name)
  }
  return modules
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2)
  const repoFlag = args.indexOf('--repo')
  const repo = repoFlag >= 0 && args[repoFlag + 1]
    ? path.resolve(args[repoFlag + 1])
    : path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
  try {
    process.stdout.write(`${JSON.stringify(runnableModules(repo))}\n`)
  } catch (error) {
    console.error(`[service-inventory] cannot read runnable module inventory from ${repo}: ${error.message}`)
    process.exitCode = 1
  }
}
