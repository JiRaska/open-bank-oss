#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Exercise the embedded account-overview listing contract without network access."""

from __future__ import annotations

import json
import pathlib
import re
import subprocess
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
CONFIG = ROOT / "openbank-infra/gitops/components/observability/cronjob-journey-account-overview.yaml"

NODE_TEST = r"""
const vm = require('node:vm');
const input = JSON.parse(require('node:fs').readFileSync(0, 'utf8'));
const owner = '11111111-1111-4111-8111-111111111111';
const other = '22222222-2222-4222-8222-222222222222';
const fixtureId = '33333333-3333-4333-8333-333333333333';
const foreignId = '00000000-0000-4000-8000-700000000000';
const customerBase = 'https://customer.open-bank.tech';
const row = { id: fixtureId, partyId: owner, status: 'ACTIVE', sharedWithMe: false };
const account = { id: fixtureId };
const balance = { balances: [
  { id: '44444444-4444-4444-8444-444444444444', accountId: fixtureId, currency: 'CZK',
    bookedAmount: 1000, availableAmount: 1000, reservedAmount: 0, pendingAmount: 0,
    notYetEffectiveCredit: 0, effectiveAvailableAmount: 1000 },
  { id: '55555555-5555-4555-8555-555555555555', accountId: fixtureId, currency: 'EUR',
    bookedAmount: 0, availableAmount: 0, reservedAmount: 0, pendingAmount: 0,
    notYetEffectiveCredit: 0, effectiveAvailableAmount: 0 },
] };

function execute(script, listing, tokenPartyId = owner, balanceBody = balance) {
  const payload = Buffer.from(JSON.stringify({ party_id: tokenPartyId })).toString('base64url');
  const token = `header.${payload}.signature`;
  const results = {};
  const response = (status, body) => ({ status, json: (path) => path === 'access_token' ? token : body });
  const context = {
    __ENV: { SYNTHETIC_CLIENT_SECRET: 'fixture-only', FIXTURE_BALANCE: '1000', FIXTURE_CURRENCY: 'CZK' },
    Date: { now: () => 1700000000000 },
    encoding: { b64decode: (value) => Buffer.from(value, 'base64url').toString('utf8') },
    check: (_value, checks) => Object.entries(checks).forEach(([name, fn]) => { results[name] = Boolean(fn()); }),
    console: { error: () => {} },
    http: {
      post: () => response(200, {}),
      get: (url, params = {}) => {
        if (url === `${customerBase}/customer/v1/accounts` && !params.headers) return response(401, {});
        if (url === `${customerBase}/customer/v1/accounts`) return response(200, listing);
        if (url === `${customerBase}/customer/v1/accounts/${fixtureId}`) return response(200, account);
        if (url === `${customerBase}/customer/v1/balances/${fixtureId}`) return response(200, balanceBody);
        if (url === `${customerBase}/customer/v1/accounts/${foreignId}`) return response(403, {});
        return response(500, {});
      },
    },
  };
  vm.runInNewContext(script + '\nglobalThis.__journey = journey; globalThis.__probe = { accountRows, ownedActiveFixture };', context);
  context.__journey();
  return { checks: results, probe: context.__probe };
}

function assertAllPass(label, listing, balanceBody = balance) {
  const { checks } = execute(input.script, listing, owner, balanceBody);
  if (Object.keys(checks).length !== 7 || Object.values(checks).some((value) => !value)) {
    throw new Error(`${label} did not pass all seven checks: ${JSON.stringify(checks)}`);
  }
}
function assertOwnershipFails(label, listing, tokenPartyId = owner) {
  const { checks } = execute(input.script, listing, tokenPartyId);
  if (checks['the canary lists exactly its own ACTIVE fixture account'] !== false) {
    throw new Error(`${label} passed the ownership check`);
  }
}
function assertBalanceFails(label, balanceBody, checkName) {
  const { checks } = execute(input.script, actualRuntimePage, owner, balanceBody);
  if (Object.keys(checks).length !== 7 || checks[checkName] !== false) {
    throw new Error(`${label} did not fail ${checkName}: ${JSON.stringify(checks)}`);
  }
}

const actualRuntimePage = { data: [row], pagination: { limit: 20, hasNextPage: false } };
const openApiPage = { data: [row], pagination: { hasMore: false, nextCursor: null } };
const dualPage = { data: [row], pagination: { hasMore: false, hasNextPage: false, nextCursor: null } };
assertAllPass('runtime PageInfo', actualRuntimePage);
assertAllPass('OpenAPI CursorPagination', openApiPage);
assertAllPass('both completion fields agree', dualPage);
assertAllPass('legacy account-list array', [row]);
assertAllPass('balance-service wrapped multi-currency response', actualRuntimePage, balance);
const futureCreditBalance = { balances: [{ ...balance.balances[0],
  notYetEffectiveCredit: 50, effectiveAvailableAmount: 950 }] };
assertAllPass('booked balance is distinct from future-credit spendability', actualRuntimePage, futureCreditBalance);
const overdraftLimitBalance = { balances: [{ ...balance.balances[0],
  availableAmount: 1100, effectiveAvailableAmount: 1100 }] };
assertAllPass('available amount may exceed booked amount', actualRuntimePage, overdraftLimitBalance);
assertOwnershipFails('wrong owner', { data: [{ ...row, partyId: other }], pagination: actualRuntimePage.pagination });
assertOwnershipFails('shared row', { data: [{ ...row, sharedWithMe: true }], pagination: actualRuntimePage.pagination });
assertOwnershipFails('missing own row', { data: [], pagination: actualRuntimePage.pagination });
assertOwnershipFails('malformed page', { data: [row] });
assertOwnershipFails('array pagination', { data: [row], pagination: [] });
assertOwnershipFails('more pages remain', { data: [row], pagination: { hasMore: true, nextCursor: 'next' } });
assertOwnershipFails('runtime more pages remain', { data: [row], pagination: { hasNextPage: true, nextCursor: 'next' } });
assertOwnershipFails('runtime more pages with omitted cursor', { data: [row], pagination: { hasNextPage: true } });
assertOwnershipFails('non-null next cursor', { data: [row], pagination: { hasMore: false, nextCursor: 'next' } });
assertOwnershipFails('empty but non-null next cursor', { data: [row], pagination: { hasNextPage: false, nextCursor: '' } });
assertOwnershipFails('missing completion flag', { data: [row], pagination: { nextCursor: null } });
assertOwnershipFails('non-boolean completion flag', { data: [row], pagination: { hasMore: 'false', nextCursor: null } });
assertOwnershipFails('conflicting completion flags', { data: [row], pagination: { hasMore: false, hasNextPage: true, nextCursor: null } });
assertOwnershipFails('token party claim mismatch', actualRuntimePage, other);

const balanceCheck = 'the balance is in the fixture currency and internally consistent';
assertBalanceFails('bare balance array envelope', balance.balances, 'the balance is served for the account');
assertBalanceFails('missing balances envelope', { data: balance.balances }, 'the balance is served for the account');
assertBalanceFails('wrong balance account', { balances: [{ ...balance.balances[0], accountId: other }] }, 'the balance is served for the account');
assertBalanceFails('duplicate balance currencies', { balances: [balance.balances[0], { ...balance.balances[0] }] }, 'the balance is served for the account');
assertBalanceFails('wrong fixture currency', { balances: [{ ...balance.balances[0], currency: 'EUR' }] }, 'the balance is served for the account');
for (const malformed of ['not-a-number', '1000', true, [], null]) {
  assertBalanceFails(`malformed bookedAmount ${JSON.stringify(malformed)}`, {
    balances: [{ ...balance.balances[0], bookedAmount: malformed }],
  }, balanceCheck);
}
assertBalanceFails('malformed balance collection', { balances: {} }, 'the balance is served for the account');
assertBalanceFails('inconsistent spendable value', {
  balances: [{ ...balance.balances[0], effectiveAvailableAmount: 999 }],
}, balanceCheck);

// Mutation control: restore the prior bare-array/currentBalance selection in the actual journey.
const currentBalanceSelection = 'const b = id ? ownedCurrencyBalance(balanceBody, id, FIXTURE_CURRENCY) : null;';
const oldBalanceSelection = `const balanceRows = balance && balance.status === 200 ? json(balance) : null;
      const b = Array.isArray(balanceRows) ? (balanceRows.find((r) => r && r.currency === FIXTURE_CURRENCY) || null) : null;`;
if (!input.script.includes(currentBalanceSelection)) throw new Error('current balance selection fragment missing');
const oldBalanceParser = input.script.replace(currentBalanceSelection, oldBalanceSelection);
if (oldBalanceParser === input.script) throw new Error('could not apply old bare-array balance parser mutation');
const { checks: oldBalanceChecks } = execute(oldBalanceParser, actualRuntimePage);
if (oldBalanceChecks['the balance is served for the account'] !== false) {
  throw new Error('old bare-array/currentBalance parser passed the wrapped-balance journey');
}

const { probe } = execute(input.script, actualRuntimePage);
if (probe.accountRows({ data: [row], pagination: [] }) !== null) throw new Error('array pagination helper accepted');
if (probe.accountRows({ data: [row], pagination: { hasMore: true, nextCursor: 'next' } }) !== null) {
  throw new Error('incomplete page helper accepted');
}
if (probe.accountRows({ data: [row], pagination: { hasMore: false, hasNextPage: true, nextCursor: null } }) !== null) {
  throw new Error('conflicting completion fields helper accepted');
}
for (const pagination of [actualRuntimePage.pagination, openApiPage.pagination, dualPage.pagination]) {
  if (probe.ownedActiveFixture({ data: [row], pagination }, owner)?.id !== fixtureId) {
    throw new Error('complete-page helper rejected a documented completion spelling');
  }
}

// Mutation control: restore the old array-only fixture selection in the actual journey body.
const oldSelection = "const fixture = Array.isArray(accounts) ? accounts.filter((a) => a && a.status === 'ACTIVE')[0] : null;";
const mutated = input.script.replace('const fixture = ownedActiveFixture(accounts, ownPartyId);', oldSelection);
if (mutated === input.script) throw new Error('could not apply old array-only selection mutation');
const { checks: mutationChecks } = execute(mutated, actualRuntimePage);
if (mutationChecks['the canary lists exactly its own ACTIVE fixture account'] !== false) {
  throw new Error('old array-only selection mutation passed the CursorPage journey');
}

// Mutation control: a malformed foreign-account path must not get the expected 403 by default.
const foreignPath = '${CUSTOMER_BASE}/customer/v1/accounts/00000000-0000-4000-8000-';
const wrongForeignPath = '${CUSTOMER_BASE}/customer/v1/not-accounts/00000000-0000-4000-8000-';
const wrongForeignRoute = input.script.replace(foreignPath, wrongForeignPath);
if (wrongForeignRoute === input.script) throw new Error('could not apply wrong foreign-route mutation');
const { checks: foreignMutationChecks } = execute(wrongForeignRoute, actualRuntimePage);
if (foreignMutationChecks['an account the caller does not own is refused'] !== false) {
  throw new Error('wrong foreign-account route passed the refusal check');
}
"""


def main() -> int:
    try:
        documents = yaml.safe_load_all(CONFIG.read_text(encoding="utf-8"))
        document = next(item for item in documents if item.get("kind") == "ConfigMap")
        script = document["data"]["journey.js"]
    except (OSError, KeyError, TypeError, yaml.YAMLError) as exc:
        print(f"account-overview probe test: cannot read embedded script: {exc}", file=sys.stderr)
        return 2

    script = re.sub(r"(?m)^import .*?;\s*$", "", script)
    script = script.replace("export const options", "const options")
    script = script.replace("export default function () {", "function journey() {")
    if "function ownedActiveFixture" not in script or "partyIdFromToken(token)" not in script:
        print("account-overview probe test: ownership contract is absent from journey source", file=sys.stderr)
        return 1

    result = subprocess.run(
        ["node", "-e", NODE_TEST],
        input=json.dumps({"script": script}),
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode:
        print(result.stderr or result.stdout, file=sys.stderr)
        return result.returncode
    print("account-overview probe test: pass (account page and legacy array, wrapped multi-currency balance, ownership and balance negatives, parser/route mutations)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
