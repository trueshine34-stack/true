/* Панель OLX — клиентская логика. Без сборщика и зависимостей. */

const PARAMS = new URLSearchParams(location.search);
const TOKEN = PARAMS.get('token') || '';

/**
 * Панель работает в двух режимах с одним и тем же интерфейсом:
 *   — автономный: вся логика внутри устройства (приложение на телефоне);
 *   — серверный: запросы уходят на Node-сервер.
 * Автономный включается, когда есть нативный мост Android или ?standalone=1.
 */
const STANDALONE = typeof window.OlxNative !== 'undefined' || PARAMS.has('standalone');

let localApiPromise = null;
const loadLocalApi = () => {
  localApiPromise = localApiPromise || import('./local-api.js').then((m) => m.localApi);
  return localApiPromise;
};

async function api(path, options = {}) {
  if (STANDALONE) {
    const localApi = await loadLocalApi();
    return localApi(path, options);
  }
  const res = await fetch(path, {
    ...options,
    headers: {
      'content-type': 'application/json',
      ...(TOKEN ? { 'x-panel-token': TOKEN } : {}),
      ...(options.headers || {}),
    },
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
  return data;
}

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const money = (value, currency = 'PLN') =>
  `${Number(value || 0).toLocaleString('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency}`;
const esc = (text) => String(text ?? '').replace(/[&<>"']/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

let toastTimer;
function toast(message, kind = '') {
  const el = $('#toast');
  el.textContent = message;
  el.className = `toast ${kind}`;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, 4000);
}

const state = {
  settings: null,
  stats: null,
  sources: [],
  listings: [],
  selected: new Set(),
  queue: { running: false },
  dryRun: true,
};

const STATUS_LABEL = {
  draft: 'черновик', ready: 'готово', queued: 'в очереди', published: 'опубликовано', error: 'ошибка',
};

/* ------------------------------- вкладки ------------------------------- */

$('#tabs').addEventListener('click', (event) => {
  const tab = event.target.closest('.tab');
  if (!tab) return;
  $$('.tab').forEach((t) => t.classList.toggle('is-active', t === tab));
  $$('[data-view]').forEach((view) => { view.hidden = view.dataset.view !== tab.dataset.tab; });
  if (tab.dataset.tab === 'logs') loadLogs();
  if (tab.dataset.tab === 'pricing') previewPricing();
});

/* -------------------------------- общее -------------------------------- */

async function loadState() {
  const data = await api('/api/state');
  state.settings = data.settings;
  state.stats = data.stats;
  state.sources = data.sources;
  state.queue = data.queue;
  state.dryRun = data.dryRun;

  const badge = $('#mode-badge');
  badge.textContent = data.dryRun ? 'Тест (dry-run)' : 'Публикация включена';
  badge.className = `badge ${data.dryRun ? 'dry' : 'live'}`;
  $('#olx-status').textContent = olxStatusText(data);

  renderStats();
  renderSources();
  fillPricingForm();
  fillTemplateForm();
  fillSettingsForm();
  renderQueue();
}

function renderStats() {
  const s = state.stats;
  $('#stats').innerHTML = `
    <div class="stat"><b>${s.total}</b><span>всего</span></div>
    <div class="stat"><b>${s.draft}</b><span>черновики</span></div>
    <div class="stat"><b>${s.ready}</b><span>готовы</span></div>
    <div class="stat"><b>${s.queued}</b><span>в очереди</span></div>
    <div class="stat"><b>${s.published}</b><span>опубликованы</span></div>
    <div class="stat"><b>${s.error}</b><span>ошибки</span></div>
    <div class="stat accent"><b>${money(s.margin)}</b><span>маржа опубликованных</span></div>`;
}

/* ------------------------------ объявления ----------------------------- */

async function loadListings() {
  const params = new URLSearchParams({
    status: $('#filter-status').value,
    q: $('#search').value.trim(),
  });
  const data = await api(`/api/listings?${params}`);
  state.listings = data.items;
  state.duplicates = new Map((data.duplicates || []).map((d) => [d.id, d.duplicateOf]));
  renderListings();
  fillPreviewPicker();
}

function renderListings() {
  const rows = state.listings.map((l) => {
    const priced = l.priced || {};
    const marginClass = priced.margin >= 0 ? 'margin-pos' : 'margin-neg';
    const dup = state.duplicates?.get(l.id);
    return `
      <tr data-id="${l.id}">
        <td class="col-check"><input type="checkbox" class="pick" ${state.selected.has(l.id) ? 'checked' : ''}></td>
        <td class="col-photo">${l.images?.[0]
          ? `<img class="thumb" src="${esc(l.images[0])}" alt="" loading="lazy">`
          : '<div class="thumb"></div>'}</td>
        <td class="cell-title">
          <div class="title">${esc(l.formatted?.title || l.title || '(без названия)')}</div>
          ${l.formatted?.title && l.formatted.title !== l.title
            ? `<div class="sub">источник: ${esc(l.title)}</div>` : ''}
          <div class="sub">
            ${esc(l.categoryName || 'без категории')}
            ${l.olxCategoryId || l.categoryId ? `· id ${esc(l.olxCategoryId || l.categoryId)}` : ''}
            ${l.images?.length ? `· ${l.images.length} фото` : '· <span class="margin-neg">нет фото</span>'}
            ${dup ? ' · <span class="margin-neg">дубль</span>' : ''}
            ${l.olx?.url ? ` · <a href="${esc(l.olx.url)}" target="_blank" rel="noopener">на OLX</a>` : ''}
          </div>
        </td>
        <td class="num cell-data" data-label="Закупка">${money(l.basePrice, priced.currency)}</td>
        <td class="num cell-data ${marginClass}" data-label="Наценка">${priced.margin != null
          ? `${money(priced.margin, '')} <span class="sub">${priced.marginPercent}%</span>` : '—'}</td>
        <td class="num cell-data" data-label="Цена OLX"><span class="price-main">${money(priced.price, priced.currency)}</span>${priced.manual ? ' <span class="sub">вручную</span>' : ''}</td>
        <td class="cell-data" data-label="Статус"><span class="pill ${l.status}">${STATUS_LABEL[l.status] || l.status}</span>
          ${l.error ? `<div class="sub margin-neg">${esc(l.error.slice(0, 90))}</div>` : ''}</td>
        <td class="cell-actions"><button class="btn btn-ghost edit">Открыть</button></td>
      </tr>`;
  }).join('');

  $('#listing-rows').innerHTML = rows;
  $('#listings-empty').hidden = state.listings.length > 0;
  renderSelection();
}

/** Выделение доступно и с телефона: в шапке таблицы там чекбокса нет. */
function renderSelection() {
  const total = state.listings.length;
  const selected = state.listings.filter((l) => state.selected.has(l.id)).length;
  $('#toggle-all').textContent = selected ? `Снять выделение (${selected})` : 'Выбрать все';
  $('#toggle-all').disabled = total === 0;
  $('#check-all').checked = total > 0 && selected === total;
}

$('#listing-rows').addEventListener('click', (event) => {
  const row = event.target.closest('tr');
  if (!row) return;
  if (event.target.classList.contains('pick')) {
    if (event.target.checked) state.selected.add(row.dataset.id);
    else state.selected.delete(row.dataset.id);
    renderSelection();
    return;
  }
  if (event.target.classList.contains('edit')) openEditor(row.dataset.id);
});

function setAllSelected(selectAll) {
  state.selected = selectAll ? new Set(state.listings.map((l) => l.id)) : new Set();
  renderListings();
}

$('#check-all').addEventListener('change', (event) => setAllSelected(event.target.checked));

$('#toggle-all').addEventListener('click', () => {
  const selected = state.listings.filter((l) => state.selected.has(l.id)).length;
  setAllSelected(selected === 0);
});

let searchTimer;
$('#search').addEventListener('input', () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(loadListings, 250);
});
$('#filter-status').addEventListener('change', loadListings);

function selectedIds() {
  if (!state.selected.size) { toast('Сначала выберите объявления', 'err'); return null; }
  return [...state.selected];
}

$('#bulk-queue').addEventListener('click', async () => {
  const ids = selectedIds();
  if (!ids) return;
  await api('/api/listings/bulk', { method: 'POST', body: { action: 'queue', ids } });
  toast(`В очередь: ${ids.length}`, 'ok');
  await refreshAll();
});

$('#bulk-delete').addEventListener('click', async () => {
  const ids = selectedIds();
  if (!ids || !confirm(`Удалить ${ids.length} объявлений из панели?`)) return;
  await api('/api/listings/bulk', { method: 'POST', body: { action: 'delete', ids } });
  state.selected.clear();
  toast('Удалено', 'ok');
  await refreshAll();
});

$('#publish-selected').addEventListener('click', async () => {
  const ids = [...state.selected];
  const label = state.dryRun ? 'Тестовый прогон' : 'Публикация';
  if (!ids.length && !confirm('Ничего не выбрано. Обработать всю очередь и черновики?')) return;
  await api('/api/publish', { method: 'POST', body: { ids } });
  toast(`${label} запущена`, 'ok');
  pollQueue();
});

$('#publish-stop').addEventListener('click', async () => {
  await api('/api/publish/stop', { method: 'POST' });
  toast('Очередь остановлена');
});

function renderQueue() {
  const q = state.queue || {};
  const bar = $('#queue-bar');
  bar.hidden = !q.running && !q.total;
  $('#publish-stop').hidden = !q.running;
  if (bar.hidden) return;
  const percent = q.total ? Math.round((q.done / q.total) * 100) : 0;
  $('.queue-fill', bar).style.width = `${percent}%`;
  $('span', bar).textContent = q.running
    ? `Обработано ${q.done} из ${q.total} · пауза ${Math.round((q.intervalMs || 0) / 1000)} с`
    : `Готово: ${q.done} из ${q.total}`;
}

let queueTimer;
async function pollQueue() {
  clearTimeout(queueTimer);
  state.queue = await api('/api/queue');
  renderQueue();
  await loadListings();
  if (state.queue.running) queueTimer = setTimeout(pollQueue, 2500);
  else { await loadState(); await loadLogs(); }
}

/* -------------------------------- импорт ------------------------------- */

function renderSources() {
  const options = ['<option value="">без источника</option>',
    ...state.sources.map((s) => `<option value="${esc(s.id)}">${esc(s.name)}</option>`)].join('');
  $('#import-source-url').innerHTML = options;
  $('#import-source-file').innerHTML = options;
  $('#sources-list').innerHTML = state.sources.length
    ? state.sources.map((s) => `
        <li data-id="${esc(s.id)}">
          <b>${esc(s.name)}</b>
          <span class="spacer"></span>
          <span class="hint">${esc(s.url || '')}</span>
          <button class="btn btn-ghost del">✕</button>
        </li>`).join('')
    : '<li class="hint">Источников пока нет</li>';
}

$('#add-source').addEventListener('click', async () => {
  const name = $('#source-name').value.trim();
  if (!name) return toast('Введите название источника', 'err');
  await api('/api/sources', { method: 'POST', body: { name, url: $('#source-url').value.trim() } });
  $('#source-name').value = ''; $('#source-url').value = '';
  await loadState();
  return toast('Источник добавлен', 'ok');
});

$('#sources-list').addEventListener('click', async (event) => {
  if (!event.target.classList.contains('del')) return;
  await api(`/api/sources/${event.target.closest('li').dataset.id}`, { method: 'DELETE' });
  await loadState();
});

$('#do-import-url').addEventListener('click', async (event) => {
  const button = event.target;
  const urls = $('#import-urls').value.split(/\s+/).filter(Boolean);
  if (!urls.length) return toast('Вставьте хотя бы одну ссылку', 'err');
  button.disabled = true;
  button.textContent = 'Импортирую…';
  try {
    const result = await api('/api/import/url', {
      method: 'POST',
      body: { urls, sourceId: $('#import-source-url').value },
    });
    showImportResult(result);
    toast(`Добавлено ${result.added}, пропущено ${result.skipped}`, 'ok');
    await refreshAll();
  } catch (err) {
    toast(err.message, 'err');
  } finally {
    button.disabled = false;
    button.textContent = 'Импортировать';
  }
  return undefined;
});

$('#import-file').addEventListener('change', async (event) => {
  const file = event.target.files[0];
  if (!file) return;
  $('#import-text').value = await file.text();
  $('#import-format').value = file.name.endsWith('.json') ? 'json' : 'csv';
});

$('#do-import-file').addEventListener('click', async () => {
  const text = $('#import-text').value;
  if (!text.trim()) return toast('Вставьте данные или выберите файл', 'err');
  try {
    const result = await api('/api/import/file', {
      method: 'POST',
      body: { text, format: $('#import-format').value, sourceId: $('#import-source-file').value },
    });
    showImportResult(result);
    toast(`Добавлено ${result.added}, пропущено ${result.skipped}`, 'ok');
    await refreshAll();
  } catch (err) {
    toast(err.message, 'err');
  }
  return undefined;
});

function showImportResult(result) {
  const out = $('#import-output');
  out.hidden = false;
  out.textContent = JSON.stringify({
    добавлено: result.added,
    пропущено_дубли: result.skipped,
    без_названия: result.invalid ?? 0,
    ошибки: result.failed ?? [],
  }, null, 2);
}

/* -------------------------------- наценка ------------------------------ */

function fillPricingForm() {
  const p = state.settings.pricing;
  for (const el of $$('#pricing-form [name]')) {
    if (el.type === 'checkbox') el.checked = Boolean(p[el.name]);
    else el.value = p[el.name] ?? '';
  }
}

function readPricingForm() {
  const rule = {};
  for (const el of $$('#pricing-form [name]')) {
    if (el.type === 'checkbox') rule[el.name] = el.checked;
    else if (el.type === 'number') rule[el.name] = Number(el.value || 0);
    else rule[el.name] = el.value;
  }
  return rule;
}

let pricingTimer;
$('#pricing-form').addEventListener('input', () => {
  clearTimeout(pricingTimer);
  pricingTimer = setTimeout(previewPricing, 200);
});

async function previewPricing() {
  const { samples } = await api('/api/pricing/preview', { method: 'POST', body: { rule: readPricingForm() } });
  $('#pricing-preview').innerHTML = `
    <thead><tr><th>Закупка</th><th class="num">Наценка</th><th class="num">Комиссия</th><th class="num">Цена OLX</th><th class="num">Маржа</th></tr></thead>
    <tbody>${samples.map((s) => `
      <tr>
        <td>${money(s.base, s.currency)}</td>
        <td class="num">${money(s.markup, '')}</td>
        <td class="num">${money(s.fee, '')}</td>
        <td class="num"><b>${money(s.price, s.currency)}</b></td>
        <td class="num ${s.margin >= 0 ? 'margin-pos' : 'margin-neg'}">${money(s.margin, '')} (${s.marginPercent}%)</td>
      </tr>`).join('')}</tbody>`;
}

$('#save-pricing').addEventListener('click', async () => {
  await api('/api/settings', { method: 'PUT', body: { pricing: readPricingForm() } });
  toast('Правило наценки сохранено', 'ok');
  await refreshAll();
});

$('#apply-pricing').addEventListener('click', async () => {
  const ids = selectedIds();
  if (!ids) return;
  await api('/api/pricing/apply', { method: 'POST', body: { ids, rule: readPricingForm() } });
  toast(`Правило применено к ${ids.length} объявлениям`, 'ok');
  await refreshAll();
});

/* -------------------------------- шаблон ------------------------------- */

function fillTemplateForm() {
  const t = state.settings.template;
  $('#tpl-title').value = t.title;
  $('#tpl-body').value = t.body;
  $('#tpl-vars').value = JSON.stringify(t.vars || {}, null, 2);
  $('#tpl-max-title').value = t.maxTitleLength;
  $('#tpl-max-body').value = t.maxBodyLength;
  $('#tpl-case').value = t.titleCase;
  $('#tpl-sanitize').checked = t.sanitize;
  $('#tpl-hashtags').checked = t.hashtags;
}

$('#save-template').addEventListener('click', async () => {
  let vars;
  try {
    vars = JSON.parse($('#tpl-vars').value || '{}');
  } catch {
    return toast('Свои переменные — некорректный JSON', 'err');
  }
  await api('/api/settings', {
    method: 'PUT',
    body: {
      template: {
        title: $('#tpl-title').value,
        body: $('#tpl-body').value,
        vars,
        maxTitleLength: Number($('#tpl-max-title').value) || 70,
        maxBodyLength: Number($('#tpl-max-body').value) || 9000,
        titleCase: $('#tpl-case').value,
        sanitize: $('#tpl-sanitize').checked,
        hashtags: $('#tpl-hashtags').checked,
      },
    },
  });
  toast('Шаблон сохранён', 'ok');
  await loadState();
  if ($('#preview-pick').value) showPreview($('#preview-pick').value);
  return undefined;
});

function fillPreviewPicker() {
  const pick = $('#preview-pick');
  const current = pick.value;
  pick.innerHTML = ['<option value="">— выберите объявление —</option>',
    ...state.listings.map((l) => `<option value="${l.id}">${esc((l.title || l.id).slice(0, 70))}</option>`)].join('');
  pick.value = current;
}

$('#preview-pick').addEventListener('change', (event) => {
  if (event.target.value) showPreview(event.target.value);
});

function renderAd(container, data, listing) {
  const errors = data.validation || [];
  container.innerHTML = `
    ${listing?.images?.length
      ? `<div class="ad-photos">${listing.images.slice(0, 8).map((u) => `<img src="${esc(u)}" alt="" loading="lazy">`).join('')}</div>`
      : ''}
    <div class="ad-title">${esc(data.formatted.title)}</div>
    <div class="ad-price">${money(data.priced.price, data.priced.currency)}${data.priced.negotiable ? ' <span class="hint">do negocjacji</span>' : ''}</div>
    <div class="ad-body">${esc(data.formatted.description)}</div>
    <div class="ad-meta">
      <span>Заголовок: ${data.formatted.title.length}/70</span>
      <span>Текст: ${data.formatted.description.length} зн.</span>
      <span>Закупка: ${money(data.priced.base)}</span>
      <span>Маржа: ${money(data.priced.margin)} (${data.priced.marginPercent}%)</span>
    </div>
    ${errors.length ? `<div class="ad-errors">Не пройдёт публикацию: ${errors.map(esc).join(' · ')}</div>` : ''}`;
}

async function showPreview(id) {
  const data = await api(`/api/listings/${id}/preview`);
  renderAd($('#ad-preview'), data, state.listings.find((l) => l.id === id));
}

/* ------------------------------- настройки ----------------------------- */

function fillSettingsForm() {
  const s = state.settings;
  $$('[data-standalone]').forEach((el) => { el.hidden = !STANDALONE; });
  if (STANDALONE && s.olx) {
    $('#set-olx-id').value = s.olx.clientId || '';
    $('#set-olx-secret').value = s.olx.clientSecret || '';
    $('#set-olx-redirect').value = s.olx.redirectUri || '';
    $('#set-interval').value = Math.round((s.publish?.intervalMs ?? 45000) / 1000);
    $('#set-max-run').value = s.publish?.maxPerRun ?? 20;
    $('#set-dry').checked = Boolean(s.publish?.dryRun);
  }
  $('#set-contact-name').value = s.contact.name || '';
  $('#set-contact-phone').value = s.contact.phone || '';
  $('#set-city-id').value = s.location.cityId ?? '';
  $('#set-district-id').value = s.location.districtId ?? '';
  $('#set-city-search').value = s.location.cityName || '';
  $('#set-advertiser').value = s.advertiserType;
}

$('#save-settings').addEventListener('click', async () => {
  await api('/api/settings', {
    method: 'PUT',
    body: {
      contact: { name: $('#set-contact-name').value.trim(), phone: $('#set-contact-phone').value.trim() },
      location: {
        cityId: Number($('#set-city-id').value) || null,
        districtId: Number($('#set-district-id').value) || null,
        cityName: $('#set-city-search').value.trim(),
      },
      advertiserType: $('#set-advertiser').value,
    },
  });
  toast('Настройки сохранены', 'ok');
  await loadState();
});

let cityTimer;
$('#set-city-search').addEventListener('input', (event) => {
  clearTimeout(cityTimer);
  const q = event.target.value.trim();
  if (q.length < 3) return;
  cityTimer = setTimeout(async () => {
    try {
      const cities = await api(`/api/olx/cities?q=${encodeURIComponent(q)}`);
      $('#city-results').innerHTML = (cities || []).slice(0, 12).map((c) => `
        <li><b>${esc(c.name)}</b><span class="hint">${esc(c.region?.name || '')}</span>
        <span class="spacer"></span><button class="btn pick-city" data-id="${c.id}" data-name="${esc(c.name)}">id ${c.id}</button></li>`).join('');
    } catch (err) {
      $('#city-results').innerHTML = `<li class="hint">${esc(err.message)}</li>`;
    }
  }, 400);
});

$('#city-results').addEventListener('click', (event) => {
  const button = event.target.closest('.pick-city');
  if (!button) return;
  $('#set-city-id').value = button.dataset.id;
  $('#set-city-search').value = button.dataset.name;
  toast(`Город: ${button.dataset.name} (id ${button.dataset.id})`);
});

$('#cat-load').addEventListener('click', async () => {
  const parent = $('#cat-parent').value.trim();
  try {
    const cats = await api(`/api/olx/categories${parent ? `?parent_id=${parent}` : ''}`);
    $('#cat-list').innerHTML = (cats || []).map((c) => `
      <li><b>${esc(c.name)}</b><span class="hint">id ${c.id}${c.is_leaf ? ' · конечная' : ''}</span>
      <span class="spacer"></span>
      <button class="btn cat-open" data-id="${c.id}">Вложенные</button></li>`).join('') || '<li class="hint">Пусто</li>';
  } catch (err) {
    $('#cat-list').innerHTML = `<li class="hint">${esc(err.message)}</li>`;
  }
});

$('#cat-list').addEventListener('click', (event) => {
  const button = event.target.closest('.cat-open');
  if (!button) return;
  $('#cat-parent').value = button.dataset.id;
  $('#cat-load').click();
});

$('#save-olx-keys')?.addEventListener('click', async () => {
  await api('/api/settings', {
    method: 'PUT',
    body: {
      olx: {
        clientId: $('#set-olx-id').value.trim(),
        clientSecret: $('#set-olx-secret').value.trim(),
        redirectUri: $('#set-olx-redirect').value.trim() || 'olxpanel://oauth',
      },
    },
  });
  toast('Ключи сохранены', 'ok');
  await loadState();
});

$('#save-publish')?.addEventListener('click', async () => {
  await api('/api/settings', {
    method: 'PUT',
    body: {
      publish: {
        intervalMs: Math.max(5, Number($('#set-interval').value) || 45) * 1000,
        maxPerRun: Math.max(1, Number($('#set-max-run').value) || 20),
        dryRun: $('#set-dry').checked,
      },
    },
  });
  toast('Настройки публикации сохранены', 'ok');
  await loadState();
});

$('#backup-export')?.addEventListener('click', async () => {
  const data = await api('/api/backup');
  const json = JSON.stringify(data, null, 2);
  const name = `olx-panel-${new Date().toISOString().slice(0, 10)}.json`;
  if (window.OlxNative?.saveFile) {
    window.OlxNative.saveFile(name, json);
    toast('Копия сохранена — выберите, куда её отправить', 'ok');
    return;
  }
  const url = URL.createObjectURL(new Blob([json], { type: 'application/json' }));
  const link = Object.assign(document.createElement('a'), { href: url, download: name });
  link.click();
  URL.revokeObjectURL(url);
  toast('Копия сохранена', 'ok');
});

$('#backup-import')?.addEventListener('click', () => $('#backup-file').click());

$('#backup-file')?.addEventListener('change', async (event) => {
  const file = event.target.files[0];
  if (!file) return;
  try {
    const result = await api('/api/restore', { method: 'POST', body: JSON.parse(await file.text()) });
    toast(`Восстановлено объявлений: ${result.listings}`, 'ok');
    await refreshAll();
  } catch (err) {
    toast(`Не удалось восстановить: ${err.message}`, 'err');
  } finally {
    event.target.value = '';
  }
});

// Код авторизации приходит из нативного окна OLX — обмениваем его на токены.
window.__olxOAuthResult = async (code, error) => {
  if (error || !code) {
    toast(error || 'Авторизация отменена', 'err');
    return;
  }
  try {
    await api('/api/olx/oauth/exchange', { method: 'POST', body: { code } });
    toast('Аккаунт OLX подключён', 'ok');
    await loadState();
  } catch (err) {
    toast(err.message, 'err');
  }
};

/** Три разных состояния: ключей нет, ключи есть, аккаунт подключён. */
function olxStatusText(data) {
  if (!data.olxConfigured) {
    return STANDALONE
      ? 'Шаг 1. Ключей нет. Получите client_id и client_secret в кабинете developer.olx.pl (заявку проверяют вручную, ключи придут на почту) и сохраните их здесь. Пока панель считает цены и готовит текст, но ничего не отправляет.'
      : 'Ключи OLX не заданы в .env — панель работает в режиме dry-run: считает цены и готовит текст, но ничего не отправляет.';
  }
  if (!data.olxConnected) {
    return 'Шаг 2. Ключи сохранены, аккаунт ещё не подключён. Нажмите «Подключить через OAuth» и подтвердите доступ на странице OLX — без этого объявления публиковать нельзя.';
  }
  return 'Аккаунт OLX подключён, публикация доступна. «Проверить связь» покажет, от чьего имени работает панель.';
}

$('#olx-connect').addEventListener('click', async () => {
  // Без client_id страница OLX отвечает «No client id supplied» — не отправляем туда впустую.
  const { url, redirectUri } = await api('/api/olx/authorize');
  if (STANDALONE && !state.settings?.olx?.clientId) {
    toast('Сначала сохраните client_id и client_secret из кабинета OLX', 'err');
    return;
  }
  if (window.OlxNative?.startOAuth) {
    if (!redirectUri) {
      toast('Укажите redirect_uri — точно такой же, как в кабинете OLX', 'err');
      return;
    }
    window.OlxNative.startOAuth(url, redirectUri);
    return;
  }
  window.open(url, '_blank', 'noopener');
});

$('#olx-check').addEventListener('click', async () => {
  const out = $('#olx-output');
  out.hidden = false;
  try {
    out.textContent = JSON.stringify(await api('/api/olx/me'), null, 2);
    toast('Связь с OLX работает', 'ok');
  } catch (err) {
    out.textContent = err.message;
    toast(err.message, 'err');
  }
});

/* --------------------------------- логи -------------------------------- */

async function loadLogs() {
  const logs = await api('/api/logs');
  $('#logs-list').innerHTML = logs.map((l) => `
    <li>
      <time>${new Date(l.at).toLocaleString('ru-RU')}</time>
      <span class="lvl ${l.level}">${l.level}</span>
      <span>${esc(l.message)}${l.meta && Object.keys(l.meta).length
        ? ` <span class="hint">${esc(JSON.stringify(l.meta).slice(0, 220))}</span>` : ''}</span>
    </li>`).join('') || '<li class="hint">Логи пусты</li>';
}

$('#logs-clear').addEventListener('click', async () => {
  await api('/api/logs', { method: 'DELETE' });
  await loadLogs();
});

/* ------------------------------- редактор ------------------------------ */

let editing = null;

async function openEditor(id) {
  const data = await api(`/api/listings/${id}`);
  editing = data;
  $('#editor').hidden = false;
  $('#editor-title').textContent = data.title || 'Объявление';
  $('#ed-title').value = data.title || '';
  $('#ed-desc').value = data.description || '';
  $('#ed-base').value = data.basePrice ?? 0;
  $('#ed-manual').value = data.manualPrice ?? '';
  $('#ed-category').value = data.olxCategoryId || data.categoryId || '';
  $('#ed-status').value = ['draft', 'ready', 'queued'].includes(data.status) ? data.status : 'draft';
  $('#ed-images').value = (data.images || []).join('\n');
  $('#ed-attrs').value = JSON.stringify(data.olxAttributes || {}, null, 2);
  renderAd($('#ed-preview'), data, data);
  $('#ed-validation').textContent = data.validation?.length ? data.validation.join(' · ') : 'Проверка пройдена';
}

$('#editor-close').addEventListener('click', () => { $('#editor').hidden = true; editing = null; });
$('#editor').addEventListener('click', (event) => {
  if (event.target.id === 'editor') { $('#editor').hidden = true; editing = null; }
});

function editorPatch() {
  let olxAttributes;
  try {
    olxAttributes = JSON.parse($('#ed-attrs').value || '{}');
  } catch {
    throw new Error('Атрибуты — некорректный JSON');
  }
  return {
    title: $('#ed-title').value,
    description: $('#ed-desc').value,
    basePrice: Number($('#ed-base').value) || 0,
    manualPrice: $('#ed-manual').value ? Number($('#ed-manual').value) : null,
    olxCategoryId: Number($('#ed-category').value) || null,
    status: $('#ed-status').value,
    images: $('#ed-images').value.split(/\s+/).filter(Boolean),
    olxAttributes,
  };
}

async function saveEditing() {
  await api(`/api/listings/${editing.id}`, { method: 'PATCH', body: editorPatch() });
}

$('#ed-save').addEventListener('click', async () => {
  try {
    await saveEditing();
    toast('Сохранено', 'ok');
    await openEditor(editing.id);
    await refreshAll();
  } catch (err) { toast(err.message, 'err'); }
});

$('#ed-refresh').addEventListener('click', async () => {
  try {
    await saveEditing();
    await openEditor(editing.id);
  } catch (err) { toast(err.message, 'err'); }
});

$('#ed-publish').addEventListener('click', async () => {
  try {
    await saveEditing();
    const result = await api('/api/publish/one', { method: 'POST', body: { id: editing.id } });
    toast(result.status === 'published' ? 'Опубликовано на OLX' : `Статус: ${STATUS_LABEL[result.status] || result.status}`,
      result.status === 'error' ? 'err' : 'ok');
    await openEditor(editing.id);
    await refreshAll();
  } catch (err) { toast(err.message, 'err'); }
});

/* --------------------------------- старт ------------------------------- */

async function refreshAll() {
  await loadState();
  await loadListings();
}

$('#refresh').addEventListener('click', () => refreshAll().then(() => toast('Обновлено')));

refreshAll()
  .then(() => { if (state.queue.running) pollQueue(); })
  .catch((err) => toast(err.message, 'err'));
