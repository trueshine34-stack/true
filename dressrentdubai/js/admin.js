const ADMIN_LOGIN = 'admin';
const ADMIN_PASSWORD = 'Admin555';
const ADMIN_SESSION_KEY = 'drd_admin_session';

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
  renderList();
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

// --- Catalog management ---
const itemForm = document.getElementById('itemForm');
const itemIdField = document.getElementById('itemId');
const itemImage = document.getElementById('itemImage');
const preview = document.getElementById('preview');
const adminList = document.getElementById('adminList');
const itemCount = document.getElementById('itemCount');
const formTitle = document.getElementById('formTitle');
const submitBtn = document.getElementById('submitBtn');
const cancelEditBtn = document.getElementById('cancelEdit');

let pendingImage = null;

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
  pendingImage = null;
  preview.hidden = true;
  formTitle.textContent = 'Добавить образ';
  submitBtn.textContent = 'Добавить в каталог';
  cancelEditBtn.hidden = true;
}

cancelEditBtn.addEventListener('click', resetForm);

itemForm.addEventListener('submit', (e) => {
  e.preventDefault();
  const catalog = drdLoadCatalog();
  const id = itemIdField.value;
  const name = document.getElementById('itemName').value.trim();
  const cat = document.getElementById('itemCat').value;
  const price = document.getElementById('itemPrice').value.trim();
  const tags = document.getElementById('itemTags').value
    .split(',')
    .map(t => t.trim())
    .filter(Boolean);

  if (id) {
    const existing = catalog.find(i => i.id === id);
    if (existing) {
      existing.name = name;
      existing.cat = cat;
      existing.price = price;
      existing.tags = tags;
      if (pendingImage) existing.image = pendingImage;
    }
  } else {
    catalog.push({
      id: drdGenId(),
      name, cat, price, tags,
      image: pendingImage,
      icon: '👗',
    });
  }

  drdSaveCatalog(catalog);
  resetForm();
  renderList();
});

function editItem(id) {
  const catalog = drdLoadCatalog();
  const item = catalog.find(i => i.id === id);
  if (!item) return;
  itemIdField.value = item.id;
  document.getElementById('itemName').value = item.name;
  document.getElementById('itemCat').value = item.cat;
  document.getElementById('itemPrice').value = item.price;
  document.getElementById('itemTags').value = (item.tags || []).join(', ');
  pendingImage = item.image;
  if (item.image) {
    preview.src = item.image;
    preview.hidden = false;
  } else {
    preview.hidden = true;
  }
  formTitle.textContent = 'Редактировать образ';
  submitBtn.textContent = 'Сохранить изменения';
  cancelEditBtn.hidden = false;
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function deleteItem(id) {
  if (!confirm('Удалить этот образ из каталога?')) return;
  const catalog = drdLoadCatalog().filter(i => i.id !== id);
  drdSaveCatalog(catalog);
  renderList();
}

function renderList() {
  const catalog = drdLoadCatalog();
  itemCount.textContent = catalog.length;

  if (!catalog.length) {
    adminList.innerHTML = '<p class="admin-empty">Каталог пуст — добавьте первый образ слева.</p>';
    return;
  }

  adminList.innerHTML = catalog.map(item => `
    <div class="admin-row">
      <div class="admin-row__media">${item.image ? `<img src="${item.image}" alt="">` : (item.icon || '👗')}</div>
      <div class="admin-row__body">
        <strong>${item.name}</strong>
        <span>${DRD_CATEGORIES[item.cat] || item.cat} · ${item.price}</span>
        ${item.tags && item.tags.length ? `<div class="card__tags">${item.tags.map(t => `<span class="tag">${t}</span>`).join('')}</div>` : ''}
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
