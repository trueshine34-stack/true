/* Общие константы и хелперы каталога: теги, чтение публичных данных, работа с изображениями */

const DRD_TAG_PREFIX = 'rentdressdubai_';

const DRD_TAGS = [
  'feathers', 'desert', 'black', 'red', 'white', 'shine', 'oriental', 'fly',
  'suits', 'wedding', 'mini', 'beach', 'pregnant', 'hat', 'accessories',
  'shoes', 'safari', 'sexy', 'cowboystyle', 'fur', 'stylist',
];

const DRD_DEFAULT_ICON = '👗';

const DRD_CATALOG_PATH = 'data/catalog.json';
const DRD_IMAGE_DIR = 'images/catalog';

function drdTagLabel(slug) {
  return '#' + DRD_TAG_PREFIX + slug;
}

/* Публичная страница: читает опубликованный каталог из репозитория (data/catalog.json).
   Если файл ещё не опубликован (например, локальный просмотр без загрузки) — используется
   пустой каталог, и на странице будет предложено зайти в админку и добавить образы. */
async function drdFetchPublicCatalog() {
  try {
    const res = await fetch(DRD_CATALOG_PATH + '?t=' + Date.now(), { cache: 'no-store' });
    if (!res.ok) return [];
    const data = await res.json();
    return Array.isArray(data) ? data : [];
  } catch (e) {
    console.warn('Не удалось загрузить каталог', e);
    return [];
  }
}

function drdGenId() {
  return 'item_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
}

/* Сжимает изображение до разумного размера перед загрузкой в репозиторий */
function drdResizeImage(file, maxSize = 1000, quality = 0.82) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = reject;
    reader.onload = () => {
      const img = new Image();
      img.onerror = reject;
      img.onload = () => {
        let { width, height } = img;
        if (width > height && width > maxSize) {
          height = Math.round((height * maxSize) / width);
          width = maxSize;
        } else if (height > maxSize) {
          width = Math.round((width * maxSize) / height);
          height = maxSize;
        }
        const canvas = document.createElement('canvas');
        canvas.width = width;
        canvas.height = height;
        canvas.getContext('2d').drawImage(img, 0, 0, width, height);
        resolve(canvas.toDataURL('image/jpeg', quality));
      };
      img.src = reader.result;
    };
    reader.readAsDataURL(file);
  });
}
