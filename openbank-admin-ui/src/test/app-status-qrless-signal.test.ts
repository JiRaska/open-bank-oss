// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'fs'
import { tmpdir } from 'os'
import path from 'path'
// @ts-expect-error - plain .mjs build script, no type declarations by design
import { deriveCapabilityStatus } from '../../scripts/generate-app-status.mjs'

// The `qrless-pay` derivedStatus signal (ADR-0074 / issue #26), tested on the paths that
// actually rotted rather than only the happy one.
//
// Why this capability got a signal at all: the curatorial `status` for it was wrong twice in
// one week. openbank-app #445 found `planned` + "NO implementation" in the yaml while the
// protocol core, both BLE transports and the payee surface were merged and shipping; #455 then
// found #445's own correction stale four PRs later, after the payer path was wired behind a
// switch and replay defence landed. Both were exactly the single-field drift this signal sees.
//
// The interesting case is the LAST one below. The payer half is dormant because of an explicit
// `PAYER_DISCOVERY_ENABLED` constant, and that constant exists because dormancy used to rest on
// nothing happening to call `startDiscovery` — a control any refactor removes without noticing.
// So a missing flag must read as "no verdict", not as a status: if this check answered `partial`
// when the switch is gone, it would place a reassuring green tick on the removal it exists to
// catch. Nothing else in the pipeline would notice either — the --check mode diffs the `derived`
// block, and the curatorial narrative is prose no gate reads.

interface Signals {
  signals: Record<string, string | null>
  gaps: string[]
}

const NEARPAY_KT = 'shared/src/commonMain/kotlin/tech/openbank/app/payment/nearpay/NearPay.kt'
const REQUEST_KT = 'composeApp/src/commonMain/kotlin/tech/openbank/app/ui/RequestScreen.kt'

/** NearPay.kt reduced to the one constant the signal reads. */
const nearPay = (flag: string) => `package tech.openbank.app.payment.nearpay

object NearPay {
    const val MAX_TTL_SECONDS = 90L
    const val RSSI_GATE_DBM = -70
${flag}
}
`

/** RequestScreen.kt with the payee half advertising a minted session. */
const PAYEE_WIRED = `class RequestScreen {
    val nearPayCtrl = nearPayController()
    fun go() { nearPayCtrl.startReceiving(firstName = "J", spayd = s, amountMinor = null) }
}
`

/** RequestScreen.kt that imports the controller but never advertises. */
const PAYEE_ABSENT = `class RequestScreen {
    val nearPayCtrl = nearPayController()
}
`

/** Build a throwaway app repo holding only the files this signal reads. */
function signalFor(files: Record<string, string | null>): Signals {
  const repo = mkdtempSync(path.join(tmpdir(), 'app-status-qrless-'))
  try {
    for (const [rel, body] of Object.entries(files)) {
      if (body === null) continue // absent on purpose
      const abs = path.join(repo, rel)
      mkdirSync(path.dirname(abs), { recursive: true })
      writeFileSync(abs, body)
    }
    // certPinningConfigured is unrelated to this capability; null keeps tls-pinning at "no verdict".
    return deriveCapabilityStatus(repo, null) as Signals
  } finally {
    rmSync(repo, { recursive: true, force: true })
  }
}

const qrless = (files: Record<string, string | null>) => signalFor(files).signals['qrless-pay']

describe('app-status qrless-pay derivedStatus', () => {
  it('reads partial when the payee advertises and the payer switch is off', () => {
    // The state that actually shipped, and the one the yaml must agree with today.
    expect(
      qrless({
        [NEARPAY_KT]: nearPay('    const val PAYER_DISCOVERY_ENABLED = false'),
        [REQUEST_KT]: PAYEE_WIRED,
      }),
    ).toBe('partial')
  })

  it('reads live once the payer switch is flipped on', () => {
    expect(
      qrless({
        [NEARPAY_KT]: nearPay('    const val PAYER_DISCOVERY_ENABLED = true'),
        [REQUEST_KT]: PAYEE_WIRED,
      }),
    ).toBe('live')
  })

  it('reads partial when only the payer half is on', () => {
    expect(
      qrless({
        [NEARPAY_KT]: nearPay('    const val PAYER_DISCOVERY_ENABLED = true'),
        [REQUEST_KT]: PAYEE_ABSENT,
      }),
    ).toBe('partial')
  })

  it('reads planned when neither half is on', () => {
    expect(
      qrless({
        [NEARPAY_KT]: nearPay('    const val PAYER_DISCOVERY_ENABLED = false'),
        [REQUEST_KT]: PAYEE_ABSENT,
      }),
    ).toBe('planned')
  })

  it('contradicts a yaml still claiming planned once the payee ships — the #445 drift', () => {
    // #445's actual finding, reduced: the protocol was merged and the payee was live while the
    // dossier said `planned`. Any answer other than `planned` makes the drift visible; asserting
    // the exact value is the first case's job.
    const signal = qrless({
      [NEARPAY_KT]: nearPay('    const val PAYER_DISCOVERY_ENABLED = false'),
      [REQUEST_KT]: PAYEE_WIRED,
    })
    expect(signal).not.toBe('planned')
    expect(signal).not.toBeNull()
  })

  it('has NO verdict when the payer switch is gone, and says why', () => {
    // A refactor that deletes the constant must not read as a status. See the header.
    const out = signalFor({
      [NEARPAY_KT]: nearPay('    // PAYER_DISCOVERY_ENABLED removed by a refactor'),
      [REQUEST_KT]: PAYEE_WIRED,
    })
    expect(out.signals['qrless-pay']).toBeNull()
    expect(out.gaps.some(g => g.includes('PAYER_DISCOVERY_ENABLED'))).toBe(true)
  })

  it('has no verdict when a source file is missing entirely', () => {
    expect(qrless({ [NEARPAY_KT]: null, [REQUEST_KT]: PAYEE_WIRED })).toBeNull()
    expect(qrless({ [NEARPAY_KT]: nearPay('    const val PAYER_DISCOVERY_ENABLED = false'), [REQUEST_KT]: null })).toBeNull()
  })

  it('does not fabricate a verdict for the purely curatorial capabilities', () => {
    // ADR-0074: a capability with no cheap code check must carry no derivedStatus at all,
    // so the join can tell "no signal" apart from "signal says null".
    const { signals } = signalFor({
      [NEARPAY_KT]: nearPay('    const val PAYER_DISCOVERY_ENABLED = false'),
      [REQUEST_KT]: PAYEE_WIRED,
    })
    for (const id of ['ui-stack', 'shared-domain', 'customer-edge', 'keycloak-realm', 'crash-monitoring', 'dossier']) {
      expect(Object.prototype.hasOwnProperty.call(signals, id), `${id} must stay curatorial`).toBe(false)
    }
  })
})
