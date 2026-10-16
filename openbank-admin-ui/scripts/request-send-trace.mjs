// Count Undici outgoing header sends without inspecting or retaining request data.
// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.

import { channel } from 'node:diagnostics_channel'
import net from 'node:net'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'

const STAGES = new Set(['gate_health'])
const AUTH_ALIASES = new Set(['github'])

export function classifyOrigin(origin) {
  if (typeof origin !== 'string') return 'unknown'
  const hostname = new URL(origin).hostname.toLowerCase()
  return hostname === 'api.github.com' ? 'api' : 'non_api'
}

function taint() {
  try {
    const pid = Number(process.env.ADMIN_UI_TRACE_PID)
    if (Number.isSafeInteger(pid) && pid > 1) process.kill(pid, 'SIGUSR1')
  } catch {}
}

export function createRequestSendTrace(stage, authAlias, traceDir) {
  const counts = new Map()
  let incomplete = !STAGES.has(stage) || !AUTH_ALIASES.has(authAlias)
  let sawSend = false
  let installed = false
  let socket
  let buffer = ''
  let pendingAck = null
  const invocationId = randomUUID().replaceAll('-', '')
  let readyResolve
  const readyPromise = new Promise((resolve) => { readyResolve = resolve })

  try {
    if (!traceDir || incomplete) throw new Error('trace IPC unavailable')
    socket = net.createConnection(join(traceDir, stage + '.sock'))
    socket.setTimeout(2000)
    socket.on('timeout', () => {
      incomplete = true
      readyResolve(false)
      pendingAck?.(false)
      pendingAck = null
      taint()
      socket.destroy()
    })
    socket.on('data', (chunk) => {
      buffer += chunk.toString('utf8')
      while (true) {
        const newline = buffer.indexOf('\n')
        if (newline < 0) break
        if (newline > 256) {
          incomplete = true
          pendingAck?.(false)
          pendingAck = null
          buffer = ''
          break
        }
        const line = buffer.slice(0, newline)
        buffer = buffer.slice(newline + 1)
        try {
          const ok = JSON.parse(line).ok === true
          if (!ok) incomplete = true
          pendingAck?.(ok)
        } catch {
          incomplete = true
          pendingAck?.(false)
        }
        pendingAck = null
      }
    })
    socket.on('error', (error) => {
      incomplete = true
      readyResolve(false)
      taint()
    })
    socket.on('close', () => {
      if (!installed || pendingAck) {
        incomplete = true
        readyResolve(false)
        pendingAck?.(false)
        pendingAck = null
        taint()
      }
    })
    socket.on('connect', () => {
      pendingAck = (ok) => {
        installed = ok
        if (ok) socket.setTimeout(0)
        readyResolve(ok)
      }
      socket.write(JSON.stringify({
        type: 'begin',
        id: invocationId,
        stage,
        client: 'node_fetch',
        authAlias,
      }) + '\n')
    })
  } catch {
    incomplete = true
    readyResolve(false)
    taint()
  }

  try {
    channel('undici:client:sendHeaders').subscribe((event) => {
      try {
        const destinationClass = classifyOrigin(event?.request?.origin)
        if (destinationClass === 'unknown') {
          incomplete = true
          return
        }
        counts.set(destinationClass, (counts.get(destinationClass) || 0) + 1)
        sawSend = true
      } catch {
        incomplete = true
      }
    })
  } catch {
    incomplete = true
  }

  async function sendEnd(complete, reasonEnum) {
    try {
      await readyPromise
      if (!socket || !installed || socket.destroyed) throw new Error('trace IPC unavailable')
      const event = {
        type: 'end',
        id: invocationId,
        counts: Object.fromEntries(counts),
        complete,
        reasonEnum,
      }
      const acked = await new Promise((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error('trace IPC acknowledgement timeout')), 2000)
        pendingAck = (ok) => {
          clearTimeout(timeout)
          resolve(ok)
        }
        socket.write(JSON.stringify(event) + '\n')
      })
      if (!acked) throw new Error('trace IPC rejected invocation')
      socket.end()
      return true
    } catch {
      incomplete = true
      taint()
      socket?.destroy()
      return false
    }
  }

  return {
    async ready() {
      try { return await readyPromise } catch { return false }
    },
    snapshot() {
      return { counts: new Map(counts), complete: installed && !incomplete && sawSend }
    },
    async write({ configured = true } = {}) {
      const complete = installed && !incomplete && configured && sawSend
      const reasonEnum = !installed
        ? 'hook_unavailable'
        : incomplete
          ? 'trace_parse_failure'
          : !configured
            ? 'collector_not_configured'
            : !sawSend
              ? 'no_send_observed'
              : null
      return sendEnd(complete, reasonEnum)
    },
  }
}
