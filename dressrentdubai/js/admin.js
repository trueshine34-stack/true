/* Админка DressRentDubai — массовая загрузка фото и групповые теги.
   Репозиторий зашит в код; администратор один раз вводит код доступа GitHub,
   который сохраняется только в его браузере. */

const ADMIN_LOGIN = 'admin';
const ADMIN_PASSWORD = 'Admin555';

const GH_OWNER = 'trueshine34-stack';
const GH_REPO = 'true';
const GH_BRANCH_PREFERRED = 'claude/dressrentdubai-website-w9cyx2';
const GH_BRANCH_FALLBACK = 'main';
const GH_API = 'https://api.github.com';
const REPO_BASE = 'dressrentdubai/';

const SESSION_KEY = 'drd_admin_session';
const TOKEN_KEY = 'drd_gh_token';

const TOKEN_CREATE_URL =
  'https://github.com/settings/tokens/new?scopes=repo&description=DressRentDubai%20admin';

// ---------- состояние ----------
let items = [];            // { id, name, price, tags, image, _new?, _dataUrl?, _dirty? }
let removed = [];          // удалённые (для чистки картинок в репозитории)
let selected = new Set();
let branch = GH_BRANCH_PREFERRED;
let editingId = null;

// ---------- элементы ----------
const $ = (id) => document.getElementById(id);
const loginScreen = $('loginScreen');
const connectScreen = $('connectScreen');
const dashboard = $('dashboard');
const gallery = $('gallery');
const statusBar = $('statusBar');
const actionBar = $('actionBar');
const publishBar = $('publishBar');
const editSheet = $('editSheet');

// ---------- утилиты ----------
const getToken = () => localStorage.getItem(TOKEN_KEY) || '';
const isAuthed = () => sessionStorage.getItem(SESSION_KEY) === '1';

function esc(str) {
  const d = document.createElement('div');
  d.textContent = str == null ? '' : str;
  return d.innerHTML;
}

/* В админке префикс у всех тегов одинаковый — показываем короткую форму,
   чтобы на телефоне в строку помещалось больше чипсов. */
const shortTag = (slug) => '#' + slug;

function status(text, kind) {
  if (!text) {
    statusBar.hidden = true;
    return;
  }
  statusBar.hidden = false;
  statusBar.textContent = text;
  statusBar.className = 'statusbar' + (kind ? ' statusbar--' + kind : '');
}

function b64Utf8(str) {
  return btoa(unescape(encodeURIComponent(str)));
}

function fromB64Utf8(str) {
  return decodeURIComponent(escape(atob(String(str).replace(/\s/g, ''))));
}

function hasChanges() {
  return removed.length > 0 || items.some((i) => i._new || i._dirty);
}

// ---------- GitHub API ----------
function ghHeaders() {
  return {
    Authorization: `Bearer ${getToken()}`,
    Accept: 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
  };
}

async function gh(path, options = {}) {
  const res = await fetch(GH_API + path, {
    ...options,
    headers: { ...ghHeaders(), 'Content-Type': 'application/json', ...(options.headers || {}) },
  });
  if (res.status === 404) return null;
  if (!res.ok) {
    let msg = `GitHub ${res.status}`;
    try {
      const j = await res.json();
      if (j.message) msg = j.message;
    } catch (e) { /* пусто */ }
    throw new Error(msg);
  }
  return res.json();
}

/* Определяет рабочую ветку: сначала ветку разработки, иначе main. */
async function resolveBranch() {
  const dev = await gh(`/repos/${GH_OWNER}/${GH_REPO}/git/ref/heads/${GH_BRANCH_PREFERRED}`);
  branch = dev ? GH_BRANCH_PREFERRED : GH_BRANCH_FALLBACK;
  return branch;
}

async function loadCatalog() {
  const file = await gh(
    `/repos/${GH_OWNER}/${GH_REPO}/contents/${REPO_BASE}${DRD_CATALOG_PATH}?ref=${encodeURIComponent(branch)}`
  );
  if (!file) return [];
  const parsed = JSON.parse(fromB64Utf8(file.content));
  return Array.isArray(parsed) ? parsed : [];
}

/* Публикует все изменения одним коммитом через Git Data API. */
async function publishAll() {
  const head = await gh(`/repos/${GH_OWNER}/${GH_REPO}/git/ref/heads/${branch}`);
  if (!head) throw new Error(`Ветка ${branch} не найдена`);
  const headSha = head.object.sha;

  const headCommit = await gh(`/repos/${GH_OWNER}/${GH_REPO}/git/commits/${headSha}`);
  const baseTree = headCommit.tree.sha;

  const tree = [];
  const newPhotos = items.filter((i) => i._new && i._dataUrl);

  for (let n = 0; n < newPhotos.length; n++) {
    const item = newPhotos[n];
    status(`Загружаем фото ${n + 1} из ${newPhotos.length}...`);
    const blob = await gh(`/repos/${GH_OWNER}/${GH_REPO}/git/blobs`, {
      method: 'POST',
      body: JSON.stringify({ content: item._dataUrl.split(',')[1], encoding: 'base64' }),
    });
    item.image = `${DRD_IMAGE_DIR}/${item.id}.jpg`;
    tree.push({ path: REPO_BASE + item.image, mode: '100644', type: 'blob', sha: blob.sha });
  }

  status('Сохраняем каталог...');
  const payload = items.map((i) => ({
    id: i.id,
    name: i.name,
    price: i.price || '',
    tags: i.tags || [],
    image: i.image || null,
  }));
  const catalogBlob = await gh(`/repos/${GH_OWNER}/${GH_REPO}/git/blobs`, {
    method: 'POST',
    body: JSON.stringify({ content: JSON.stringify(payload, null, 2) + '\n', encoding: 'utf-8' }),
  });
  tree.push({
    path: REPO_BASE + DRD_CATALOG_PATH,
    mode: '100644',
    type: 'blob',
    sha: catalogBlob.sha,
  });

  // удаляем фото убранных образов
  removed.forEach((item) => {
    if (item.image && !item._new) {
      tree.push({ path: REPO_BASE + item.image, mode: '100644', type: 'blob', sha: null });
    }
  });

  const newTree = await gh(`/repos/${GH_OWNER}/${GH_REPO}/git/trees`, {
    method: 'POST',
    body: JSON.stringify({ base_tree: baseTree, tree }),
  });

  const added = newPhotos.length;
  const message = added
    ? `Каталог: +${added} фото, всего образов ${items.length}`
    : `Каталог: обновлено, всего образов ${items.length}`;

  const commit = await gh(`/repos/${GH_OWNER}/${GH_REPO}/git/commits`, {
    method: 'POST',
    body: JSON.stringify({ message, tree: newTree.sha, parents: [headSha] }),
  });

  await gh(`/repos/${GH_OWNER}/${GH_REPO}/git/refs/heads/${branch}`, {
    method: 'PATCH',
    body: JSON.stringify({ sha: commit.sha }),
  });

  // _dataUrl оставляем: пока GitHub Pages пересобирается, плитки показывают локальное превью
  items.forEach((i) => {
    delete i._new;
    delete i._dirty;
  });
  removed = [];
}

// ---------- экраны ----------
function showLogin() {
  loginScreen.hidden = false;
  connectScreen.hidden = true;
  dashboard.hidden = true;
}

function showConnect() {
  loginScreen.hidden = true;
  connectScreen.hidden = false;
  dashboard.hidden = true;
  $('tokenLink').href = TOKEN_CREATE_URL;
}

async function showDashboard() {
  loginScreen.hidden = true;
  connectScreen.hidden = true;
  dashboard.hidden = false;
  buildTagBar($('tagBar'), applyTagToSelection);
  buildTagBar($('editTags'), toggleEditTag);
  await refresh();
}

function route() {
  if (!isAuthed()) return showLogin();
  if (!getToken()) return showConnect();
  showDashboard();
}

// ---------- вход ----------
$('loginForm').addEventListener('submit', (e) => {
  e.preventDefault();
  const user = $('loginUser').value.trim();
  const pass = $('loginPass').value;
  if (user === ADMIN_LOGIN && pass === ADMIN_PASSWORD) {
    sessionStorage.setItem(SESSION_KEY, '1');
    $('loginError').textContent = '';
    $('loginForm').reset();
    route();
  } else {
    $('loginError').textContent = 'Неверный логин или пароль';
  }
});

$('logoutBtn').addEventListener('click', () => {
  sessionStorage.removeItem(SESSION_KEY);
  showLogin();
});

$('connectBack').addEventListener('click', () => {
  sessionStorage.removeItem(SESSION_KEY);
  showLogin();
});

$('connectForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const token = $('tokenInput').value.trim();
  const note = $('connectNote');
  if (!token) return;

  $('connectBtn').disabled = true;
  note.className = 'note';
  note.textContent = 'Проверяем...';

  localStorage.setItem(TOKEN_KEY, token);
  try {
    const repo = await gh(`/repos/${GH_OWNER}/${GH_REPO}`);
    if (!repo) throw new Error('Нет доступа к репозиторию');
    note.textContent = 'Готово!';
    $('tokenInput').value = '';
    route();
  } catch (err) {
    localStorage.removeItem(TOKEN_KEY);
    note.className = 'note note--error';
    note.textContent = 'Не получилось: ' + err.message;
  } finally {
    $('connectBtn').disabled = false;
  }
});

// ---------- загрузка каталога ----------
async function refresh() {
  status('Загружаем каталог...');
  try {
    await resolveBranch();
    items = await loadCatalog();
    selected.clear();
    removed = [];
    status('');
    render();
  } catch (err) {
    status('Ошибка: ' + err.message, 'error');
    if (/bad credentials|requires authentication/i.test(err.message)) {
      localStorage.removeItem(TOKEN_KEY);
      showConnect();
    }
  }
}

// ---------- фото ----------
$('photoInput').addEventListener('change', async (e) => {
  const files = Array.from(e.target.files || []);
  if (!files.length) return;

  status(`Обрабатываем ${files.length} фото...`);
  for (let n = 0; n < files.length; n++) {
    status(`Обрабатываем фото ${n + 1} из ${files.length}...`);
    try {
      const dataUrl = await drdResizeImage(files[n]);
      const name = files[n].name.replace(/\.[^.]+$/, '').slice(0, 60) || 'Новый образ';
      items.push({ id: drdGenId(), name, price: '', tags: [], image: null, _new: true, _dataUrl: dataUrl });
    } catch (err) {
      console.warn('Не удалось обработать файл', files[n].name, err);
    }
  }
  e.target.value = '';
  status(`Добавлено ${files.length} фото — поставь теги и нажми «Опубликовать»`, 'ok');
  render();
});

// ---------- теги ----------
function buildTagBar(container, handler) {
  container.innerHTML = '';
  DRD_TAGS.forEach((slug) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'tagchip';
    b.dataset.tag = slug;
    b.textContent = shortTag(slug);
    b.addEventListener('click', () => handler(slug));
    container.appendChild(b);
  });
}

/* Ставит тег всем выделенным; если он уже у всех — снимает. */
function applyTagToSelection(slug) {
  const chosen = items.filter((i) => selected.has(i.id));
  if (!chosen.length) return;
  const everyone = chosen.every((i) => (i.tags || []).includes(slug));

  chosen.forEach((i) => {
    i.tags = i.tags || [];
    if (everyone) {
      i.tags = i.tags.filter((t) => t !== slug);
    } else if (!i.tags.includes(slug)) {
      i.tags.push(slug);
    }
    i._dirty = true;
    paintTags(i);
  });
  syncTagBar();
  updateBars();
}

/* Обновляет теги одной плитки, не перерисовывая галерею целиком. */
function paintTags(item) {
  const tile = gallery.querySelector(`.tile[data-id="${item.id}"]`);
  if (!tile) return;
  const html = (item.tags || []).map((t) => `<span class="minitag">${esc(shortTag(t))}</span>`).join('');
  let box = tile.querySelector('.tile__tags');
  if (!html) {
    if (box) box.remove();
    return;
  }
  if (!box) {
    box = document.createElement('div');
    box.className = 'tile__tags';
    tile.querySelector('.tile__edit').before(box);
  }
  box.innerHTML = html;
}

function syncTagBar() {
  const chosen = items.filter((i) => selected.has(i.id));
  $('tagBar').querySelectorAll('.tagchip').forEach((chip) => {
    const tag = chip.dataset.tag;
    const count = chosen.filter((i) => (i.tags || []).includes(tag)).length;
    chip.classList.toggle('is-on', chosen.length > 0 && count === chosen.length);
    chip.classList.toggle('is-partial', count > 0 && count < chosen.length);
  });
}

// ---------- выделение ----------
function paintSelection() {
  gallery.querySelectorAll('.tile').forEach((tile) => {
    tile.classList.toggle('is-selected', selected.has(tile.dataset.id));
  });
}

function toggleSelect(id) {
  if (selected.has(id)) selected.delete(id);
  else selected.add(id);
  paintSelection();
  updateBars();
}

$('selectAllBtn').addEventListener('click', () => {
  if (selected.size === items.length) selected.clear();
  else items.forEach((i) => selected.add(i.id));
  paintSelection();
  updateBars();
});

$('clearSelBtn').addEventListener('click', () => {
  selected.clear();
  paintSelection();
  updateBars();
});

$('closeTags').addEventListener('click', () => {
  selected.clear();
  paintSelection();
  updateBars();
});

$('deleteSelBtn').addEventListener('click', () => {
  const chosen = items.filter((i) => selected.has(i.id));
  if (!chosen.length) return;
  if (!confirm(`Удалить ${chosen.length} образ(ов) из каталога?`)) return;
  chosen.forEach((i) => { if (!i._new) removed.push(i); });
  items = items.filter((i) => !selected.has(i.id));
  selected.clear();
  render();
});

// ---------- редактор ----------
function openEditor(id) {
  const item = items.find((i) => i.id === id);
  if (!item) return;
  editingId = id;
  $('editName').value = item.name || '';
  $('editPrice').value = item.price || '';
  const img = $('editPreview');
  const src = item._dataUrl || item.image;
  img.hidden = !src;
  if (src) img.src = src;
  syncEditTags();
  editSheet.hidden = false;
}

function closeEditor() {
  editSheet.hidden = true;
  editingId = null;
}

function toggleEditTag(slug) {
  const item = items.find((i) => i.id === editingId);
  if (!item) return;
  item.tags = item.tags || [];
  if (item.tags.includes(slug)) item.tags = item.tags.filter((t) => t !== slug);
  else item.tags.push(slug);
  item._dirty = true;
  syncEditTags();
}

function syncEditTags() {
  const item = items.find((i) => i.id === editingId);
  $('editTags').querySelectorAll('.tagchip').forEach((chip) => {
    chip.classList.toggle('is-on', !!item && (item.tags || []).includes(chip.dataset.tag));
  });
}

document.querySelectorAll('[data-close-sheet]').forEach((el) =>
  el.addEventListener('click', (e) => {
    e.preventDefault();
    closeEditor();
    render();
  })
);

$('editForm').addEventListener('submit', (e) => {
  e.preventDefault();
  const item = items.find((i) => i.id === editingId);
  if (item) {
    item.name = $('editName').value.trim();
    item.price = $('editPrice').value.trim();
    item._dirty = true;
  }
  closeEditor();
  render();
});

// ---------- публикация ----------
$('publishBtn').addEventListener('click', async () => {
  const btn = $('publishBtn');
  btn.disabled = true;
  try {
    await publishAll();
    status('Опубликовано! Сайт обновится через 1–2 минуты.', 'ok');
    render();
  } catch (err) {
    status('Ошибка публикации: ' + err.message, 'error');
  } finally {
    btn.disabled = false;
  }
});

// ---------- отрисовка ----------
/* Обновляет счётчики и нижние панели без перерисовки галереи. */
function updateBars() {
  $('countLabel').textContent = `${items.length} образов`;
  $('selectAllBtn').textContent = selected.size === items.length && items.length ? 'Снять все' : 'Выбрать все';
  $('clearSelBtn').hidden = selected.size === 0;

  const anySelected = selected.size > 0;
  actionBar.hidden = !anySelected;
  if (anySelected) {
    $('selCount').textContent = `${selected.size} выбрано`;
    syncTagBar();
  }

  const dirty = hasChanges();
  publishBar.hidden = !dirty || anySelected;
  if (dirty) {
    const added = items.filter((i) => i._new).length;
    $('publishInfo').textContent = added
      ? `${added} новых фото готово`
      : 'Есть несохранённые изменения';
  }

  reserveBarSpace();
}

/* Резервирует под галереей место под плавающую панель, чтобы нижние фото не перекрывались. */
function reserveBarSpace() {
  requestAnimationFrame(() => {
    const bar = !actionBar.hidden ? actionBar : (!publishBar.hidden ? publishBar : null);
    const h = bar ? bar.getBoundingClientRect().height : 0;
    document.documentElement.style.setProperty('--bar-h', h + 'px');
  });
}

function render() {
  if (!items.length) {
    gallery.innerHTML = '<p class="gallery__empty">Пока пусто. Нажми «Добавить фото» — можно выбрать сразу несколько снимков.</p>';
  } else {
    gallery.innerHTML = items
      .map((item) => {
        const src = item._dataUrl || item.image;
        const tags = (item.tags || []).map((t) => `<span class="minitag">${esc(shortTag(t))}</span>`).join('');
        return `
          <div class="tile ${selected.has(item.id) ? 'is-selected' : ''}" data-id="${item.id}">
            <div class="tile__media" data-select="${item.id}">
              ${src ? `<img src="${src}" alt="" loading="lazy">` : DRD_DEFAULT_ICON}
              <span class="tile__check">✓</span>
              ${item._new ? '<span class="tile__badge">ново</span>' : ''}
            </div>
            <div class="tile__body">
              <div class="tile__name">${esc(item.name)}</div>
              <div class="tile__price">${esc(item.price) || '—'}</div>
              ${tags ? `<div class="tile__tags">${tags}</div>` : ''}
              <button type="button" class="tile__edit" data-edit="${item.id}">Изменить</button>
            </div>
          </div>`;
      })
      .join('');

    gallery.querySelectorAll('[data-select]').forEach((el) =>
      el.addEventListener('click', () => toggleSelect(el.dataset.select))
    );
    gallery.querySelectorAll('[data-edit]').forEach((el) =>
      el.addEventListener('click', () => openEditor(el.dataset.edit))
    );
  }

  updateBars();
}

route();
