// SPDX-License-Identifier: Apache-2.0
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const root = path.resolve(__dirname, '../../..');
const { JSDOM } = createRequire(path.join(root, 'openbank-admin-ui/package.json'))('jsdom');
const site = path.join(root, 'openbank-infra/web/landing');
const { annualSavingsIllustration } = require('../landing/bank-as-code.js');
const read = file => fs.readFileSync(path.join(site, file), 'utf8');

function browser(page, reduced = true) {
  const dom = new JSDOM(read(page), { runScripts: 'outside-only', url: 'https://open-bank.tech/' + page });
  const w = dom.window, jobs = new Map();
  let timerId = 0;
  const motion = { matches: reduced, addEventListener(_, callback) { this.change = callback; } };
  w.matchMedia = () => motion;
  w.setTimeout = callback => { jobs.set(++timerId, callback); return timerId; };
  w.clearTimeout = id => jobs.delete(id);
  w.fetch = () => { throw new Error('Illustrations must not contact a service'); };
  w.eval(read('bank-as-code.js'));
  const flush = () => { for (const [id, callback] of [...jobs]) { jobs.delete(id); callback(); } };
  return { dom, w, d: w.document, motion, jobs, flush };
}

test('savings illustration handles zero, maximum, fractional rates and bonus eligibility', () => {
  assert.deepEqual(annualSavingsIllustration(0, false), { base: 0, bonus: 0, total: 0 });
  assert.deepEqual(annualSavingsIllustration(2.5, true), { base: 250, bonus: 100, total: 350 });
  assert.deepEqual(annualSavingsIllustration(4.75, false), { base: 475, bonus: 0, total: 475 });
  assert.deepEqual(annualSavingsIllustration(5, true), { base: 500, bonus: 100, total: 600 });
  for (const rate of [-1, 5.25, NaN, Infinity, 'not a rate']) assert.throws(() => annualSavingsIllustration(rate, true), RangeError);
});

test('product input updates the outcome and displayed definition together', () => {
  const { dom, d, w } = browser('product-as-code.html');
  const rate = d.querySelector('#ob-v2-rate'), bonus = d.querySelector('#ob-v2-bonus');
  rate.value = '4.25'; rate.dispatchEvent(new w.Event('input'));
  assert.equal(d.querySelector('#ob-v2-total').textContent, '€525.00');
  assert.equal(d.querySelector('#ob-v2-code-rate').textContent, '0.0425');
  bonus.checked = false; bonus.dispatchEvent(new w.Event('change'));
  assert.equal(d.querySelector('#ob-v2-total').textContent, '€425.00');
  assert.equal(d.querySelector('#ob-v2-bonus-bar').style.width, '0%');
  dom.window.close();
});

test('animated journeys progress, finish and become replayable', () => {
  const { dom, d, jobs, flush } = browser('product-as-code.html', false);
  const button = d.querySelector('#ob-play-product');
  assert.equal(button.hidden, false);
  button.click();
  assert.equal(button.disabled, true);
  assert.equal(jobs.size, 3);
  flush();
  assert.equal(button.disabled, false);
  assert.match(d.querySelector('#ob-product-status').textContent, /person to examine/);
  assert.equal(d.querySelectorAll('#ob-product-flow [data-active=true]').length, 1);
  dom.window.close();
});

test('switching the control example cancels a running journey and updates the evidence and source', () => {
  const { dom, d, jobs, flush } = browser('controls-as-code.html', false);
  d.querySelector('#ob-play-control').click();
  assert.equal(jobs.size, 3);
  d.querySelector('[data-control=fraud]').click();
  assert.equal(jobs.size, 0);
  flush();
  assert.equal(d.querySelector('#ob-rec-check').textContent, 'maker ≠ checker');
  assert.match(d.querySelector('#ob-control-source').href, /FraudThresholdActivationServiceTest/);
  assert.match(d.querySelector('#ob-control-status').textContent, /proposer/);
  d.querySelector('[data-control=aml]').click();
  assert.equal(d.querySelector('#ob-rec-check').textContent, 'decision requirements');
  assert.match(d.querySelector('#ob-control-source').href, /AmlCase.kt/);
  assert.equal(d.querySelector('#ob-play-control').disabled, false);
  dom.window.close();
});

for (const request of ['allowed', 'denied']) test(`AI ${request} scenario displays its corresponding policy and audit outcome`, () => {
  const { dom, d, jobs } = browser('ai-as-code.html');
  d.querySelector(`[data-request=${request}]`).click();
  d.querySelector('#ob-play-ai').click();
  const log = d.querySelector('#ob-ai-log-text').textContent;
  assert.match(log, request === 'allowed' ? /policy.decision  ALLOW/ : /policy.decision  DENY/);
  assert.match(log, request === 'allowed' ? /PERMITTED/ : /NOT EXECUTED/);
  assert.equal(jobs.size, 0, 'Reduced motion must finish without animation timers');
  dom.window.close();
});

test('changing the AI scenario cannot publish a stale result from the previous run', () => {
  const { dom, d, jobs, flush } = browser('ai-as-code.html', false);
  d.querySelector('#ob-play-ai').click();
  d.querySelector('[data-request=denied]').click();
  assert.equal(jobs.size, 0);
  flush();
  assert.equal(d.querySelector('#ob-ai-log-tag').textContent, 'READY TO EXPLORE');
  d.querySelector('#ob-play-ai').click();
  flush();
  assert.match(d.querySelector('#ob-ai-log-text').textContent, /NOT EXECUTED/);
  dom.window.close();
});

test('enabling reduced motion finishes an in-flight journey and restores its button', () => {
  const { dom, d, motion, jobs } = browser('ai-as-code.html', false);
  const button = d.querySelector('#ob-play-ai');
  button.click();
  motion.matches = true; motion.change();
  assert.equal(jobs.size, 0);
  assert.equal(button.disabled, false);
  assert.equal(d.querySelectorAll('.ob-flash').length, 0);
  assert.match(d.querySelector('#ob-ai-log-text').textContent, /PERMITTED/);
  dom.window.close();
});

test('all eight disciplines and story links are readable without JavaScript', () => {
  const dom = new JSDOM(read('index.html')), d = dom.window.document;
  assert.equal(d.querySelectorAll('.ob-discipline').length, 8);
  assert.equal(d.querySelectorAll('#bank-as-code').length, 1);
  for (const name of ['Infrastructure', 'Documentation', 'Compliance', 'Product', 'AML', 'Fraud', 'AI', 'Bank']) {
    assert.ok([...d.querySelectorAll('.ob-discipline h3')].some(h => h.textContent === name + ' as Code'));
  }
  for (const page of ['product-as-code.html', 'controls-as-code.html', 'ai-as-code.html']) {
    assert.ok(d.querySelector(`a[href="${page}"]`));
    const article = new JSDOM(read(page)).window.document;
    assert.ok(article.querySelector('main h1'));
    assert.ok(!article.querySelector('main[hidden], [data-page], [data-view]'));
    assert.ok(article.querySelector('a[href^="https://github.com/JiRaska/open-bank-oss"]'));
    assert.doesNotMatch(read(page), /window\.openai|@@|localhost|data:image/);
  }
  dom.window.close();
});

test('extension styles are scoped and cannot restyle the original header, hero or app sections', () => {
  const dom = new JSDOM('<style>' + read('bank-as-code.css') + '</style>');
  function check(rules) {
    for (const rule of rules) {
      if (rule.selectorText) for (const selector of rule.selectorText.split(',')) assert.ok(selector.trim().startsWith('.bac'), selector);
      if (rule.cssRules && !rule.cssText.startsWith('@keyframes')) check(rule.cssRules);
    }
  }
  check(dom.window.document.querySelector('style').sheet.cssRules);
  dom.window.close();
});
