// SPDX-License-Identifier: Apache-2.0
// Execute the served inline JavaScript offline; never request or submit a payment.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const pages = ['pay/v1.html', 'pay/v1/9998.html', 'pay/v1/9999.html'];
const site = path.resolve(__dirname, '../landing');
const source = page => fs.readFileSync(path.join(site, page), 'utf8');

function extractScript(html, page) {
  const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/gi)];
  assert.equal(scripts.length, 1, `${page}: expected the single served script`);
  return scripts[0][1];
}

function render(page, search, observeUnknownWrites = false) {
  const script = extractScript(source(page), page);
  const elements = new Map(['amt', 'rn', 'acc', 'msg', 'pay'].map(id => [id, {
    textContent: '', hidden: true,
    set innerHTML(value) { throw new Error('Payment fields must remain text'); },
  }]));
  const context = vm.createContext({
    location: { search },
    document: { getElementById(id) {
      assert.ok(elements.has(id), `unexpected DOM target ${id}`);
      return elements.get(id);
    } },
  });
  // Detect an unknown property write at runtime. This sentinel models an inherited
  // setter; it is not evidence that an attacker controls Object.prototype here.
  if (observeUnknownWrites) vm.runInContext(`
    globalThis.unexpectedWrites = 0;
    Object.defineProperty(Object.prototype, 'OB_UNEXPECTED_FIELD', {
      set(value) { globalThis.unexpectedWrites++; }, configurable: true
    });
  `, context);
  vm.runInContext(script, context, { timeout: 1000, filename: page });
  return {
    visible: !elements.get('pay').hidden,
    text: Object.fromEntries(['amt', 'rn', 'acc', 'msg'].map(id => [id, elements.get(id).textContent])),
    unexpectedWrites: observeUnknownWrites ? context.unexpectedWrites : 0,
  };
}

const query = spayd => `?${encodeURIComponent(spayd)}`;

test('script extraction respects HTML tag case and requires exactly one script', () => {
  for (const [open, close] of [['script', 'script'], ['SCRIPT', 'SCRIPT'], ['ScRiPt', 'sCrIpT']]) {
    assert.equal(extractScript(`<${open}>const value = 1;</${close}>`, 'fixture'), 'const value = 1;');
  }
  assert.throws(() => extractScript('<p>No script</p>', 'fixture'), /expected the single served script/);
  assert.throws(() => extractScript('<script>first()</script><SCRIPT>second()</SCRIPT>', 'fixture'),
    /expected the single served script/);
});

test('all three fallback payment pages remain byte-identical', () => {
  for (const page of pages.slice(1)) assert.equal(source(page), source(pages[0]), page);
});

for (const page of pages) {
  test(`${page}: renders supported SPAYD fields and account without BIC`, () => {
    assert.deepEqual(render(page, query('SPD*1.0*ACC:CZ0000000000000000000000+TESTCZ00*AM:123.45*CC:CZK*RN:Test Recipient*MSG:Test payment*X-VS:123')), {
      visible: true,
      text: { amt: '123.45 CZK', rn: 'Test Recipient', acc: 'CZ0000000000000000000000', msg: 'Test payment · VS 123' },
      unexpectedWrites: 0,
    });
  });

  test(`${page}: preserves colons, mixed-case names and last duplicate values`, () => {
    const result = render(page, query('SPD*1.0*aCc:FIRST*ACC:LAST+BIC*am:1*AM:2*cC:EUR*rN:Name: suffix*mSg:time:12:30*x-vs:001'));
    assert.equal(result.visible, true);
    assert.deepEqual(result.text, { amt: '2 EUR', rn: 'Name: suffix', acc: 'LAST', msg: 'time:12:30 · VS 001' });
  });

  test(`${page}: keeps empty/default fields and zero amounts`, () => {
    assert.deepEqual(render(page, query('SPD*1.0*ACC:TEST*RN:Name*rn:*MSG:old*msg:*X-VS:old*x-vs:*AM:0')).text,
      { amt: '0 ', rn: 'Účet příjemce', acc: 'TEST', msg: '' });
    assert.equal(render(page, query('SPD*1.0*ACC:TEST*ACC:')).visible, false);
  });

  test(`${page}: ignores unknown and prototype-like keys without object writes`, () => {
    const result = render(page, query('SPD*1.0*ACC:TEST*OB_UNEXPECTED_FIELD:payload*__proto__:ignored*constructor:ignored*prototype:ignored*toString:ignored*hasOwnProperty:ignored'), true);
    assert.equal(result.unexpectedWrites, 0, 'unknown URL keys must not reach object property assignment');
    assert.equal(result.visible, true);
    assert.deepEqual(result.text, { amt: '', rn: 'Účet příjemce', acc: 'TEST', msg: '' });
  });

  test(`${page}: malformed encoding or non-SPD input stays hidden`, () => {
    for (const search of ['', '?', '?%', '?%E0%A4%A', '?plain', query('spd*1.0*ACC:TEST'), query('SPD*1.0*MSG:No account'), query('SPD*1.0*:TEST*ACC')]) {
      const result = render(page, search);
      assert.equal(result.visible, false, search);
      assert.deepEqual(result.text, { amt: '', rn: '', acc: '', msg: '' });
    }
  });

  test(`${page}: HTML-looking and Unicode values remain literal text`, () => {
    const markup = '<img src=x onerror=alert(1)>';
    const result = render(page, query(`SPD*1.0*ACC:${markup}*RN:Žluťoučký 🦁*MSG:${markup}*X-VS:<b>123</b>`));
    assert.equal(result.visible, true);
    assert.deepEqual(result.text, { amt: '', rn: 'Žluťoučký 🦁', acc: markup, msg: `${markup} · VS <b>123</b>` });
  });
}
