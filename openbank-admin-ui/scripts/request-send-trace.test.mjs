// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { channel } from 'node:diagnostics_channel'
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs'
import http from 'node:http'
import net from 'node:net'
import os from 'node:os'
import { dirname, join, resolve } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { classifyOrigin, createRequestSendTrace } from './request-send-trace.mjs'

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

async function startSupervisor(testContext, traceDir) {
  const env = { ...process.env, ADMIN_UI_TRACE_DIR: traceDir }
  const child = spawn('python3', [ipcScript, 'serve', '--stage', 'gate_health'], {
    env, detached: true, stdio: 'ignore',
  })
  env.ADMIN_UI_TRACE_PID = String(child.pid)
  process.env.ADMIN_UI_TRACE_PID = String(child.pid)
  child.unref()
  testContext.after(() => stopSupervisor(child))
  const deadline = Date.now() + 2000
  while (Date.now() < deadline) {
    if (existsSync(join(traceDir, 'gate_health.sock')) && existsSync(join(traceDir, 'gate_health.pid'))) {
      return { child, env }
    }
    await new Promise((resolve) => setTimeout(resolve, 10))
  }
  throw new Error('trace supervisor did not become ready')
}

function finishSupervisor(traceDir, env) {
  const result = spawnSync('python3', [ipcScript, 'finish', '--stage', 'gate_health'], {
    env, encoding: 'utf8', timeout: 6000,
  })
  assert.ok([0, 2].includes(result.status), result.stderr)
  const report = JSON.parse(readFileSync(join(traceDir, 'admin-ui-stage-gate_health.json'), 'utf8'))
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
