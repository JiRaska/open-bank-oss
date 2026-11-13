// SPDX-License-Identifier: Apache-2.0
// Local marketing illustrations. No service calls, approvals or transactions occur here.
function annualSavingsIllustration(baseRate, bonusEligible) {
  const rate = Number(baseRate);
  if (!Number.isFinite(rate) || rate < 0 || rate > 5) throw new RangeError('Rate must be between 0 and 5 percent');
  const base = Math.round(rate * 10000) / 100;
  const bonus = bonusEligible ? 100 : 0;
  return { base, bonus, total: base + bonus };
}

if (typeof module !== 'undefined' && module.exports) module.exports = { annualSavingsIllustration };

if (typeof document !== 'undefined') (() => {
  const root = document.querySelector('.bac');
  if (!root) return;
  const $ = selector => root.querySelector(selector);
  const all = selector => [...root.querySelectorAll(selector)];
  const motion = matchMedia('(prefers-reduced-motion: reduce)');
  const euro = new Intl.NumberFormat('en-IE', { style: 'currency', currency: 'EUR' });
  const timers = new Set();
  let completeCurrent = null;
  root.classList.add('bac-ready');
  all('button[id^="ob-play-"]').forEach(button => { button.hidden = false; });

  function cancelJourney() {
    timers.forEach(clearTimeout);
    timers.clear();
    completeCurrent = null;
    all('button[data-running]').forEach(button => {
      button.disabled = false;
      button.removeAttribute('data-running');
    });
    all('.ob-flash').forEach(node => node.classList.remove('ob-flash'));
  }

  function journey(button, nodes, messages, status, done) {
    cancelJourney();
    button.disabled = true;
    button.dataset.running = 'true';
    const step = index => {
      nodes.forEach((node, i) => {
        node.dataset.active = String(i === index);
        node.classList.remove('ob-flash');
      });
      if (!motion.matches) nodes[index].classList.add('ob-flash');
      status.textContent = messages[index];
      if (index === nodes.length - 1) {
        button.disabled = false;
        button.removeAttribute('data-running');
        completeCurrent = null;
        if (done) done();
      }
    };
    completeCurrent = () => step(nodes.length - 1);
    if (motion.matches) { completeCurrent(); return; }
    nodes.forEach((_, i) => {
      if (i === 0) step(0);
      else {
        const timer = setTimeout(() => { timers.delete(timer); step(i); }, i * 850);
        timers.add(timer);
      }
    });
  }

  motion.addEventListener('change', () => {
    if (motion.matches && completeCurrent) {
      const finish = completeCurrent;
      cancelJourney();
      finish();
    }
  });
  window.addEventListener('pagehide', cancelJourney);

  const rate = $('#ob-v2-rate'), bonus = $('#ob-v2-bonus');
  if (rate && bonus) {
    const update = () => {
      const result = annualSavingsIllustration(rate.value, bonus.checked);
      $('#ob-v2-rate-label').textContent = Number(rate.value).toFixed(2) + '%';
      $('#ob-v2-code-rate').textContent = (Number(rate.value) / 100).toFixed(4);
      $('#ob-v2-total').textContent = euro.format(result.total);
      $('#ob-v2-base').textContent = 'Base ' + euro.format(result.base);
      $('#ob-v2-bonus-amount').textContent = 'Bonus ' + euro.format(result.bonus);
      $('#ob-v2-base-bar').style.width = (result.base / 600 * 100) + '%';
      $('#ob-v2-bonus-bar').style.width = (result.bonus / 600 * 100) + '%';
    };
    rate.addEventListener('input', update);
    bonus.addEventListener('change', update);
    update();
  }

  const flows = [
    ['home', ['Start with a clearly defined product change.', 'Explore how the proposed rule changes the outcome.', 'Bring the proposal and its evidence to human review.', 'Keep the decision connected to its definition and evidence.']],
    ['product', ['The proposed rate starts in a versioned definition.', 'Scenarios explore the rate and bonus conditions.', 'Results become part of the review context.', 'The proposal is ready for a person to examine.']],
  ];
  for (const [name, messages] of flows) {
    const button = $('#ob-play-' + name);
    if (button) button.addEventListener('click', () => journey(button, all('#ob-' + name + '-flow .ob-step'), messages, $('#ob-' + name + '-status')));
  }

  const controls = {
    compliance: {
      title: 'SERVICE DECLARATION / GOVERNANCE CHECK',
      steps: ['A service declares its data handling.', 'A check compares the declaration with code.', 'A mismatch becomes visible.', 'A reviewer examines the result.'],
      subject: 'service declaration', check: 'implementation match', question: 'does the code agree?', next: 'human review',
      source: 'openbank-admin-ui/src/test/generate-governance.test.ts',
    },
    aml: {
      title: 'AML CASE / DECISION WITH CONTEXT',
      steps: ['A case is opened for review.', 'An authorised person examines the case.', 'A blocking decision requires a reason.', 'Decision details remain with the case.'],
      subject: 'AML case', check: 'decision requirements', question: 'is a reason recorded?', next: 'inspect case history',
      source: 'openbank-aml-service/src/main/kotlin/com/openbank/aml/domain/model/AmlCase.kt',
    },
    fraud: {
      title: 'THRESHOLD CHANGE / SEPARATE REVIEW',
      steps: ['A proposer submits a new threshold.', 'The proposal has a version and effective date.', 'A different reviewer must approve.', 'Activation respects the effective date.'],
      subject: 'threshold proposal', check: 'maker ≠ checker', question: 'who approved the change?', next: 'effective date check',
      source: 'openbank-fraud-service/src/test/kotlin/com/openbank/fraud/application/usecase/FraudThresholdActivationServiceTest.kt',
    },
  };
  let control = 'compliance';
  all('[data-control]').forEach(button => button.addEventListener('click', () => {
    cancelJourney();
    control = button.dataset.control;
    const example = controls[control];
    all('[data-control]').forEach(other => other.setAttribute('aria-pressed', String(other === button)));
    $('#ob-control-title').textContent = example.title;
    example.steps.forEach((text, i) => { $('#ob-ctrl-' + (i + 1)).textContent = text; });
    for (const key of ['subject', 'check', 'question', 'next']) $('#ob-rec-' + key).textContent = example[key];
    $('#ob-control-source').href = 'https://github.com/JiRaska/open-bank-oss/blob/main/' + example.source;
    $('#ob-control-status').textContent = example.steps[0];
    all('#ob-control-flow .ob-evidence-node').forEach((node, i) => { node.dataset.active = String(i === 0); });
  }));
  const controlButton = $('#ob-play-control');
  if (controlButton) controlButton.addEventListener('click', () => journey(controlButton, all('#ob-control-flow .ob-evidence-node'), controls[control].steps, $('#ob-control-status')));

  let request = 'allowed';
  all('[data-request]').forEach(button => button.addEventListener('click', () => {
    cancelJourney();
    request = button.dataset.request;
    const allowed = request === 'allowed';
    all('[data-request]').forEach(other => other.setAttribute('aria-pressed', String(other === button)));
    all('#ob-ai-flow .ob-ai-box').forEach(node => { node.dataset.active = 'false'; });
    $('#ob-ai-request').textContent = allowed ? 'request: permitted_tool' : 'request: restricted_tool';
    $('#ob-ai-outcome-title').textContent = allowed ? 'The tool can run.' : 'The request stops.';
    $('#ob-ai-outcome-detail').textContent = allowed ? 'execution stays within authority' : 'no tool execution';
    $('#ob-ai-arrow').textContent = allowed ? '→' : '×';
    $('#ob-ai-log-tag').textContent = 'READY TO EXPLORE';
    $('#ob-ai-log-text').textContent = 'request  →  policy evaluation\noutcome  →  execution or denial\nevidence →  an inspectable audit record';
    $('#ob-ai-status').textContent = 'Select a scenario and follow the request.';
  }));
  const aiButton = $('#ob-play-ai');
  if (aiButton) aiButton.addEventListener('click', () => {
    const allowed = request === 'allowed';
    journey(aiButton, all('#ob-ai-flow .ob-ai-box'), ['The agent submits a tool request.', 'Policy checks the request against its authority.', allowed ? 'Permitted request. Tool execution can proceed.' : 'Denied request. The tool does not execute.'], $('#ob-ai-status'), () => {
      $('#ob-ai-log-tag').textContent = 'ILLUSTRATIVE RESULT';
      $('#ob-ai-log-text').textContent = 'policy.decision  ' + (allowed ? 'ALLOW' : 'DENY') + '\ntool.execution   ' + (allowed ? 'PERMITTED' : 'NOT EXECUTED') + '\naudit.outcome    ' + (allowed ? 'ALLOWED REQUEST' : 'DENIED REQUEST');
    });
  });
})();
