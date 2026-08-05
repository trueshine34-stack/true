/* Общее хранилище каталога для сайта и админки (localStorage браузера) */

const DRD_STORAGE_KEY = 'drd_catalog_v1';

const DRD_CATEGORIES = {
  evening: 'Вечернее',
  cocktail: 'Коктейльное',
  wedding: 'Гостье на свадьбу',
  accessories: 'Аксессуары',
};

const DRD_DEFAULT_CATALOG = [
  { id: 'd1', name: 'Elie Saab Midnight', cat: 'evening', price: '850 AED', tags: ['новинка'], image: null, icon: '👗' },
  { id: 'd2', name: 'Zuhair Murad Aurora', cat: 'evening', price: '1 200 AED', tags: ['премиум'], image: null, icon: '👗' },
  { id: 'd3', name: 'Rose Cocktail', cat: 'cocktail', price: '420 AED', tags: [], image: null, icon: '💃' },
  { id: 'd4', name: 'Emerald Silk', cat: 'cocktail', price: '380 AED', tags: [], image: null, icon: '💃' },
  { id: 'd5', name: 'Golden Guest', cat: 'wedding', price: '650 AED', tags: [], image: null, icon: '🌸' },
  { id: 'd6', name: 'Blush Elegance', cat: 'wedding', price: '590 AED', tags: [], image: null, icon: '🌸' },
  { id: 'd7', name: 'Diamond Set', cat: 'accessories', price: '250 AED', tags: [], image: null, icon: '💎' },
  { id: 'd8', name: 'Satin Clutch', cat: 'accessories', price: '120 AED', tags: [], image: null, icon: '👛' },
];

function drdLoadCatalog() {
  try {
    const raw = localStorage.getItem(DRD_STORAGE_KEY);
    if (raw) return JSON.parse(raw);
  } catch (e) {
    console.warn('Не удалось прочитать каталог из localStorage', e);
  }
  drdSaveCatalog(DRD_DEFAULT_CATALOG);
  return DRD_DEFAULT_CATALOG.slice();
}

function drdSaveCatalog(items) {
  localStorage.setItem(DRD_STORAGE_KEY, JSON.stringify(items));
}

function drdGenId() {
  return 'item_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
}

/* Сжимает изображение до разумного размера перед сохранением в localStorage */
function drdResizeImage(file, maxSize = 900, quality = 0.82) {
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
