// SPDX-License-Identifier: Apache-2.0
// Offline integration checks. No test submits to Web3Forms or requests a CAPTCHA.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const { execFileSync } = require('node:child_process');
const root = path.resolve(__dirname, '../../..');
const requireAdmin = createRequire(path.join(root, 'openbank-admin-ui/package.json'));
const { JSDOM } = requireAdmin('jsdom');
const site = path.join(root, 'openbank-infra/web/landing');
const read = name => fs.readFileSync(path.join(site, name), 'utf8');
const formHtml = text => text.match(/<form class="tf-form"[\s\S]*?<\/form>/)[0].replace(/data-theme="(?:light|dark)"/, 'data-theme="THEME"');

function browser() {
  const dom = new JSDOM(read('index.html'), { url: 'https://open-bank.tech/', runScripts: 'outside-only' });
  dom.window.IntersectionObserver = class { observe() {} unobserve() {} };
  dom.window.matchMedia = () => ({ matches: true });
  dom.window.fetch = () => { throw new Error('Unmocked network request'); };
  dom.window.eval(read('main.js'));
  return dom;
}
const tick = () => new Promise(resolve => setImmediate(resolve));

test('TestFlight form contract and privacy dialog are unchanged except the widget theme', () => {
  assert.equal(formHtml(read('index.html')), formHtml(read('classic.html')));
  const current = new JSDOM(read('index.html')).window.document;
  const previous = new JSDOM(read('classic.html')).window.document;
  assert.equal(current.querySelector('#tf-modal').outerHTML, previous.querySelector('#tf-modal').outerHTML);
  assert.equal(current.querySelector('#tf-form').action, 'https://api.web3forms.com/submit');
  assert.equal(current.querySelector('[name=consent]').checked, false);
  assert.equal(current.querySelector('[name=consent]').required, true);
  assert.equal(current.querySelector('[name=email]').required, true);
  assert.equal(current.querySelector('[name=botcheck]').tabIndex, -1);
});

test('native validation requires a valid email and explicit consent', () => {
  const dom = browser();
  const form = dom.window.document.querySelector('#tf-form');
  assert.equal(form.checkValidity(), false);
  form.elements.email.value = 'tester@example.invalid';
  assert.equal(form.checkValidity(), false);
  form.elements.consent.checked = true;
  assert.equal(form.checkValidity(), true);
  dom.window.close();
});

test('rendered CAPTCHA prevents submission without a response', async () => {
  const dom = browser(), w = dom.window, d = w.document;
  let requests = 0;
  w.fetch = async () => { requests++; return { ok: true, json: async () => ({ success: true }) }; };
  d.querySelector('.h-captcha').append(d.createElement('iframe'));
  d.querySelector('#tf-form').dispatchEvent(new w.Event('submit', { bubbles: true, cancelable: true }));
  await tick();
  assert.equal(requests, 0);
  assert.match(d.querySelector('#tf-status').textContent, /complete/);
  dom.window.close();
});

test('successful signup posts the original payload and shows confirmation', async () => {
  const dom = browser(), w = dom.window, d = w.document;
  let request;
  w.fetch = async (url, options) => { request = { url, options }; return { ok: true, json: async () => ({ success: true }) }; };
  const form = d.querySelector('#tf-form');
  form.elements.email.value = 'tester@example.invalid';
  form.elements.consent.checked = true;
  d.querySelector('.h-captcha').append(d.createElement('iframe'));
  const response = d.createElement('textarea'); response.name = 'h-captcha-response'; response.value = 'offline-test-only'; form.append(response);
  form.dispatchEvent(new w.Event('submit', { bubbles: true, cancelable: true }));
  assert.equal(d.querySelector('.tf-submit').disabled, true);
  assert.match(d.querySelector('#tf-status').textContent, /Sending/);
  await tick();
  assert.equal(request.url, 'https://api.web3forms.com/submit');
  assert.equal(request.options.method, 'POST');
  assert.equal(request.options.body.get('email'), 'tester@example.invalid');
  assert.equal(request.options.body.get('subject'), form.elements.subject.value);
  assert.equal(request.options.body.get('access_key'), form.elements.access_key.value);
  assert.match(request.options.body.get('consent'), /Consented/);
  assert.match(d.querySelector('#tf-status').textContent, /Thanks/);
  assert.equal(form.elements.email.value, '');
  dom.window.close();
});

for (const mode of ['server', 'network']) test(`signup ${mode} failure permits retry`, async () => {
  const dom = browser(), w = dom.window, d = w.document;
  w.fetch = async () => {
    if (mode === 'network') throw new Error('offline');
    return { ok: false, json: async () => ({ success: false, message: 'Please retry' }) };
  };
  d.querySelector('#tf-form').dispatchEvent(new w.Event('submit', { bubbles: true, cancelable: true }));
  await tick();
  assert.equal(d.querySelector('.tf-submit').disabled, false);
  assert.equal(d.querySelector('#tf-status').className, 'tf-status err');
  assert.match(d.querySelector('#tf-status').textContent, mode === 'network' ? /Network/ : /retry/);
  dom.window.close();
});

test('every local link, fragment and image resolves on all entry pages', () => {
  for (const page of ['index.html', 'platform.html', 'labs.html', 'classic.html', 'product-as-code.html', 'controls-as-code.html', 'ai-as-code.html']) {
    const d = new JSDOM(read(page)).window.document;
    const ids = [...d.querySelectorAll('[id]')].map(el => el.id);
    assert.equal(new Set(ids).size, ids.length, `${page}: duplicate IDs`);
    for (const el of d.querySelectorAll('[src], a[href], link[href]')) {
      const target = el.getAttribute('src') || el.getAttribute('href');
      if (!target || /^(https?:|mailto:|data:)/.test(target)) continue;
      const [fileWithQuery, fragment] = target.split('#');
      const file = fileWithQuery.split('?')[0];
      assert.ok(fs.existsSync(path.join(site, file || page)), `${page}: missing ${file}`);
      if (fragment && /\.html$/.test(file || page)) {
        const linked = new JSDOM(read(file || page)).window.document;
        assert.ok(linked.getElementById(fragment), `${page}: missing ${target}`);
      }
    }
  }
});

test('public inventory is generated by the canonical admin catalog and includes every module once', () => {
  const tmp = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'ob-web-catalog-'));
  try {
    const catalogPath = path.join(tmp, 'catalog.json');
    execFileSync(process.execPath, [path.join(root, 'openbank-admin-ui/scripts/generate-catalog.mjs'), '--out', catalogPath]);
    execFileSync('python3', [path.join(root, 'openbank-infra/web/generate-public-catalog.py'), '--catalog', catalogPath, '--check']);
    const expected = JSON.parse(fs.readFileSync(catalogPath)).services.map(s => s.short).sort();
    const d = new JSDOM(read('platform.html')).window.document;
    const actual = [...d.querySelectorAll('.module-list li > a:first-child')].map(el => el.textContent).sort();
    assert.deepEqual(actual, expected);
    assert.equal(d.querySelectorAll('[data-count], [data-cov]').length, 0);
  } finally { fs.rmSync(tmp, { recursive: true, force: true }); }
});

test('catalog search finds treasury, handles no results and restores the full inventory', () => {
  const dom = new JSDOM(read('platform.html'), { runScripts: 'outside-only' });
  const w = dom.window, d = w.document;
  w.eval(read('catalog.js'));
  const search = d.querySelector('#module-search');
  const filter = query => { search.value = query; search.dispatchEvent(new w.Event('input')); };
  filter('treasury');
  assert.equal(d.getElementById('treasury').hidden, false);
  assert.equal(d.getElementById('payments').hidden, true);
  filter('nothing-matches-this-query');
  assert.match(d.querySelector('#catalog-status').textContent, /^0 modules/);
  filter('');
  assert.equal(d.querySelectorAll('.domain-group[hidden]').length, 0);
  dom.window.close();
});


test('indexable pages expose unique canonical metadata and matching structured data', () => {
  const pages = ['index.html', 'platform.html', 'labs.html', 'product-as-code.html', 'controls-as-code.html', 'ai-as-code.html'];
  const urls = pages.map(page => page === 'index.html' ? 'https://open-bank.tech/' : `https://open-bank.tech/${page}`);
  const titles = new Set();
  for (const [index, page] of pages.entries()) {
    const d = new JSDOM(read(page)).window.document;
    const url = urls[index];
    const title = d.title;
    assert.ok(title.includes('OpenBank'), `${page}: title identifies the project`);
    assert.ok(!titles.has(title), `${page}: unique title`);
    titles.add(title);
    assert.equal(d.querySelector('link[rel=canonical]')?.href, url);
    assert.equal(d.querySelector('meta[property="og:url"]')?.content, url);
    assert.equal(d.querySelector('meta[property="og:title"]')?.content, title);
    assert.equal(d.querySelector('meta[property="og:type"]')?.content, 'website');
    assert.equal(d.querySelector('meta[name="twitter:card"]')?.content, 'summary_large_image');
    const description = d.querySelector('meta[name=description]')?.content;
    assert.ok(description && description.length > 100, `${page}: descriptive snippet`);
    assert.equal(d.querySelector('meta[property="og:description"]')?.content, description);
    const image = d.querySelector('meta[property="og:image"]')?.content;
    assert.ok(image?.startsWith('https://open-bank.tech/assets/'));
    assert.ok(fs.existsSync(path.join(site, new URL(image).pathname)));
    const graph = JSON.parse(d.querySelector('script[type="application/ld+json"]')?.textContent);
    const nodes = graph['@graph'] || [graph];
    assert.ok(nodes.some(node => node['@type'] === 'WebPage' && node.url === url && node.name === title));
  }
  const sitemap = read('sitemap.xml');
  for (const url of urls) assert.ok(sitemap.includes(`<loc>${url}</loc>`));
  assert.ok(!sitemap.includes('classic.html'));
  assert.match(read('classic.html'), /<meta name="robots" content="noindex, follow"/);
  assert.match(read('robots.txt'), /Sitemap: https:\/\/open-bank.tech\/sitemap.xml/);
});

function brandBrowser({ reduced = false, rejectPlayback = false } = {}) {
  const dom = new JSDOM(read('index.html'), { runScripts: 'outside-only' });
  const film = dom.window.document.querySelector('.brand-film');
  const calls = { play: 0, pause: 0, load: 0 };
  const media = { matches: reduced, addEventListener(type, listener) { this.change = listener; } };
  dom.window.matchMedia = () => media;
  film.play = () => { calls.play++; return rejectPlayback ? Promise.reject(new Error('Autoplay blocked')) : Promise.resolve(); };
  film.pause = () => { calls.pause++; };
  film.load = () => { calls.load++; };
  dom.window.eval(read('brand.js'));
  return { dom, film, calls, media };
}

test('current pages share the canonical identity and retain the header-only animation', () => {
  for (const page of ['index.html', 'platform.html', 'labs.html', 'product-as-code.html', 'controls-as-code.html', 'ai-as-code.html']) {
    const dom = new JSDOM(read(page)), d = dom.window.document;
    assert.equal(d.querySelectorAll('.brand-film').length, 1);
    assert.ok(d.querySelector('link[rel="stylesheet"][href^="styles.css?v="]'), 'returning visitors must receive the header sizing styles');
    const film = d.querySelector('header .brand-film');
    assert.ok(film);
    assert.equal(film.closest('a').getAttribute('aria-label'), 'OpenBank home');
    assert.equal(film.hasAttribute('loop'), false);
    assert.equal(film.hasAttribute('autoplay'), false);
    assert.equal(film.hasAttribute('muted'), true);
    assert.equal(film.hasAttribute('playsinline'), true);
    assert.ok(fs.existsSync(path.join(site, film.getAttribute('poster'))));
    assert.equal(d.querySelector('footer .brand img').getAttribute('src'), 'assets/lipa-lockup-light.svg');
    assert.equal(d.querySelectorAll('script[src="brand.js"]').length, 1);
    if (page === 'index.html') {
      assert.equal(d.querySelector('.hero-art img').getAttribute('src'), 'assets/explorers-lipa.webp');
      assert.equal(d.querySelector('.crew').getAttribute('src'), 'assets/agent-crew.webp');
    }
    dom.window.close();
  }
  assert.ok(!read('classic.html').includes('brand-film'));
});

test('logo plays once and restores the poster when reduced motion is enabled', () => {
  const { dom, calls, media } = brandBrowser();
  assert.deepEqual(calls, { play: 1, pause: 0, load: 0 });
  media.matches = true;
  media.change();
  assert.deepEqual(calls, { play: 1, pause: 1, load: 1 });
  dom.window.close();
});

test('reduced motion keeps the completed-logo poster without starting playback', () => {
  const { dom, calls } = brandBrowser({ reduced: true });
  assert.deepEqual(calls, { play: 0, pause: 0, load: 0 });
  dom.window.close();
});

test('blocked autoplay falls back to the completed-logo poster', async () => {
  const { dom, calls } = brandBrowser({ rejectPlayback: true });
  await tick();
  assert.deepEqual(calls, { play: 1, pause: 0, load: 1 });
  dom.window.close();
});

// Cover every entry page: favicon/share images and embedded art are part of a brand release.
test('all entry pages use current brand assets, including archive and social metadata', () => {
  const legacy = /(?:ob-mark-complete\.svg|openbank-logo\.png|explorers-hero\.webp|openbank-labs-kernel\.jpg|app-screen(?:-current)?\.png)/;
  for (const page of ['index.html', 'platform.html', 'labs.html', 'classic.html', 'product-as-code.html', 'controls-as-code.html', 'ai-as-code.html']) {
    const html = read(page), d = new JSDOM(html).window.document;
    assert.doesNotMatch(html, legacy, page);
    assert.ok(d.querySelector('link[rel="icon"][type="image/svg+xml"]'));
    assert.ok(d.querySelector('link[rel="icon"][sizes="32x32"]'));
    assert.equal(d.querySelector('link[rel="apple-touch-icon"]').getAttribute('href'), 'assets/lipa-icon-180.png');
    const share = new URL(d.querySelector('meta[property="og:image"]').content);
    assert.equal(share.origin, 'https://open-bank.tech');
    assert.ok(fs.existsSync(path.join(site, share.pathname)));
  }
  const d = new JSDOM(read('index.html')).window.document;
  assert.equal(d.querySelector('.phone-stage img').getAttribute('src').split('?')[0], 'assets/app-iphone-20261024.png');
  assert.match(d.querySelector('.phone-figure figcaption').textContent, /Actual app render.*synthetic demo data.*iOS Simulator/);
});

// Exactly the branded files added by #13754, excluding the manifest itself.
// Unchanged header-film/crew/control-room assets are outside this PR's provenance scope.
const brandedAssetInventory = [
  '../favicon.ico', 'app-cards-lipa.png', 'app-iphone-20261024.png',
  'explorers-lipa.webp', 'labs-kernel-lipa.webp',
  'lipa-icon-180.png', 'lipa-icon-192.png', 'lipa-icon-32.png', 'lipa-icon-512.png',
  'lipa-icon.svg', 'lipa-lockup-dark.svg', 'lipa-lockup-light.svg', 'lipa-mark.svg',
].sort();

function assertBrandProvenance(assets, readAsset = file => fs.readFileSync(path.join(site, 'assets', file))) {
  const names = assets.map(asset => asset.file);
  assert.equal(new Set(names).size, names.length, 'Duplicate provenance file');
  assert.deepEqual([...names].sort(), brandedAssetInventory, 'Brand provenance must cover exactly the PR inventory');
  for (const asset of assets) {
    assert.match(asset.sha256, /^[a-f0-9]{64}$/, `Invalid SHA-256: ${asset.file}`);
    const actual = require('node:crypto').createHash('sha256').update(readAsset(asset.file)).digest('hex');
    assert.equal(actual, asset.sha256, `Unreviewed brand asset change: ${asset.file}`);
  }
}

test('brand provenance covers every proposed asset and root favicon exactly once with matching bytes', () => {
  assertBrandProvenance(JSON.parse(read('assets/lipa-provenance.json')).assets);
});

test('brand provenance rejects omissions, duplicates, unexpected assets, missing files and changed bytes', () => {
  // Synthetic validator fixture, not a claim of approved provenance for missing records.
  const complete = brandedAssetInventory.map(file => ({ file, sha256: require('node:crypto')
    .createHash('sha256').update(fs.readFileSync(path.join(site, 'assets', file))).digest('hex') }));
  assert.doesNotThrow(() => assertBrandProvenance(complete));
  for (const omitted of brandedAssetInventory) {
    assert.throws(() => assertBrandProvenance(complete.filter(asset => asset.file !== omitted)),
      /must cover exactly the PR inventory/, `Omission must fail: ${omitted}`);
  }
  assert.throws(() => assertBrandProvenance([...complete, complete[0]]), /Duplicate provenance file/);
  assert.throws(() => assertBrandProvenance([...complete, { file: 'unreviewed.png', sha256: '0'.repeat(64) }]),
    /must cover exactly the PR inventory/);
  assert.throws(() => assertBrandProvenance(complete, () => { throw new Error('Missing asset'); }), /Missing asset/);
  assert.throws(() => assertBrandProvenance(complete, () => Buffer.from('changed bytes')), /Unreviewed brand asset change/);
});

// A fresh HTML response cannot invalidate a still-fresh browser cache entry at the old image URL.
test('app preview URL changes with the recorded image content hash for cached visitors', () => {
  const asset = JSON.parse(read('assets/lipa-provenance.json')).assets
    .find(asset => asset.file === 'app-iphone-20261024.png');
  const digest = require('node:crypto').createHash('sha256')
    .update(fs.readFileSync(path.join(site, 'assets', asset.file))).digest('hex');
  assert.equal(asset.sha256, digest);
  const expected = `assets/${asset.file}?v=${digest.slice(0, 12)}`;
  for (const page of ['index.html', 'classic.html']) {
    const document = new JSDOM(read(page)).window.document;
    assert.equal(document.querySelector('img[src*="app-iphone-20261024.png"]').getAttribute('src'), expected, page);
  }
});
