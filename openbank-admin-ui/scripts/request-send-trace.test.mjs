// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { channel } from 'node:diagnostics_channel'
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import http from 'node:http'
import net from 'node:net'
import os from 'node:os'
import { dirname, join, resolve } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { classifyOrigin, createRequestSendTrace, withRequestSendTrace } from './request-send-trace.mjs'

const scriptDir = dirname(fileURLToPath(import.meta.url))
const ipcScript = resolve(scriptDir, '../../.github/scripts/admin_ui_trace_ipc.py')

function waitForExit(child, timeoutMs) {
  if (child.exitCode !== null || child.signalCode !== null) return Promise.resolve(true)
  return new Promise((resolve) => {
    const onExit = () => {
      clearTimeout(timer)
      resolve(true)
    }
    const timer = setTimeout(() => {
      child.off('exit', onExit)
      resolve(false)
    }, timeoutMs)
    child.once('exit', onExit)
  })
}

async function stopSupervisor(child) {
  if (child.exitCode !== null || child.signalCode !== null) return
  child.kill('SIGTERM')
  if (!(await waitForExit(child, 2000))) {
    child.kill('SIGKILL')
    assert.equal(await waitForExit(child, 2000), true, 'trace supervisor should stop after SIGKILL')
  }
}

async function startSupervisor(testContext, traceDir, stage = 'gate_health', auth = 'github') {
  const env = { ...process.env, ADMIN_UI_TRACE_DIR: traceDir }
  const child = spawn('python3', [ipcScript, 'serve', '--stage', stage, '--auth', auth], {
    env, detached: true, stdio: 'ignore',
  })
  env.ADMIN_UI_TRACE_PID = String(child.pid)
  process.env.ADMIN_UI_TRACE_PID = String(child.pid)
  child.unref()
  testContext.after(() => stopSupervisor(child))
  const deadline = Date.now() + 2000
  while (Date.now() < deadline) {
    if (existsSync(join(traceDir, `${stage}.sock`)) && existsSync(join(traceDir, `${stage}.pid`))) {
      return { child, env }
    }
    await new Promise((resolve) => setTimeout(resolve, 10))
  }
  throw new Error('trace supervisor did not become ready')
}

function finishSupervisor(traceDir, env, stage = 'gate_health', auth = 'github') {
  const result = spawnSync('python3', [ipcScript, 'finish', '--stage', stage, '--auth', auth], {
    env, encoding: 'utf8', timeout: 6000,
  })
  assert.ok([0, 2].includes(result.status), result.stderr)
  const report = JSON.parse(readFileSync(join(traceDir, `admin-ui-stage-${stage}.json`), 'utf8'))
  rmSync(traceDir, { recursive: true, force: true })
  return report
}

test('Undici header-send observer matches loopback oracle without retaining request data', async (testContext) => {
  assert.equal(classifyOrigin('https://api.github.com'), 'api')
  assert.equal(classifyOrigin('http://127.0.0.1:1234'), 'non_api')
  assert.equal(classifyOrigin(undefined), 'unknown')

  const oracle = []
  const server = http.createServer((req, res) => {
    oracle.push({ method: req.method, path: req.url })
    const pathname = new URL(req.url, 'http://127.0.0.1').pathname
    if (pathname === '/redirect-a') {
      res.writeHead(302, { Location: `http://127.0.0.1:${server.address().port}/redirect-b` })
      res.end()
    } else if (pathname === '/redirect-b') {
      res.writeHead(302, { Location: `http://localhost:${server.address().port}/redirect-final` })
      res.end()
    } else if (pathname === '/not-modified') {
      res.writeHead(304, { 'X-Sentinel': 'TRACE_SENTINEL_HEADER_2b9f' })
      res.end()
    } else {
      res.writeHead(200, { 'Content-Type': 'text/plain', 'X-Sentinel': 'TRACE_SENTINEL_HEADER_2b9f' })
      res.end('TRACE_SENTINEL_BODY_5c23')
    }
  })
  await new Promise((resolve) => server.listen(0, '::', resolve))
  testContext.after(() => new Promise((resolve) => server.close(resolve)))
  const port = server.address().port
  const traceDir = mkdtempSync(join(os.tmpdir(), 'admin-ui-trace-test-'))
  testContext.after(() => rmSync(traceDir, { recursive: true, force: true }))
  const supervisor = await startSupervisor(testContext, traceDir)
  const trace = createRequestSendTrace('gate_health', 'github', traceDir)
  assert.equal(await trace.ready(), true)
  const base = trace.snapshot()
  const secret = 'TRACE_SENTINEL_AUTH_8f4e'
  const query = 'TRACE_SENTINEL_QUERY_71ad'

  try {
    const redirect = await fetch(`http://127.0.0.1:${port}/redirect-a?marker=${query}`, {
      headers: { Authorization: `Bearer ${secret}` },
      redirect: 'follow',
    })
    await redirect.arrayBuffer()
    const redirectCounts = trace.snapshot().counts
    const redirectDelta = [...redirectCounts.entries()].reduce(
      (sum, [kind, count]) => sum + count - (base.counts.get(kind) || 0), 0,
    )
    assert.equal(redirectDelta, 3)
    assert.equal(oracle.length, 3)

    const before304 = trace.snapshot().counts
    const notModified = await fetch(`http://127.0.0.1:${port}/not-modified`)
    assert.equal(notModified.status, 304)
    const after304 = trace.snapshot().counts
    assert.equal([...after304.values()].reduce((a, b) => a + b, 0) - [...before304.values()].reduce((a, b) => a + b, 0), 1)

    // A quiet gap between real requests must not expire the IPC registration socket.
    await new Promise((resolve) => setTimeout(resolve, 2100))
    assert.equal(trace.snapshot().complete, true)

    const beforeKeepAlive = trace.snapshot().counts
    await fetch(`http://127.0.0.1:${port}/keep-a`)
    await fetch(`http://127.0.0.1:${port}/keep-b`)
    const afterKeepAlive = trace.snapshot().counts
    assert.equal([...afterKeepAlive.values()].reduce((a, b) => a + b, 0) - [...beforeKeepAlive.values()].reduce((a, b) => a + b, 0), 2)
    assert.equal(oracle.length, 6)
    assert.equal(trace.snapshot().complete, true)

    const capturedTrace = JSON.stringify([...trace.snapshot().counts.entries()])
    for (const sentinel of [secret, query, 'TRACE_SENTINEL_BODY_5c23', 'TRACE_SENTINEL_HEADER_2b9f']) {
      assert.equal(capturedTrace.includes(sentinel), false)
    }

    const closed = net.createServer()
    await new Promise((resolve) => closed.listen(0, '127.0.0.1', resolve))
    const closedPort = closed.address().port
    await new Promise((resolve) => closed.close(resolve))
    await assert.rejects(fetch(`http://127.0.0.1:${closedPort}/unreachable`))
    channel('undici:client:sendHeaders').publish({ request: { origin: 'not a URL' } })
    assert.equal(trace.snapshot().complete, false)
    assert.equal(await trace.write({ configured: true }), true)
    const report = finishSupervisor(traceDir, supervisor.env)
    assert.equal(report.complete, false)
    assert.ok(report.records.some((row) => row.numericSendCount > 0))
  } finally {
    await new Promise((resolve) => server.close(resolve))
    if (existsSync(traceDir)) {
      try { finishSupervisor(traceDir, supervisor.env) } catch {}
    }
  }
})

test('collector wrappers count retry sends for both new stages and preserve collector failures', async (testContext) => {
  const secret = 'TRACE_SENTINEL_AUTH_8f4e'
  let retryCount = 0
  const server = http.createServer((_req, res) => {
    retryCount += 1
    res.writeHead(retryCount < 3 ? 503 : 200, { 'X-Sentinel': secret })
    res.end(secret)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  testContext.after(() => new Promise((resolve) => server.close(resolve)))
  const url = `http://127.0.0.1:${server.address().port}/retry?secret=${secret}`
  const traceDir = mkdtempSync(join(os.tmpdir(), 'admin-ui-collector-trace-'))
  testContext.after(() => rmSync(traceDir, { recursive: true, force: true }))
  process.env.ADMIN_UI_TRACE_DIR = traceDir

  let supervisor = await startSupervisor(testContext, traceDir, 'quality_report', 'pact_broker')
  const ok = await withRequestSendTrace('quality_report', 'pact_broker', true, async () => {
    for (let attempt = 0; attempt < 3; attempt += 1) await fetch(url)
    return true
  })
  assert.equal(ok, true)
  const quality = finishSupervisor(traceDir, supervisor.env, 'quality_report', 'pact_broker')
  assert.equal(quality.complete, true)
  assert.equal(quality.authAlias, 'pact_broker')
  assert.equal(quality.records.reduce((sum, row) => sum + row.numericSendCount, 0), 3)

  supervisor = await startSupervisor(testContext, traceDir, 'infra_lifecycle', 'none')
  await assert.rejects(
    withRequestSendTrace('infra_lifecycle', 'none', true, async () => {
      await fetch(url)
      throw new Error('collector-failure-sentinel')
    }),
    /collector-failure-sentinel/,
  )
  const lifecycle = finishSupervisor(traceDir, supervisor.env, 'infra_lifecycle', 'none')
  assert.equal(lifecycle.complete, false)
  assert.equal(lifecycle.reasonEnum, 'collector_failed')
  assert.equal(lifecycle.records.reduce((sum, row) => sum + row.numericSendCount, 0), 1)
  const receipts = JSON.stringify([quality, lifecycle])
  assert.equal(receipts.includes(secret), false)
  delete process.env.ADMIN_UI_TRACE_DIR
})

test('unconfigured collector bypasses trace IPC and taint signaling', async () => {
  const traceDir = mkdtempSync(join(os.tmpdir(), 'admin-ui-unconfigured-trace-'))
  const oldDir = process.env.ADMIN_UI_TRACE_DIR
  const oldPid = process.env.ADMIN_UI_TRACE_PID
  const originalKill = process.kill
  let signalCalls = 0
  process.env.ADMIN_UI_TRACE_DIR = traceDir
  process.env.ADMIN_UI_TRACE_PID = '424242'
  process.kill = (...args) => {
    if (args[1] === 'SIGUSR1') signalCalls += 1
    return true
  }
  try {
    let ran = false
    const result = await withRequestSendTrace('quality_report', 'none', false, async () => {
      ran = true
      return 'unchanged-result'
    })
    assert.equal(ran, true)
    assert.equal(result, 'unchanged-result')
    assert.equal(signalCalls, 0)
  } finally {
    process.kill = originalKill
    if (oldDir === undefined) delete process.env.ADMIN_UI_TRACE_DIR
    else process.env.ADMIN_UI_TRACE_DIR = oldDir
    if (oldPid === undefined) delete process.env.ADMIN_UI_TRACE_PID
    else process.env.ADMIN_UI_TRACE_PID = oldPid
    rmSync(traceDir, { recursive: true, force: true })
  }
})

test('gate-health trace reflects collector success, later failure, and missing token', async (testContext) => {
  const collector = resolve(scriptDir, 'collect-gate-health.mjs')

  async function runCollectorScenario({ token, failAfterFirstRequest }) {
    const traceDir = mkdtempSync(join(os.tmpdir(), 'admin-ui-gate-health-trace-'))
    testContext.after(() => rmSync(traceDir, { recursive: true, force: true }))
    process.env.ADMIN_UI_TRACE_DIR = traceDir
    const supervisor = await startSupervisor(testContext, traceDir, 'gate_health', 'github')
    const preload = join(traceDir, 'fetch-fixture.mjs')
    const reportPath = join(traceDir, 'gate-health.json')
    writeFileSync(preload, `
      import { channel } from 'node:diagnostics_channel'
      let calls = 0
      globalThis.fetch = async () => {
        calls += 1
        if (calls === 1) {
          channel('undici:client:sendHeaders').publish({
            request: { origin: 'https://api.github.com' },
          })
          const workflowRuns = ${failAfterFirstRequest
            ? "[{ id: 123, head_sha: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', event: 'workflow_dispatch', created_at: '2026-10-17T00:00:00Z' }]"
            : '[]'}
          return new Response(JSON.stringify({ workflow_runs: workflowRuns }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        throw new Error('fixture later GitHub API failure')
      }
    `)
    const childEnv = {
      ...process.env,
      ADMIN_UI_TRACE_DIR: traceDir,
      ADMIN_UI_TRACE_PID: String(supervisor.child.pid),
      GITHUB_REPOSITORY: 'JiRaska/open-bank-oss',
      GITHUB_RUN_ID: '123',
      GITHUB_RUN_ATTEMPT: '1',
    }
    if (token) childEnv.GITHUB_TOKEN = token
    else delete childEnv.GITHUB_TOKEN
    const child = spawnSync(
      'node', ['--import', preload, collector, '--out', reportPath],
      { env: childEnv, encoding: 'utf8', timeout: 10000 },
    )
    assert.equal(child.status, 0, child.stderr)
    const collectorReport = JSON.parse(readFileSync(reportPath, 'utf8'))
    const stageReport = finishSupervisor(traceDir, supervisor.env)
    return { collectorReport, stageReport }
  }

  const successful = await runCollectorScenario({ token: 'fixture-token', failAfterFirstRequest: false })
  assert.equal(successful.collectorReport.available, true)
  assert.equal(successful.stageReport.complete, true)
  assert.equal(successful.stageReport.reasonEnum, null)
  assert.equal(successful.stageReport.records.reduce((sum, row) => sum + row.numericSendCount, 0), 1)

  const failed = await runCollectorScenario({ token: 'fixture-token', failAfterFirstRequest: true })
  assert.equal(failed.collectorReport.available, false)
  assert.equal(failed.stageReport.complete, false)
  assert.equal(failed.stageReport.reasonEnum, 'collector_failed')
  assert.equal(failed.stageReport.records.reduce((sum, row) => sum + row.numericSendCount, 0), 1)

  const unconfigured = await runCollectorScenario({ token: '', failAfterFirstRequest: false })
  assert.equal(unconfigured.collectorReport.available, false)
  assert.equal(unconfigured.stageReport.complete, false)
  assert.equal(unconfigured.stageReport.reasonEnum, 'collector_not_configured')
  assert.equal(unconfigured.stageReport.records.reduce((sum, row) => sum + row.numericSendCount, 0), 0)
})
