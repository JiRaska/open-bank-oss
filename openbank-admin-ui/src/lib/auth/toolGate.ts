// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { auth } from "@/auth"
import { hasPermission, type Permission } from "@/lib/auth/roles"
import { sameOriginPath } from "@/lib/auth/safeCallbackPath"

/**
 * ADR-0234 — the identity-aware edge gate's DECISION, shared by its two edge contracts:
 *
 *   - `/api/gate?tool=<t>`            nginx `auth_request` (ingress-nginx, until ADR-0324 Phase 5)
 *   - `/api/gate/<t>/<original uri>`  Envoy Gateway `SecurityPolicy.extAuth` (ADR-0324 Phase 2)
 *
 * The edges differ only in how they spell the answer (see the two route files); the session
 * check, the allow-list and the deny-list below are one piece of code so the two edges cannot
 * drift apart while both serve traffic.
 */

/**
 * Tools reachable through the gate, and the permission each one requires.
 *
 * An ALLOW-list keyed by the tool the EDGE names — `?tool=` hard-coded in the Ingress
 * auth-url, or the path segment hard-coded in the SecurityPolicy's `extAuth.http.path` — an
 * unknown tool is denied, so adding an edge route without adding it here fails closed rather
 * than open.
 *
 * The permission is the SAME one the Sidebar uses to decide whether to render
 * the link, deliberately: a gate wider than the nav hides access nobody can
 * find, a gate narrower than the nav renders a link that 403s. Expressing both
 * as `system:view` means widening access is one edit in `roles.ts`, not two
 * edits kept in sync.
 *
 * Grafana's own `role_attribute_path` (kube-prometheus-stack values) then maps
 * ROLE_ADMIN→Admin and ROLE_OPERATOR→Editor. The gate is the coarse "may you
 * reach it at all" layer; the tool's own SSO decides what you can do inside.
 */
export const TOOL_PERMISSIONS: Record<string, Permission> = {
  grafana: "system:view",
  alertmanager: "system:view",
  pyrra: "system:view",
}

/**
 * PER-TOOL deny-list: roles refused for a specific tool, whatever else they carry.
 *
 * `demo@openbank.local` is a PUBLIC account — its credentials are handed out —
 * and in the sandbox realm it holds ROLE_ADMIN, ROLE_OPERATOR, ROLE_COMPLIANCE,
 * ROLE_AUDITOR, ROLE_PAYMENTS and ROLE_VIEWER alongside ROLE_DEMO, so that it can
 * show every console page. Those roles were harmless while the tools had no route;
 * ADR-0234 gave them one, and the permission check alone then admits the public
 * account everywhere.
 *
 * The demo account is DELIBERATELY still admitted to Grafana and Pyrra, because a
 * greyed-out console is a worse outcome than a read-only one — the point of the
 * account is that a visitor sees a working platform. It is held to the least
 * privilege each tool can express instead:
 *
 *   - Grafana: `role_attribute_path` (kube-prometheus-stack) tests ROLE_DEMO FIRST
 *     and maps it to Viewer. Viewer sees dashboards; it does not get Explore, which
 *     is what would otherwise expose the raw Prometheus/Loki/Tempo streams.
 *   - Pyrra: read-only by construction — there is nothing to restrict.
 *   - Alertmanager: DENIED, and it is the exception because it has no role model at
 *     all. Its UI is its API: anything that can load the page can silence or expire
 *     an alert on the whole platform. There is no read-only Alertmanager to offer,
 *     so the choice is "can mute production alerting" or "not admitted", and for a
 *     public account that is not a close call.
 *
 * Keyed by tool rather than global so that adding a tool forces the question
 * "what is the least privilege this one can express?" instead of inheriting an
 * answer. The Sidebar renders a denied tool as a disabled entry with the
 * demo-account tooltip, so it reads as deliberate rather than broken.
 *
 * Forbidden rather than unauthenticated — re-authenticating cannot help, and a
 * login redirect would loop the user through login.
 */
const TOOL_DENIED_ROLES: Record<string, readonly string[]> = {
  alertmanager: ["ROLE_DEMO"],
}

export type GateDecision = "allow" | "unauthenticated" | "forbidden"

export async function decideToolAccess(tool: string): Promise<GateDecision> {
  const required = Object.prototype.hasOwnProperty.call(TOOL_PERMISSIONS, tool) ? TOOL_PERMISSIONS[tool] : undefined

  // Unknown or missing tool: forbidden, not unauthenticated — re-authenticating cannot
  // help, and a login redirect would bounce the operator through a login loop.
  if (!required) return "forbidden"

  // `auth()` is typed as an overloaded helper, so its awaited type is not the
  // Session — read the user off it inside the try rather than annotating it.
  let user: { roles?: string[]; error?: string } | undefined
  try {
    user = (await auth())?.user
  } catch {
    // Fail closed. A thrown session decode is not an allow.
    return "unauthenticated"
  }

  // No session, or a session whose Keycloak refresh has failed (the middleware
  // forces a re-login on the same condition).
  if (!user || user.error === "RefreshAccessTokenError") return "unauthenticated"

  const roles: string[] = user.roles ?? []

  // Deny BEFORE the permission check: the denied roles are carried alongside the
  // ones that would pass it, so checking permission first would admit them.
  const denied = TOOL_DENIED_ROLES[tool] ?? []
  if (roles.some(r => denied.includes(r))) return "forbidden"

  return hasPermission(roles, required) ? "allow" : "forbidden"
}

/**
 * The post-login destination for a denied tool request — the browser's original
 * `/tools/<tool>/…` URI, or nothing. Same-origin only and confined to the tool's own
 * prefix: the URI arrives from the client, so it must not become an open redirect or
 * a way to bounce the operator anywhere else on the console after login.
 */
export function toolCallbackPath(tool: string, originalUri: string): string | null {
  const path = sameOriginPath(originalUri)
  if (!path) return null
  const prefix = `/tools/${tool}`
  return path === prefix || path.startsWith(`${prefix}/`) || path.startsWith(`${prefix}?`) ? path : null
}
