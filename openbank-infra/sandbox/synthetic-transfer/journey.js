// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 1,
  iterations: 1,
  systemTags: ['name', 'method', 'status', 'scenario'],
  tags: { journey: 'synthetic-own-account-transfer' },
  thresholds: { checks: ['rate==1.0'] },
};

const isoDate = /^\d{4}-\d{2}-\d{2}$/;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const steps = [
  'fixture balances and history are consistent',
  'outbound transfer appears on both accounts with one synthetic journal',
  'return transfer appears on both accounts with one synthetic journal',
  'fixture balances return to baseline',
];

class FixtureFailure extends Error {}

function need(condition, step) {
  if (!condition) throw new FixtureFailure(step);
}

function setting(name) {
  const value = __ENV[name];
  need(typeof value === 'string' && value.length > 0, `missing fixture setting ${name}`);
  return value;
}

function endpoint(name) {
  const value = setting(name).replace(/\/$/, '');
  need(/^https:\/\//.test(value) || /^http:\/\/127\.0\.0\.1:\d+(\/|$)/.test(value), 'invalid fixture URL');
  return value;
}

function params(name, token, tainted = false) {
  const headers = { Accept: 'application/json', 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (tainted) headers['x-openbank-synthetic'] = 'true';
  return { headers, redirects: 0, timeout: '5s', tags: { name } };
}

function token(url, clientId, secret) {
  const response = http.post(url, {
    grant_type: 'client_credentials', client_id: clientId, client_secret: secret,
  }, { redirects: 0, timeout: '5s', tags: { name: 'fixture-token' } });
  need(response.status === 200, 'fixture authentication failed');
  const accessToken = response.json('access_token');
  need(typeof accessToken === 'string' && accessToken.length > 0, 'fixture token unavailable');
  return accessToken;
}

function minor(amount) {
  const match = /^(-?\d+)(?:\.(\d+))?$/.exec(String(amount));
  need(match !== null && !/[1-9]/.test((match[2] || '').slice(2)), 'invalid fixture currency amount');
  const fraction = Number((match[2] || '').slice(0, 2).padEnd(2, '0'));
  const minorAmount = Number(match[1]) * 100 + (match[1].startsWith('-') ? -fraction : fraction);
  need(Number.isSafeInteger(minorAmount), 'fixture currency amount out of range');
  return minorAmount;
}

function waitFor(action, reason) {
  const deadline = Date.now() + 45_000;
  do {
    const result = action();
    if (result) return result;
    sleep(1);
  } while (Date.now() < deadline);
  throw new FixtureFailure(reason);
}

function verify(name) {
  check(null, { [name]: () => true });
}

function fixture() {
  const source = setting('FIXTURE_SOURCE_ACCOUNT');
  const target = setting('FIXTURE_TARGET_ACCOUNT');
  need(uuid.test(source) && uuid.test(target) && source !== target, 'invalid fixture account pair');
  const sourceBooked = minor(setting('FIXTURE_SOURCE_BOOKED_CZK'));
  const targetBooked = minor(setting('FIXTURE_TARGET_BOOKED_CZK'));
  need(sourceBooked > 0 && targetBooked >= 0, 'fixture has insufficient starting funds');
  const f = {
    source, target, sourceBooked, targetBooked,
    customerBase: endpoint('CUSTOMER_BASE'),
    ledgerBase: endpoint('LEDGER_BASE'),
    customerToken: token(endpoint('CUSTOMER_TOKEN_URL'), 'openbank-synthetic-transfer',
      setting('CUSTOMER_CLIENT_SECRET')),
    ledgerToken: token(endpoint('LEDGER_TOKEN_URL'), 'openbank-synthetic-transfer-verifier',
      setting('LEDGER_CLIENT_SECRET')),
  };
  return { ...f, runDate: bankDate(f) };
}

function bankDate(f) {
  const response = http.get(`${f.customerBase}/customer/v1/synthetic/transfers`,
    params('fixture-bank-date', f.customerToken, true));
  need(response.status === 200, 'fixture bank date unavailable');
  const runDate = response.json('runDate');
  need(typeof runDate === 'string' && isoDate.test(runDate), 'invalid fixture bank date');
  return runDate;
}

function history(f, account) {
  const response = http.get(`${f.customerBase}/customer/v1/transactions?accountId=${account}&limit=50`,
    params('fixture-transactions', f.customerToken, true));
  need(response.status === 200, 'fixture transaction history unavailable');
  const rows = response.json('data');
  need(Array.isArray(rows), 'invalid fixture transaction history');
  return rows;
}

function leg(rows, f, from, to) {
  const matches = rows.filter((row) => row.valueDate === f.runDate && row.type === 'TRANSFER'
    && row.description === 'Synthetic transfer canary' && row.sourceAccountId === from && row.targetAccountId === to);
  need(matches.length <= 1, 'duplicate fixture transaction history');
  if (matches.length === 0) return null;
  const row = matches[0];
  need(uuid.test(row.id) && row.currencyCode === 'CZK' && minor(row.amount) === 1,
    'fixture transaction fields differ');
  need(row.status !== 'FAILED' && row.status !== 'REVERSED', 'fixture transaction did not complete');
  return row;
}

function booked(f, account) {
  const response = http.get(`${f.customerBase}/customer/v1/balances/${account}`,
    params('fixture-balance', f.customerToken, true));
  need(response.status === 200, 'fixture balance unavailable');
  const rows = response.json('balances');
  need(Array.isArray(rows), 'invalid fixture balance envelope');
  const row = rows.find((balance) => balance.accountId === account && balance.currency === 'CZK');
  need(row !== undefined, 'fixture CZK balance unavailable');
  return minor(row.bookedAmount);
}

function waitBalance(f, sourceDelta, targetDelta) {
  waitFor(() => booked(f, f.source) === f.sourceBooked + sourceDelta
    && booked(f, f.target) === f.targetBooked + targetDelta, 'fixture booked balances did not converge');
}

function submit(f, direction) {
  need(bankDate(f) === f.runDate, 'fixture bank date changed before submission');
  const url = `${f.customerBase}/customer/v1/synthetic/transfers`;
  const payload = JSON.stringify({ runDate: f.runDate, direction });
  let response = http.post(url, payload, params('fixture-submit', f.customerToken, true));
  if ([0, 502, 503, 504].includes(response.status)) {
    response = http.post(url, payload, params('fixture-submit-replay', f.customerToken, true));
  }
  need(response.status === 201, 'fixture transfer was not accepted');
  const id = response.json('id');
  need(uuid.test(id), 'fixture transfer identifier unavailable');
  return id;
}

function proof(f, id, from, to) {
  return waitFor(() => {
    const sourceLeg = leg(history(f, f.source), f, from, to);
    const targetLeg = leg(history(f, f.target), f, from, to);
    if (!sourceLeg || !targetLeg) return null;
    need(sourceLeg.id === id && targetLeg.id === id, 'fixture histories disagree on the transaction');
    if (sourceLeg.status !== 'COMPLETED' || targetLeg.status !== 'COMPLETED') return null;
    const response = http.get(`${f.ledgerBase}/api/v1/journals/synthetic/transactions/${id}`,
      params('fixture-journal-proof', f.ledgerToken));
    if (response.status === 404) return null;
    need(response.status === 200, 'fixture ledger proof unavailable');
    const journal = response.json();
    need(journal.transactionId === id && uuid.test(journal.journalId) && journal.synthetic === true
      && journal.postingCount === 1 && journal.amountMinor === 1 && journal.currency === 'CZK'
      && journal.debitAccountId === from && journal.creditAccountId === to && journal.valueDate === f.runDate,
    'fixture ledger proof differs from the transfer');
    return journal.journalId;
  }, 'fixture transfer did not converge through history and ledger');
}

export default function () {
  let completed = 0;
  let f;
  let outboundId;
  let forwardMayExist = false;
  try {
    f = fixture();
    const rows = history(f, f.source);
    const outbound = leg(rows, f, f.source, f.target);
    if (outbound) {
      forwardMayExist = true;
      outboundId = outbound.id;
    }
    const returning = leg(rows, f, f.target, f.source);
    need(outbound || !returning, 'fixture return exists without outbound history');
    if (!outbound) waitBalance(f, 0, 0);
    verify(steps[completed++]);

    if (!outboundId) {
      forwardMayExist = true;
      outboundId = submit(f, 'OUTBOUND');
    }
    const firstJournal = proof(f, outboundId, f.source, f.target);
    verify(steps[completed++]);

    if (!returning) waitBalance(f, -1, 1);
    const returnId = returning ? returning.id : submit(f, 'RETURN');
    const secondJournal = proof(f, returnId, f.target, f.source);
    need(outboundId !== returnId && firstJournal !== secondJournal, 'fixture directions share a posting');
    verify(steps[completed++]);
    waitBalance(f, 0, 0);
    verify(steps[completed++]);
  } catch (failure) {
    console.error(failure instanceof FixtureFailure ? failure.message : 'fixture response could not be processed');
    if (f && forwardMayExist && completed < 3) {
      console.error(`fixture compensation unproven for bank date ${f.runDate}, outbound ${outboundId || 'unknown'}`);
    }
    for (const step of steps.slice(completed)) check(null, { [step]: () => false });
  }
}
