const $ = (id) => document.getElementById(id);

const TYPE_HINTS = {
  notification: 'A one-way notice that lands in people’s notifications.',
  message: 'A regular message in people’s inbox.',
  task: 'Asks people to approve or fill something in — responses show up on the right.',
};
const SEND_LABELS = { notification: 'Send announcement', message: 'Send message', task: 'Send task' };
const TYPE_LABELS = { notification: 'Announcement', message: 'Message', task: 'Task' };
const AUDIENCE_HINTS = {
  everyone: 'Everyone signed in to the portal will see this.',
  teams: 'Only members of the teams you list will see it.',
  roles: 'Only people with one of these roles will see it.',
  people: 'Only these individual people will see it.',
};
const CHIP_PLACEHOLDERS = {
  teams: 'Team key, e.g. back-office — press Enter to add',
  roles: 'Role, e.g. admin — press Enter to add',
  people: 'Username or person id — press Enter to add',
};
const CHIP_NAMES = { teams: 'team', roles: 'role', people: 'person' };
const KIND_HINTS = {
  approval: 'People just approve or deny — no form to fill in.',
  collect: 'People fill in a form; you will see every response here.',
};
const FRIENDLY_ERRORS = {
  'v must be 1': 'this message uses an unsupported format version',
  'type must be notification|message|task': 'unknown message type',
  'moduleKey must be kebab-case': 'the module key must be lowercase letters and dashes',
  'id must be a UUID': 'the message id is invalid',
  'audience object required': 'no recipients selected',
  'title.en required': 'a title is required',
  'body.en required': 'a message text is required',
};
const OUTCOME_VERBS = {
  approve: 'approved your task',
  approved: 'approved your task',
  deny: 'denied your task',
  denied: 'denied your task',
  submit: 'submitted a response',
  submitted: 'submitted a response',
  skip: 'skipped a task',
  skipped: 'skipped a task',
};
const Q_TYPES = [
  ['text', 'Text'],
  ['long', 'Long text'],
  ['number', 'Number'],
  ['integer', 'Whole number'],
  ['boolean', 'Yes / no'],
  ['choice', 'Choice'],
];

function defaultState() {
  return {
    type: 'notification',
    title: '',
    body: '',
    audienceMode: 'everyone',
    chips: [],
    kind: 'approval',
    completion: 'any',
    claim: false,
    deadline: '',
    questions: [],
    useTemplate: false,
    tplKey: '',
    tplVersion: 1,
    completionEvent: '',
  };
}

const PRESETS = {
  1: {
    state: {
      type: 'task',
      title: 'Expense approval',
      body: 'Please approve the attached expense report.',
      kind: 'approval',
      completion: 'any',
    },
  },
  2: {
    state: {
      type: 'task',
      title: 'Daily hours report',
      body: 'Please report your hours for today.',
      kind: 'collect',
      completion: 'each',
      completionEvent: 'hours.submitted',
      deadlineIn: 86400000,
      questions: [
        { label: 'Hours worked', type: 'number', required: true, min: '0', max: '24' },
        { label: 'Comment', type: 'long', required: false },
      ],
    },
  },
  3: {
    state: {
      type: 'task',
      title: 'Off-site RSVP',
      body: 'Will you attend the off-site?',
      kind: 'collect',
      completion: 'each',
      completionEvent: 'rsvp.submitted',
      deadlineIn: 259200000,
      questions: [
        { label: 'Can you attend?', type: 'boolean', required: true },
        { label: 'Meal preference', type: 'choice', required: true, choices: 'meat, fish, veg' },
      ],
    },
  },
  4: {
    state: {
      type: 'task',
      title: 'Loaner laptop request',
      body: 'One person of the pool: please take this and provide details.',
      kind: 'collect',
      completion: 'any',
      completionEvent: 'loan.requested',
      claim: true,
      deadlineIn: 604800000,
      questions: [
        { label: 'Justification', type: 'long', required: true, max: '200' },
        { label: 'Days', type: 'integer', required: true, min: '1', max: '30' },
      ],
    },
  },
  5: {
    state: {
      type: 'notification',
      title: 'Notice for the back-office team',
      body: 'Only members of the back-office team will see this.',
      audienceMode: 'teams',
      chips: ['back-office'],
    },
  },
  6: {
    state: {
      type: 'task',
      title: 'Expense report (saved form)',
      body: 'The form comes from a saved template in the portal.',
      kind: 'collect',
      completion: 'any',
      completionEvent: 'expense.reported',
      useTemplate: true,
      tplKey: 'expense-approval',
      tplVersion: 1,
    },
  },
  bad: {
    raw: {
      v: 2,
      type: 'notification',
      title: { en: 'Malformed demo' },
      body: { en: 'v=2 → rejected on purpose.' },
    },
  },
};

let state = defaultState();
let filter = 'all';
const rows = [];
const sentIndex = new Map();

function slugify(s) {
  return String(s || '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 64);
}

function parseChoices(q) {
  return String(q.choices || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
}

function numOrNull(v) {
  if (v === '' || v == null) return null;
  const n = Number(v);
  return Number.isNaN(n) ? null : n;
}

function uniqueFieldName(label, i, used) {
  let name = slugify(label) || `field-${i + 1}`;
  if (used.has(name)) {
    let n = 2;
    while (used.has(`${name}-${n}`)) n += 1;
    name = `${name}-${n}`;
  }
  used.add(name);
  return name;
}

function questionToField(q, i, used) {
  const field = {
    name: uniqueFieldName(q.label, i, used),
    required: !!q.required,
    label: { en: String(q.label).slice(0, 200) },
  };
  const min = numOrNull(q.min);
  const max = numOrNull(q.max);
  switch (q.type) {
    case 'long': {
      field.multiline = true;
      field.schema = { type: 'string', maxLength: max ?? 500 };
      break;
    }
    case 'number':
    case 'integer': {
      field.schema = { type: q.type === 'integer' ? 'integer' : 'number' };
      if (min != null) field.schema.minimum = min;
      if (max != null) field.schema.maximum = max;
      break;
    }
    case 'boolean':
      field.schema = { type: 'boolean' };
      break;
    case 'choice':
      field.schema = { type: 'string', enum: parseChoices(q) };
      break;
    default:
      field.schema = { type: 'string', maxLength: max ?? 200 };
  }
  return field;
}

function buildAudience() {
  if (state.audienceMode === 'everyone') return { allUsers: true };
  const key = { teams: 'groups', roles: 'roles', people: 'users' }[state.audienceMode];
  return { [key]: state.chips.map((c) => c.trim()).filter(Boolean) };
}

function autoCompletionEvent() {
  const word = slugify(state.title).split('-').filter(Boolean)[0] || '';
  const first = /^[a-z][a-z0-9]*$/.test(word) ? word : 'task';
  return `${first}.submitted`;
}

function buildTask() {
  const task = {
    kind: state.kind === 'approval' ? 'approval' : 'collect',
    completion: state.claim ? 'any' : state.completion,
  };
  if (task.kind === 'collect') {
    task.completionEvent = state.completionEvent || autoCompletionEvent();
  }
  if (state.deadline) {
    const d = new Date(state.deadline);
    if (!Number.isNaN(d.getTime())) task.expiresAt = d.toISOString();
  }
  if (state.claim) task.claim = { enabled: true, mode: 'single' };
  if (task.kind === 'collect') {
    if (state.useTemplate) {
      task.template = { key: state.tplKey.trim(), version: Number(state.tplVersion) || 1 };
    } else if (state.questions.length > 0) {
      const used = new Set();
      task.fields = state.questions.map((q, i) => questionToField(q, i, used));
    }
  }
  return task;
}

function parseJson(text, what) {
  try {
    return JSON.parse(text);
  } catch (err) {
    throw new Error(`the ${what} JSON is not valid — ${err.message}`);
  }
}

function buildEnvelope(useAdv) {
  const env = {
    v: 1,
    id: crypto.randomUUID(),
    createdAt: new Date().toISOString(),
    type: state.type,
    moduleKey: useAdv ? $('advModuleKey').value.trim() || 'sample-sender' : 'sample-sender',
    title: { en: state.title.trim() },
    body: { en: state.body },
    audience: useAdv ? parseJson($('advAudience').value, 'audience') : buildAudience(),
  };
  if (state.type === 'task') {
    const task = useAdv ? parseJson($('advTask').value, 'task') : buildTask();
    if (task && typeof task === 'object' && Object.keys(task).length > 0) env.task = task;
  }
  if (useAdv) {
    const sections = parseJson($('advSections').value, 'sections');
    if (Array.isArray(sections) && sections.length > 0) env.body.sections = sections;
  }
  return env;
}

function validate() {
  const errs = [];
  if (!state.title.trim()) errs.push('Add a title first.');
  if (state.audienceMode !== 'everyone' && buildAudience()[{ teams: 'groups', roles: 'roles', people: 'users' }[state.audienceMode]].length === 0) {
    errs.push(`Add at least one ${CHIP_NAMES[state.audienceMode]}.`);
  }
  if (state.type === 'task' && state.kind === 'collect') {
    if (state.useTemplate) {
      if (!state.tplKey.trim()) errs.push('Enter the form name of the saved form.');
    } else if (state.questions.length === 0) {
      errs.push('Add at least one question, or switch on “Use a saved form instead”.');
    } else {
      state.questions.forEach((q, i) => {
        const where = q.label.trim() ? `“${q.label.trim()}”` : `Question ${i + 1}`;
        if (!q.label.trim()) errs.push(`Question ${i + 1} needs a label.`);
        if (q.type === 'choice' && parseChoices(q).length < 2) {
          errs.push(`${where} needs at least two options.`);
        }
        if (q.type === 'number' || q.type === 'integer') {
          const min = numOrNull(q.min);
          const max = numOrNull(q.max);
          if (min != null && max != null && max < min) {
            errs.push(`${where}: the minimum cannot be bigger than the maximum.`);
          }
        }
      });
    }
  }
  return errs;
}

function friendlyServer(msg) {
  return String(msg)
    .split('; ')
    .map((part) => FRIENDLY_ERRORS[part] || part)
    .join('; ');
}

function short(v, max = 60) {
  let s;
  if (typeof v === 'string') s = v;
  else {
    try {
      s = JSON.stringify(v);
    } catch {
      s = String(v);
    }
    if (s == null) s = String(v);
  }
  return s.length > max ? `${s.slice(0, max - 1)}…` : s;
}

function friendlyResponder(sub) {
  if (typeof sub !== 'string' || !sub) return 'Someone';
  return /^[0-9a-f]{8}-[0-9a-f-]{28}$/i.test(sub) ? `${sub.slice(0, 8)}…` : sub;
}

function audienceLabel(aud) {
  if (!aud || typeof aud !== 'object') return 'no one';
  if (aud.allUsers) return 'everyone';
  const parts = [];
  const plural = (n, one, many) => `${n} ${n > 1 ? many : one}`;
  if (Array.isArray(aud.groups) && aud.groups.length) parts.push(plural(aud.groups.length, 'team', 'teams'));
  if (Array.isArray(aud.roles) && aud.roles.length) parts.push(plural(aud.roles.length, 'role', 'roles'));
  if (Array.isArray(aud.users) && aud.users.length) parts.push(plural(aud.users.length, 'person', 'people'));
  return parts.join(', ') || 'no one';
}

function toLocalInput(d) {
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

function setResult(text, ok) {
  const el = $('lastResult');
  el.textContent = text;
  el.classList.toggle('ok', ok === true);
  el.classList.toggle('bad', ok === false);
}

function setStatus(text, ok) {
  const el = $('status');
  el.textContent = text;
  el.classList.toggle('ok', ok === true);
  el.classList.toggle('bad', ok === false);
}

function clearRaw() {
  if ($('advRaw').value) $('advRaw').value = '';
  $('rawHint').hidden = true;
}

function clearPresetHighlight() {
  document.querySelectorAll('#presets .pill').forEach((p) => p.classList.remove('on'));
}

function markDirty() {
  clearPresetHighlight();
  if ($('advRaw').value) clearRaw();
}

function renderSeg(el, value) {
  el.querySelectorAll('button').forEach((b) => b.classList.toggle('on', b.dataset.value === value));
}

function renderChips() {
  const box = $('chips');
  box.innerHTML = '';
  state.chips.forEach((c, i) => {
    const chip = document.createElement('span');
    chip.className = 'chip';
    const txt = document.createElement('span');
    txt.textContent = c;
    const del = document.createElement('button');
    del.type = 'button';
    del.textContent = '×';
    del.title = 'Remove';
    del.addEventListener('click', () => {
      state.chips.splice(i, 1);
      markDirty();
      renderChips();
      syncAdvanced();
    });
    chip.append(txt, del);
    box.append(chip);
  });
}

function renderQuestions() {
  const box = $('questions');
  box.innerHTML = '';
  state.questions.forEach((q, i) => {
    const row = document.createElement('div');
    row.className = 'qrow';

    const label = document.createElement('input');
    label.className = 'q-label';
    label.autocomplete = 'off';
    label.placeholder = `Question ${i + 1} e.g. Hours worked`;
    label.value = q.label;
    label.addEventListener('input', () => {
      q.label = label.value;
    });

    const type = document.createElement('select');
    type.className = 'q-type';
    Q_TYPES.forEach(([v, t]) => {
      const o = document.createElement('option');
      o.value = v;
      o.textContent = t;
      type.append(o);
    });
    type.value = q.type;
    type.addEventListener('change', () => {
      q.type = type.value;
      markDirty();
      renderQuestions();
    });

    const del = document.createElement('button');
    del.type = 'button';
    del.className = 'q-del';
    del.textContent = '×';
    del.title = 'Remove question';
    del.addEventListener('click', () => {
      state.questions.splice(i, 1);
      markDirty();
      renderQuestions();
    });

    row.append(label, type, del);

    if (q.type === 'choice') {
      const extra = document.createElement('input');
      extra.className = 'q-extra';
      extra.autocomplete = 'off';
      extra.placeholder = 'Options, comma separated e.g. meat, fish, veg';
      extra.value = q.choices || '';
      extra.addEventListener('input', () => {
        q.choices = extra.value;
      });
      row.append(extra);
    }

    if (q.type === 'number' || q.type === 'integer') {
      const nums = document.createElement('div');
      nums.className = 'q-nums';
      [
        ['min', 'Minimum'],
        ['max', 'Maximum'],
      ].forEach(([k, ph]) => {
        const wrap = document.createElement('div');
        const lab = document.createElement('label');
        lab.textContent = ph;
        const inp = document.createElement('input');
        inp.type = 'number';
        inp.value = q[k] ?? '';
        inp.addEventListener('input', () => {
          q[k] = inp.value;
        });
        wrap.append(lab, inp);
        nums.append(wrap);
      });
      row.append(nums);
    }

    const req = document.createElement('label');
    req.className = 'q-req';
    const cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.checked = !!q.required;
    cb.addEventListener('change', () => {
      q.required = cb.checked;
    });
    req.append(cb, document.createTextNode('required'));
    row.append(req);

    box.append(row);
  });
  $('questionsHint').hidden = state.questions.length > 0;
}

function render() {
  renderSeg($('typeSeg'), state.type);
  renderSeg($('audienceSeg'), state.audienceMode);
  renderSeg($('kindSeg'), state.kind);
  renderSeg($('completionSeg'), state.completion);
  $('typeHint').textContent = TYPE_HINTS[state.type];
  $('audienceHint').textContent = AUDIENCE_HINTS[state.audienceMode];
  $('chipsBox').hidden = state.audienceMode === 'everyone';
  if (state.audienceMode !== 'everyone') $('chipInput').placeholder = CHIP_PLACEHOLDERS[state.audienceMode];
  $('taskBlock').hidden = state.type !== 'task';
  $('kindHint').textContent = KIND_HINTS[state.kind];
  $('claim').checked = state.claim;
  $('deadline').value = state.deadline;
  $('completionSeg').querySelector('[data-value="each"]').disabled = state.claim;
  const needForm = state.type === 'task' && state.kind === 'collect';
  $('questionsBlock').hidden = !needForm || state.useTemplate;
  $('templateBlock').hidden = !needForm || !state.useTemplate;
  $('useTemplate').checked = state.useTemplate;
  $('tplKey').value = state.tplKey;
  $('tplVersion').value = state.tplVersion;
  $('send').textContent = SEND_LABELS[state.type];
  renderChips();
  renderQuestions();
  syncAdvanced();
}

const touched = { audience: false, task: false, sections: false };

function syncAdvanced() {
  if (!$('advancedBlock').hidden) refreshAdvancedEditors();
}

function refreshAdvancedEditors(force = false) {
  if (force || !touched.audience) {
    $('advAudience').value = JSON.stringify(buildAudience(), null, 2);
    touched.audience = false;
  }
  if (force || !touched.task) {
    $('advTask').value = state.type === 'task' ? JSON.stringify(buildTask(), null, 2) : '{}';
    touched.task = false;
  }
  if (force || !touched.sections) {
    $('advSections').value = '[]';
    touched.sections = false;
  }
}

function setAdvanced(open) {
  $('advancedToggle').checked = open;
  $('advancedBlock').hidden = !open;
  if (open) refreshAdvancedEditors(true);
}

function applyPreset(key) {
  const preset = PRESETS[key];
  if (!preset) return;
  clearPresetHighlight();
  clearRaw();
  if (preset.raw) {
    state = defaultState();
    $('title').value = '';
    $('body').value = '';
    setAdvanced(true);
    $('advRaw').value = JSON.stringify(preset.raw, null, 2);
    $('rawHint').hidden = false;
    setResult('', null);
    render();
    document.querySelector(`#presets [data-preset="${key}"]`).classList.add('on');
    return;
  }
  const s = { ...defaultState(), ...preset.state };
  s.chips = [...(preset.state.chips || [])];
  s.questions = (preset.state.questions || []).map((q) => ({ ...q }));
  if (preset.state.deadlineIn) s.deadline = toLocalInput(new Date(Date.now() + preset.state.deadlineIn));
  state = s;
  $('title').value = s.title;
  $('body').value = s.body;
  if ($('advancedToggle').checked) refreshAdvancedEditors(true);
  setResult('', null);
  render();
  document.querySelector(`#presets [data-preset="${key}"]`).classList.add('on');
}

async function send() {
  const useAdv = $('advancedToggle').checked;
  let envelope;
  let transport = useAdv ? $('advTransport').value : 'nats';
  try {
    if (useAdv && $('advRaw').value.trim()) {
      envelope = parseJson($('advRaw').value, 'raw envelope');
      if (typeof envelope !== 'object' || envelope === null || Array.isArray(envelope)) {
        throw new Error('the raw envelope must be a JSON object');
      }
    } else {
      const errs = validate();
      if (errs.length) throw new Error(errs[0]);
      envelope = buildEnvelope(useAdv);
    }
  } catch (err) {
    setResult(`Could not send: ${err.message}`, false);
    return;
  }
  const btn = $('send');
  btn.disabled = true;
  try {
    const res = await fetch(`/send?transport=${transport}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(envelope),
    });
    const payload = await res.json().catch(() => ({}));
    if (res.ok) {
      setResult(`Sent ✓ — over ${transport === 'http' ? 'the HTTP endpoint' : 'NATS'}`, true);
    } else {
      setResult(`Could not send: ${friendlyServer(payload.error || `HTTP ${res.status}`)}`, false);
    }
  } catch (err) {
    setResult(`Could not send: ${short(err.message || String(err), 120)}`, false);
  } finally {
    btn.disabled = false;
  }
}

function categoryOf(kind) {
  if (kind === 'sent') return 'sent';
  if (kind === 'received') return 'responses';
  if (kind === 'rejected' || kind === 'failed' || kind === 'dlq') return 'errors';
  return 'system';
}

function entryToRow(entry) {
  const cat = categoryOf(entry.kind);
  const time = String(entry.at || '').slice(11, 19);
  if (entry.kind === 'sent') {
    const p = entry.payload || {};
    if (p.id) sentIndex.set(p.id, p.title?.en || '');
    return {
      cat,
      cls: 'ok',
      time,
      headline: `You sent “${p.title?.en || 'untitled'}”`,
      meta: `${TYPE_LABELS[p.type] || p.type || '?'} · ${audienceLabel(p.audience)} · ${
        entry.transport === 'http' ? 'HTTP endpoint' : 'NATS'
      }`,
      subject: entry.subject,
      detail: p,
    };
  }
  if (entry.kind === 'received') {
    const p = entry.payload || {};
    const verb = OUTCOME_VERBS[p.outcome] || `responded (${p.outcome ?? 'unknown'})`;
    const context = (p.eventRef && sentIndex.get(p.eventRef)) || '';
    const bits = [];
    if (p.data && typeof p.data === 'object') {
      for (const [k, v] of Object.entries(p.data)) bits.push(`${k}: ${short(v)}`);
    }
    if (p.note) bits.push(`“${short(p.note, 80)}”`);
    return {
      cat,
      cls: 'info',
      time,
      headline: `${friendlyResponder(p.responder)} ${verb}${context ? ` — “${short(context, 80)}”` : ''}`,
      meta: bits.join(' · '),
      subject: entry.subject,
      detail: p,
    };
  }
  if (entry.kind === 'dlq') {
    const p = entry.payload || {};
    return {
      cat,
      cls: 'bad',
      time,
      headline: 'The portal rejected a message',
      meta: p.reason ? friendlyServer(p.reason) : 'the portal’s validation refused it',
      subject: entry.subject,
      detail: p,
    };
  }
  if (entry.kind === 'rejected') {
    return {
      cat,
      cls: 'bad',
      time,
      headline: `Could not send: ${friendlyServer(entry.reason || 'rejected')}`,
      subject: entry.subject,
      detail: entry.payload,
    };
  }
  if (entry.kind === 'failed') {
    return {
      cat,
      cls: 'bad',
      time,
      headline: `Could not send: ${short(entry.reason || 'unknown error', 120)}`,
      subject: entry.subject,
      detail: entry.payload,
    };
  }
  const msg = String(entry.message || '');
  return {
    cat,
    cls: 'system',
    time,
    headline: /fail/i.test(msg) ? `⚠ Connection problem — ${msg}` : 'Live — connected to the message broker',
    subject: null,
    detail: null,
  };
}

function buildRowEl(r) {
  const row = document.createElement('div');
  row.className = `row ${r.cls}`;
  const line = document.createElement('div');
  line.className = 'line';
  const time = document.createElement('span');
  time.className = 'time';
  time.textContent = r.time;
  const headline = document.createElement('span');
  headline.className = 'headline';
  headline.textContent = r.headline;
  line.append(time, headline);
  row.append(line);
  if (r.meta) {
    const meta = document.createElement('span');
    meta.className = 'meta';
    meta.textContent = r.meta;
    row.append(meta);
  }
  if (r.subject) {
    const det = document.createElement('details');
    const sum = document.createElement('summary');
    sum.textContent = 'technical details';
    const pre = document.createElement('pre');
    pre.textContent = `${r.subject}\n${JSON.stringify(r.detail, null, 2)}`;
    det.append(sum, pre);
    row.append(det);
  }
  return row;
}

function renderLog() {
  const log = $('log');
  log.innerHTML = '';
  for (const r of rows) {
    if (filter !== 'all' && r.cat !== filter) continue;
    log.append(buildRowEl(r));
  }
  $('logEmpty').hidden = rows.length > 0;
}

function pushRow(row) {
  rows.unshift(row);
  if (rows.length > 200) rows.pop();
  renderLog();
}

document.querySelectorAll('#presets .pill').forEach((pill) => {
  pill.addEventListener('click', () => applyPreset(pill.dataset.preset));
});

$('typeSeg').addEventListener('click', (e) => {
  const btn = e.target.closest('button');
  if (!btn) return;
  state.type = btn.dataset.value;
  markDirty();
  render();
});

$('audienceSeg').addEventListener('click', (e) => {
  const btn = e.target.closest('button');
  if (!btn) return;
  state.audienceMode = btn.dataset.value;
  markDirty();
  render();
});

$('kindSeg').addEventListener('click', (e) => {
  const btn = e.target.closest('button');
  if (!btn || state.type !== 'task') return;
  state.kind = btn.dataset.value;
  markDirty();
  render();
});

$('completionSeg').addEventListener('click', (e) => {
  const btn = e.target.closest('button');
  if (!btn || btn.disabled || state.type !== 'task') return;
  state.completion = btn.dataset.value;
  markDirty();
  render();
});

$('title').addEventListener('input', (e) => {
  state.title = e.target.value;
  state.completionEvent = '';
  markDirty();
});

$('body').addEventListener('input', (e) => {
  state.body = e.target.value;
  markDirty();
});

['advAudience', 'advTask', 'advSections'].forEach((id, i) => {
  $(id).addEventListener('input', () => {
    touched[['audience', 'task', 'sections'][i]] = true;
  });
});

$('chipInput').addEventListener('keydown', (e) => {
  if (e.key === 'Enter' || e.key === ',') {
    e.preventDefault();
    const v = e.target.value.trim().replace(/,$/, '');
    if (v && !state.chips.includes(v)) {
      state.chips.push(v);
      markDirty();
      renderChips();
      syncAdvanced();
    }
    e.target.value = '';
  } else if (e.key === 'Backspace' && !e.target.value && state.chips.length) {
    state.chips.pop();
    markDirty();
    renderChips();
    syncAdvanced();
  }
});

$('claim').addEventListener('change', (e) => {
  state.claim = e.target.checked;
  if (state.claim) state.completion = 'any';
  markDirty();
  render();
});

$('deadline').addEventListener('change', (e) => {
  state.deadline = e.target.value;
  markDirty();
});

$('addQ').addEventListener('click', () => {
  state.questions.push({ label: '', type: 'text', required: false, min: '', max: '', choices: '' });
  markDirty();
  renderQuestions();
});

$('useTemplate').addEventListener('change', (e) => {
  state.useTemplate = e.target.checked;
  markDirty();
  render();
});

$('tplKey').addEventListener('input', (e) => {
  state.tplKey = e.target.value;
  markDirty();
});

$('tplVersion').addEventListener('input', (e) => {
  state.tplVersion = e.target.value;
  markDirty();
});

$('advancedToggle').addEventListener('change', (e) => {
  const open = e.target.checked;
  $('advancedBlock').hidden = !open;
  if (open) refreshAdvancedEditors(true);
  else if (!$('advRaw').value.trim()) clearRaw();
});

$('send').addEventListener('click', send);

$('clear').addEventListener('click', () => {
  rows.length = 0;
  renderLog();
});

$('filters').addEventListener('click', (e) => {
  const btn = e.target.closest('button');
  if (!btn) return;
  filter = btn.dataset.filter;
  $('filters').querySelectorAll('button').forEach((b) => b.classList.toggle('on', b === btn));
  renderLog();
});

const es = new EventSource('/events');
es.onmessage = (e) => {
  const entry = JSON.parse(e.data);
  if (entry.kind === 'hello') return;
  pushRow(entryToRow(entry));
  if (entry.kind === 'system') {
    const msg = String(entry.message || '');
    if (/fail/i.test(msg)) setStatus('Offline — retrying', false);
    else setStatus('Live', true);
  }
};

fetch('/api/config')
  .then((r) => r.json())
  .then((cfg) => setStatus(cfg.nats ? 'Live' : 'Offline — retrying', cfg.nats))
  .catch(() => setStatus('Offline — retrying', false));

render();
