const ADMIN_LOGIN = 'admin';
const ADMIN_PASSWORD = 'Admin555';
const ADMIN_SESSION_KEY = 'drd_admin_session';
const GH_SETTINGS_KEY = 'drd_gh_settings';
const REPO_BASE = 'dressrentdubai/';
const GH_API = 'https://api.github.com';

const loginScreen = document.getElementById('loginScreen');
const dashboard = document.getElementById('dashboard');
const loginForm = document.getElementById('loginForm');
const loginError = document.getElementById('loginError');

function isAuthed() {
  return sessionStorage.getItem(ADMIN_SESSION_KEY) === '1';
}

function showDashboard() {
  loginScreen.hidden = true;
  dashboard.hidden = false;
  loadSettingsIntoForm();
  buildTagGrid();
  refreshList();
}

function showLogin() {
  loginScreen.hidden = false;
  dashboard.hidden = true;
}

if (isAuthed()) showDashboard(); else showLogin();

loginForm.addEventListener('submit', (e) => {
  e.preventDefault();
  const user = document.getElementById('loginUser').value.trim();
  const pass = document.getElementById('loginPass').value;
  if (user === ADMIN_LOGIN && pass === ADMIN_PASSWORD) {
    sessionStorage.setItem(ADMIN_SESSION_KEY, '1');
    loginError.textContent = '';
    loginForm.reset();
    showDashboard();
  } else {
    loginError.textContent = 'Неверный логин или пароль';
  }
});

document.getElementById('logoutBtn').addEventListener('click', () => {
  sessionStorage.removeItem(ADMIN_SESSION_KEY);
  showLogin();
});

// --- GitHub settings ---
function loadSettings() {
  try {
    return JSON.parse(localStorage.getItem(GH_SETTINGS_KEY)) || {};
  } catch (e) {
    return {};
  }
}

function saveSettings(settings) {
  localStorage.setItem(GH_SETTINGS_KEY, JSON.stringify(settings));
}

function loadSettingsIntoForm() {
  const s = loadSettings();
  document.getElementById('ghOwner').value = s.owner || 'trueshine34-stack';
  document.getElementById('ghRepo').value = s.repo || 'true';
  document.getElementById('ghBranch').value = s.branch || 'claude/dressrentdubai-website-w9cyx2';
  document.getElementById('ghToken').value = s.token || '';
  const connected = !!(s.owner && s.repo && s.branch && s.token);
  updateConnStatus(connected);
  if (!connected) document.getElementById('settingsPanel').open = true;
}

function updateConnStatus(connected) {
  const el = document.getElementById('connStatus');
  el.textContent = connected ? 'GitHub подключён' : 'GitHub не подключён';
  el.classList.toggle('conn-status--on', connected);
  el.classList.toggle('conn-status--off', !connected);
}

document.getElementById('saveSettings').addEventListener('click', async () => {
  const settings = {
    owner: document.getElementById('ghOwner').value.trim(),
    repo: document.getElementById('ghRepo').value.trim(),
    branch: document.getElementById('ghBranch').value.trim(),
    token: document.getElementById('ghToken').value.trim(),
  };
  const note = document.getElementById('settingsNote');

  if (!settings.owner || !settings.repo || !settings.branch || !settings.token) {
    note.textContent = 'Заполните все поля.';
    updateConnStatus(false);
    return;
  }

  note.textContent = 'Проверяем подключение...';
  try {
    const res = await fetch(`${GH_API}/repos/${settings.owner}/${settings.repo}`, { headers: ghHeaders(settings.token) });
    if (!res.ok) throw new Error((await safeJson(res)).message || `HTTP ${res.status}`);
    saveSettings(settings);
    note.textContent = 'Готово, подключение работает.';
    updateConnStatus(true);
    refreshList();
  } catch (err) {
    note.textContent = 'Ошибка подключения: ' + err.message;
    updateConnStatus(false);
  }
});

// --- GitHub API helpers ---
function ghHeaders(token) {
  return {
    Authorization: `Bearer ${token}`,
    Accept: 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
  };
}

async function safeJson(res) {
  try { return await res.json(); } catch (e) { return {}; }
}

function ghEncodePath(path) {
  return path.split('/').map(encodeURIComponent).join('/');
}

function b64EncodeUnicode(str) {
  return btoa(unescape(encodeURIComponent(str)));
}

function b64DecodeUnicode(str) {
  return decodeURIComponent(escape(atob(str.replace(/\n/g, ''))));
}

async function ghGetFile(path, settings) {
  const url = `${GH_API}/repos/${settings.owner}/${settings.repo}/contents/${ghEncodePath(path)}?ref=${encodeURIComponent(settings.branch)}`;
  const res = await fetch(url, { headers: ghHeaders(settings.token) });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error((await safeJson(res)).message || `GitHub API ${res.status}`);
  return res.json();
}

async function ghPutFile(path, contentBase64, message, sha, settings) {
  const url = `${GH_API}/repos/${settings.owner}/${settings.repo}/contents/${ghEncodePath(path)}`;
  const body = { message, content: contentBase64, branch: settings.branch };
  if (sha) body.sha = sha;
  const res = await fetch(url, {
    method: 'PUT',
    headers: { ...ghHeaders(settings.token), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error((await safeJson(res)).message || `GitHub API ${res.status}`);
  return res.json();
}

async function ghDeleteFile(path, message, sha, settings) {
  const url = `${GH_API}/repos/${settings.owner}/${settings.repo}/contents/${ghEncodePath(path)}`;
  const res = await fetch(url, {
    method: 'DELETE',
    headers: { ...ghHeaders(settings.token), 'Content-Type': 'application/json' },
    body: JSON.stringify({ message, sha, branch: settings.branch }),
  });
  if (!res.ok) throw new Error((await safeJson(res)).message || `GitHub API ${res.status}`);
  return res.json();
}

function fullPath(relPath) {
  return REPO_BASE + relPath;
}

async function fetchRemoteCatalog(settings) {
  const file = await ghGetFile(fullPath(DRD_CATALOG_PATH), settings);
  if (!file) return { items: [], sha: null };
  return { items: JSON.parse(b64DecodeUnicode(file.content)), sha: file.sha };
}

// --- Tag grid ---
function buildTagGrid() {
  const grid = document.getElementById('tagGrid');
  grid.innerHTML = DRD_TAGS.map(slug => `
    <label class="tag-check">
      <input type="checkbox" value="${slug}">
      <span>${drdTagLabel(slug)}</span>
    </label>
  `).join('');
}

function getSelectedTags() {
  return Array.from(document.querySelectorAll('#tagGrid input:checked')).map(el => el.value);
}

function setSelectedTags(tags) {
  document.querySelectorAll('#tagGrid input').forEach(el => {
    el.checked = (tags || []).includes(el.value);
  });
}

// --- Item form ---
const itemForm = document.getElementById('itemForm');
const itemIdField = document.getElementById('itemId');
const itemImagePathField = document.getElementById('itemImagePath');
const itemImage = document.getElementById('itemImage');
const preview = document.getElementById('preview');
const adminList = document.getElementById('adminList');
const itemCount = document.getElementById('itemCount');
const formTitle = document.getElementById('formTitle');
const submitBtn = document.getElementById('submitBtn');
const cancelEditBtn = document.getElementById('cancelEdit');
const formNote = document.getElementById('formNote');

let pendingImage = null;
let currentCatalog = [];

itemImage.addEventListener('change', async () => {
  const file = itemImage.files[0];
  if (!file) return;
  pendingImage = await drdResizeImage(file);
  preview.src = pendingImage;
  preview.hidden = false;
});

function resetForm() {
  itemForm.reset();
  itemIdField.value = '';
  itemImagePathField.value = '';
  pendingImage = null;
  preview.hidden = true;
  setSelectedTags([]);
  formTitle.textContent = 'Добавить образ';
  submitBtn.textContent = 'Опубликовать в каталог';
  cancelEditBtn.hidden = true;
}

cancelEditBtn.addEventListener('click', resetForm);

itemForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const settings = loadSettings();
  if (!settings.owner || !settings.repo || !settings.branch || !settings.token) {
    formNote.textContent = 'Сначала заполните и сохраните настройки подключения к GitHub выше.';
    document.getElementById('settingsPanel').open = true;
    return;
  }

  const id = itemIdField.value || drdGenId();
  const isNew = !itemIdField.value;
  const name = document.getElementById('itemName').value.trim();
  const price = document.getElementById('itemPrice').value.trim();
  const tags = getSelectedTags();

  submitBtn.disabled = true;
  formNote.textContent = 'Публикуем в GitHub...';

  try {
    let imagePath = itemImagePathField.value || null;

    if (pendingImage) {
      formNote.textContent = 'Загружаем фото...';
      const relPath = `${DRD_IMAGE_DIR}/${id}.jpg`;
      const full = fullPath(relPath);
      const existing = await ghGetFile(full, settings);
      const base64 = pendingImage.split(',')[1];
      await ghPutFile(full, base64, `${isNew ? 'Добавить' : 'Обновить'} фото: ${name}`, existing ? existing.sha : undefined, settings);
      imagePath = relPath;
    }

    formNote.textContent = 'Обновляем каталог...';
    const { items, sha } = await fetchRemoteCatalog(settings);
    const newItem = { id, name, price, tags, image: imagePath };
    const idx = items.findIndex(i => i.id === id);
    if (idx >= 0) items[idx] = newItem; else items.push(newItem);

    const newContent = b64EncodeUnicode(JSON.stringify(items, null, 2));
    await ghPutFile(fullPath(DRD_CATALOG_PATH), newContent, `${isNew ? 'Добавить' : 'Обновить'} образ: ${name}`, sha, settings);

    currentCatalog = items;
    renderList();
    formNote.textContent = 'Опубликовано! Изменения появятся на сайте в течение 1-2 минут.';
    resetForm();
  } catch (err) {
    formNote.textContent = 'Ошибка публикации: ' + err.message;
  } finally {
    submitBtn.disabled = false;
  }
});

function editItem(id) {
  const item = currentCatalog.find(i => i.id === id);
  if (!item) return;
  itemIdField.value = item.id;
  itemImagePathField.value = item.image || '';
  document.getElementById('itemName').value = item.name;
  document.getElementById('itemPrice').value = item.price || '';
  setSelectedTags(item.tags);
  pendingImage = null;
  if (item.image) {
    preview.src = item.image;
    preview.hidden = false;
  } else {
    preview.hidden = true;
  }
  formTitle.textContent = 'Редактировать образ';
  submitBtn.textContent = 'Сохранить изменения';
  cancelEditBtn.hidden = false;
  formNote.textContent = '';
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

async function deleteItem(id) {
  const item = currentCatalog.find(i => i.id === id);
  if (!item) return;
  if (!confirm(`Удалить «${item.name}» из каталога?`)) return;

  const settings = loadSettings();
  if (!settings.token) {
    formNote.textContent = 'Сначала заполните и сохраните настройки подключения к GitHub выше.';
    return;
  }

  formNote.textContent = 'Удаляем...';
  try {
    const { items, sha } = await fetchRemoteCatalog(settings);
    const remaining = items.filter(i => i.id !== id);
    const newContent = b64EncodeUnicode(JSON.stringify(remaining, null, 2));
    await ghPutFile(fullPath(DRD_CATALOG_PATH), newContent, `Удалить образ: ${item.name}`, sha, settings);

    if (item.image) {
      const imgFull = fullPath(item.image);
      const imgFile = await ghGetFile(imgFull, settings);
      if (imgFile) await ghDeleteFile(imgFull, `Удалить фото: ${item.name}`, imgFile.sha, settings);
    }

    currentCatalog = remaining;
    renderList();
    formNote.textContent = 'Удалено.';
  } catch (err) {
    formNote.textContent = 'Ошибка удаления: ' + err.message;
  }
}

async function refreshList() {
  const settings = loadSettings();
  if (!settings.owner || !settings.repo || !settings.branch || !settings.token) {
    adminList.innerHTML = '<p class="admin-empty">Заполните настройки подключения к GitHub выше, чтобы увидеть и редактировать каталог.</p>';
    itemCount.textContent = '0';
    return;
  }
  adminList.innerHTML = '<p class="admin-empty">Загружаем каталог из GitHub...</p>';
  try {
    const { items } = await fetchRemoteCatalog(settings);
    currentCatalog = items;
    renderList();
  } catch (err) {
    adminList.innerHTML = `<p class="admin-empty">Ошибка загрузки каталога: ${err.message}</p>`;
  }
}

function renderList() {
  itemCount.textContent = currentCatalog.length;

  if (!currentCatalog.length) {
    adminList.innerHTML = '<p class="admin-empty">Каталог пуст — добавьте первый образ слева.</p>';
    return;
  }

  adminList.innerHTML = currentCatalog.map(item => `
    <div class="admin-row">
      <div class="admin-row__media">${item.image ? `<img src="${item.image}" alt="">` : DRD_DEFAULT_ICON}</div>
      <div class="admin-row__body">
        <strong>${item.name}</strong>
        <span>${item.price || ''}</span>
        ${item.tags && item.tags.length ? `<div class="card__tags">${item.tags.map(t => `<span class="tag">${drdTagLabel(t)}</span>`).join('')}</div>` : ''}
      </div>
      <div class="admin-row__actions">
        <button type="button" data-edit="${item.id}" class="icon-btn" title="Редактировать">✎</button>
        <button type="button" data-del="${item.id}" class="icon-btn icon-btn--danger" title="Удалить">✕</button>
      </div>
    </div>
  `).join('');

  adminList.querySelectorAll('[data-edit]').forEach(btn =>
    btn.addEventListener('click', () => editItem(btn.dataset.edit))
  );
  adminList.querySelectorAll('[data-del]').forEach(btn =>
    btn.addEventListener('click', () => deleteItem(btn.dataset.del))
  );
}
