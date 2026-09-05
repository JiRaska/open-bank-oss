import { describe, it, expect } from "vitest"
import { readFileSync, existsSync } from "node:fs"
import { execFileSync } from "node:child_process"
import { join } from "node:path"

/**
 * The /temporal page describes the Temporal rollout to operators, and it once
 * advertised a whole authorization architecture that did not exist: it named
 * `OpaActivityInterceptor`, `OpenBankSaga`, `TemporalWorkerConfig` and
 * `DeterministicRandom` as delivered, and derived a PSD2 Art. 5(3) compliance
 * claim from "OPA policy gate on each activity". `openbank-libs-temporal`
 * contained exactly two files, and none of those four classes existed anywhere
 * in the fleet.
 *
 * A page that overstates a control is worse than one that omits it, because it
 * stops anyone from looking. This guard pins the page's *shipped* claims to
 * classes that actually exist on disk, so the next component named as delivered
 * has to be delivered.
 *
 * It deliberately asserts existence and not behaviour: proving the interceptor
 * *works* needs a running worker, but proving the class is absent is one lookup
 * and catches the failure that actually happened.
 */

const REPO_ROOT = join(__dirname, "..", "..", "..")
const PAGE = join(REPO_ROOT, "openbank-admin-ui", "src", "app", "temporal", "page.tsx")

/** Kotlin identifiers the page may name. Each must exist iff claimed as shipped. */
const NAMED_COMPONENTS = [
  "TemporalClientProducer",
  "TemporalConfig",
  "TemporalWorkerConfig",
  "OpenBankSaga",
  "OpaActivityInterceptor",
  "DeterministicRandom",
]

function kotlinClassExists(name: string): boolean {
  // Search the tracked tree only; an untracked local file must not make a claim pass.
  try {
    const out = execFileSync(
      "git",
      // -P, not -E: POSIX ERE has no \b, so the -E form silently matched nothing
      // and every assertion here would have passed vacuously. The self-test below
      // is what caught it.
      ["grep", "-l", "-P", `(class|object|interface) ${name}\\b`, "--", "*.kt"],
      { cwd: REPO_ROOT, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] },
    )
    return out.trim().length > 0
  } catch (err) {
    // git grep exits 1 for "no match" and >1 for a real failure. Collapsing both
    // to `false` would let a broken git invocation read as "the class is absent",
    // which is the direction that turns this guard into decoration.
    const status = (err as { status?: number }).status
    if (status === 1) return false
    throw new Error(`git grep failed for ${name} (exit ${String(status)})`)
  }
}

describe("temporal page: every component claimed as shipped exists", () => {
  const page = readFileSync(PAGE, "utf8")

  it("the page under test is present", () => {
    expect(existsSync(PAGE)).toBe(true)
  })

  it("names no Kotlin component as shipped unless it exists in the tree", () => {
    const violations: string[] = []

    for (const name of NAMED_COMPONENTS) {
      if (!page.includes(name)) continue

      const exists = kotlinClassExists(name)

      // Find every line naming it, and require that a line asserting delivery
      // is only present when the class is real. A line that marks the component
      // planned/not-deployed is always allowed.
      for (const line of page.split("\n")) {
        if (!line.includes(name)) continue
        const disclaimed =
          /plánován|planned|neexistuj|not exist|Neither|not deployed|nenasazen|zatím ne|target state|cílového stavu/i.test(line)
        if (!disclaimed && !exists) {
          violations.push(`${name}: claimed without a disclaimer, but no Kotlin class exists — ${line.trim()}`)
        }
      }
    }

    expect(violations).toEqual([])
  })

  it("makes no unqualified PSD2 claim resting on the activity-level policy gate", () => {
    // The gate does not exist; a compliance line asserting it is a false
    // regulatory claim on an operator-facing screen.
    const psd2Lines = page.split("\n").filter((l) => l.includes("PSD2"))
    expect(psd2Lines.length).toBeGreaterThan(0)

    for (const line of psd2Lines) {
      if (/policy gate/i.test(line)) {
        expect(line).toMatch(/plánováno|planned|not deployed|nenasazen/i)
      }
    }
  })

  it("the guard can actually detect a nonexistent class (self-test)", () => {
    // Without this, every assertion above would pass vacuously if `git grep`
    // silently returned nothing for all inputs.
    expect(kotlinClassExists("TemporalClientProducer")).toBe(true)
    expect(kotlinClassExists("ThisClassIsNotInTheFleetAtAll")).toBe(false)
  })
})
