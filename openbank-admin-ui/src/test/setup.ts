// SPDX-License-Identifier: Apache-2.0
import '@testing-library/jest-dom'
import { afterEach } from 'vitest'

// ── Browser APIs this jsdom build does not implement ───────────────────────
// These are NOT app-code mocks — they are the browser primitives jsdom leaves out,
// which real pages legitimately use on mount. Without them the render-smoke suite
// cannot mount a single page (LanguageProvider reads localStorage in its very
// first effect; recharts constructs a ResizeObserver; some pages ask matchMedia
// for the colour scheme). Keep this list to genuine platform gaps — app code and
// app contexts must never be stubbed here.

if (typeof globalThis.localStorage === 'undefined') {
  const store = new Map<string, string>()
  const localStorageStub: Storage = {
    get length() { return store.size },
    key: (i: number) => Array.from(store.keys())[i] ?? null,
    getItem: (k: string) => (store.has(k) ? store.get(k)! : null),
    setItem: (k: string, v: string) => { store.set(k, String(v)) },
    removeItem: (k: string) => { store.delete(k) },
    clear: () => { store.clear() },
  }
  Object.defineProperty(globalThis, 'localStorage', { value: localStorageStub, writable: true })
  if (typeof window !== 'undefined') {
    Object.defineProperty(window, 'localStorage', { value: localStorageStub, writable: true })
  }
}

if (typeof globalThis.sessionStorage === 'undefined') {
  Object.defineProperty(globalThis, 'sessionStorage', { value: globalThis.localStorage, writable: true })
}

if (typeof globalThis.ResizeObserver === 'undefined') {
  // recharts' <ResponsiveContainer> constructs one on mount. A no-op observer is
  // correct for a smoke test: it never reports a resize, so charts render at their
  // default size — we assert the mount, not the layout.
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver
}

if (typeof window !== 'undefined' && typeof window.matchMedia === 'undefined') {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  })
}

if (typeof window !== 'undefined' && typeof window.scrollTo === 'undefined') {
  Object.defineProperty(window, 'scrollTo', { writable: true, value: () => {} })
}

// ── Unawaited-act gate (#3550) ───────────────────────────────────────────────
// React logs this on every run of the #3512 flake class — passing runs included:
//   "A component suspended inside an `act` scope, but the `act` call was not awaited."
// A bare render() of a use()-suspending tree hangs on its Suspense fallback forever and
// then *usually passes anyway* if a sibling test already stamped the shared promise
// fulfilled. Vitest intercepts console, so the warning reached nobody. Here the warning
// fails the test that emitted it. Falsified by unawaited-act-gate.test.tsx, which
// triggers the warning deliberately and asserts capture — a gate that has only ever
// passed is unfalsified, so the proof test is part of the gate, not a nicety.

const UNAWAITED_ACT_WARNING =
  'A component suspended inside an `act` scope, but the `act` call was not awaited'

let capturedUnawaitedAct: string | null = null

const originalConsoleError = console.error.bind(console)
console.error = (...args: unknown[]) => {
  const msg = args.map(String).join(' ')
  if (msg.includes(UNAWAITED_ACT_WARNING)) capturedUnawaitedAct = msg
  originalConsoleError(...args)
}

// take() is the ONLY way a test may see the warning without being failed by the
// afterEach below: the gate's own proof test consumes (and clears) it.
;(globalThis as { __takeUnawaitedActWarning?: () => string | null }).__takeUnawaitedActWarning =
  () => {
    const w = capturedUnawaitedAct
    capturedUnawaitedAct = null
    return w
  }

afterEach(() => {
  const w = capturedUnawaitedAct
  capturedUnawaitedAct = null
  if (w) {
    throw new Error(
      'React emitted the unawaited-act warning during this test. A bare render() of a ' +
        'use()-suspending tree hangs on its Suspense fallback and then passes or fails by ' +
        'sibling-test luck (#3512). Wrap the render: await act(async () => { render(...) }).\n' +
        w,
    )
  }
})
