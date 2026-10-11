// Traducerile panoului de administrare (română / rusă). Cheile lipsă cad pe română.
import { plural } from '../../lib/format.js';
const RO = {
  'nav.dashboard': 'Panou', 'nav.orders': 'Comenzi', 'nav.products': 'Produse', 'nav.customers': 'Clienți', 'nav.promos': 'Promoții',
  'nav.delivery': 'Livrare', 'nav.emails': 'Emailuri', 'nav.messages': 'Mesaje', 'nav.settings': 'Setări', 'nav.audit': 'Jurnal',
  'nav.store': 'Vezi magazinul', 'nav.logout': 'Ieșire', 'nav.sales': 'Vânzări', 'nav.catalog': 'Catalog', 'nav.system': 'Sistem',
  'search.orders': 'Caută comandă, email, telefon, AWB…',
  'dash.title': 'Bună, {name}', 'dash.sub': 'Iată ce se întâmplă în magazin.', 'dash.revenue_today': 'Vânzări azi', 'dash.revenue_30': 'Vânzări 30 de zile',
  'dash.orders_30': 'Comenzi 30 de zile', 'dash.aov': 'Valoare medie comandă', 'dash.new_customers': 'Clienți noi (30 de zile)', 'dash.to_process': 'De procesat',
  'dash.vs_prev': 'față de perioada anterioară', 'dash.revenue_chart': 'Vânzări pe zi, ultimele 30 de zile', 'dash.orders_chart': 'Comenzi pe zi',
  'dash.status': 'Comenzi pe status (30 de zile)', 'dash.top': 'Cele mai vândute (30 de zile)', 'dash.delivery': 'Metode de livrare (30 de zile)',
  'dash.low_stock': 'Stoc scăzut', 'dash.recent': 'Comenzi recente', 'dash.see_all': 'Vezi toate', 'dash.table': 'Vezi ca tabel', 'dash.payment': 'Metode de plată (30 de zile)',
  'col.order': 'Comandă', 'col.date': 'Data', 'col.customer': 'Client', 'col.total': 'Total', 'col.status': 'Status', 'col.payment': 'Plată', 'col.delivery': 'Livrare',
  'col.items': 'Produse', 'col.product': 'Produs', 'col.sku': 'SKU', 'col.mpn': 'Cod Apple', 'col.price': 'Preț', 'col.old_price': 'Preț vechi', 'col.stock': 'Stoc',
  'col.active': 'Activ', 'col.category': 'Categorie', 'col.variants': 'Variante', 'col.revenue': 'Încasări', 'col.qty': 'Buc.', 'col.email': 'Email',
  'col.orders': 'Comenzi', 'col.spent': 'Total cheltuit', 'col.registered': 'Înregistrat', 'col.verified': 'Confirmat', 'col.code': 'Cod',
  'col.type': 'Tip', 'col.value': 'Valoare', 'col.used': 'Folosit', 'col.valid': 'Valabil până', 'col.method': 'Metodă', 'col.eta': 'Termen',
  'col.free_over': 'Gratuit de la', 'col.cod': 'Ramburs', 'col.day': 'Zi', 'col.action': 'Acțiune', 'col.who': 'Cine', 'col.details': 'Detalii',
  'all': 'Toate', 'filter': 'Filtrează', 'reset': 'Resetează', 'save': 'Salvează', 'saved': 'Modificările au fost salvate.', 'cancel': 'Anulează', 'yes': 'Da', 'no': 'Nu',
  'prev': 'Înapoi', 'next': 'Înainte', 'export_csv': 'Export CSV', 'results': '{n} rezultate', 'none': 'Nimic de afișat.', 'back': 'Înapoi',
  'orders.title': 'Comenzi', 'order.items': 'Produse', 'order.customer': 'Client', 'order.delivery': 'Livrare', 'order.payment': 'Plată', 'order.billing': 'Facturare',
  'order.timeline': 'Istoric', 'order.change_status': 'Schimbă statusul', 'order.notify': 'Trimite email clientului', 'order.note': 'Notă (vizibilă în email)',
  'order.awb': 'AWB (număr de urmărire)', 'order.apply': 'Aplică', 'order.admin_notes': 'Note interne', 'order.invoice': 'Proformă / factură', 'order.mark_paid': 'Marchează plătită',
  'order.customer_notes': 'Mențiuni client', 'order.open_map': 'Vezi pe hartă', 'order.track': 'Urmărește', 'order.guest': 'fără cont', 'order.company': 'Firmă',
  'products.title': 'Produse', 'products.new': 'Produs nou', 'products.edit': 'Editează produsul', 'products.basic': 'Informații', 'products.variants': 'Variante, prețuri și stoc',
  'products.images': 'Imagini', 'products.upload': 'Încarcă imagine', 'products.for_key': 'Pentru', 'products.name': 'Nume', 'products.slug': 'Slug (URL)',
  'products.tagline': 'Descriere scurtă', 'products.description': 'Descriere (un paragraf pe linie)', 'products.highlights': 'Puncte forte (unul pe linie)',
  'products.badge': 'Etichetă', 'products.featured': 'Recomandat pe prima pagină', 'products.active': 'Vizibil în magazin', 'products.preorder': 'Precomandă',
  'products.release': 'Notă precomandă', 'products.group': 'Grupă', 'products.view': 'Vezi în magazin', 'products.low': 'stoc mic', 'products.out': 'epuizat',
  'products.price_hint': 'Prețurile sunt în lei, cu TVA.', 'products.bulk': 'Ajustare în masă', 'products.bulk_pct': 'Modifică prețurile cu (%)',
  'customers.title': 'Clienți', 'customer.block': 'Suspendă contul', 'customer.unblock': 'Reactivează contul', 'customer.resend': 'Retrimite emailul de confirmare',
  'customer.addresses': 'Adrese', 'customer.orders': 'Comenzi', 'customer.marketing': 'Abonat la noutăți',
  'promos.title': 'Coduri promoționale', 'promos.new': 'Cod nou', 'promos.percent': 'Procent', 'promos.fixed': 'Sumă fixă', 'promos.shipping': 'Transport gratuit',
  'promos.min': 'Minim comandă (lei)', 'promos.max': 'Reducere maximă (lei)', 'promos.uses': 'Utilizări maxime', 'promos.until': 'Valabil până la', 'promos.desc': 'Descriere',
  'promos.value': 'Valoare (% sau lei)', 'promos.first': 'prima comandă', 'promos.first_hint': 'Doar la prima comandă a clientului', 'promos.expired': 'expirat', 'promos.delete': 'Șterge',
  'delivery.title': 'Livrare', 'delivery.methods': 'Metode de livrare', 'delivery.lockers': 'Lockere', 'delivery.import': 'Importă lockere (JSON)',
  'delivery.import_hint': 'Format: {"lockers":[{"id","provider","kind","name","address","city","county","postcode","lat","lng","hours","note"}]} — kind: locker sau point. Lista completă se reconstruiește cu tools/build_lockers.py.',
  'delivery.sources': 'Surse și acoperire', 'delivery.official': 'listă oficială', 'delivery.partial': 'parțial (OpenStreetMap) — conectați contul Sameday', 'delivery.updated': 'Actualizat la {date}',
  'col.lockers': 'Lockere', 'col.points': 'Puncte', 'col.counties': 'Județe', 'col.source': 'Sursă',
  'emails.title': 'Emailuri', 'emails.templates': 'Șabloane', 'emails.log': 'Emailuri trimise', 'emails.test': 'Trimite un test la adresa mea', 'emails.smtp_off': 'SMTP nu este configurat: emailurile se salvează local (var/outbox).',
  'emails.smtp_on': 'SMTP activ: {host}', 'messages.title': 'Mesaje de contact', 'messages.handled': 'Rezolvat', 'messages.mark': 'Marchează rezolvat',
  'settings.title': 'Setări', 'settings.company': 'Date firmă', 'settings.showroom': 'Showroom', 'settings.store': 'Magazin', 'settings.placeholder': 'Datele sunt exemple — înlocuiește-le cu datele reale ale firmei.',
  'audit.title': 'Jurnal de activitate', 'lang': 'Limba',
  'login.title': 'Panou Livada', 'login.sub': 'Autentificare pentru echipă', 'login.btn': 'Intră în panou',
};

const RU = {
  'nav.dashboard': 'Обзор', 'nav.orders': 'Заказы', 'nav.products': 'Товары', 'nav.customers': 'Клиенты', 'nav.promos': 'Промокоды',
  'nav.delivery': 'Доставка', 'nav.emails': 'Письма', 'nav.messages': 'Сообщения', 'nav.settings': 'Настройки', 'nav.audit': 'Журнал',
  'nav.store': 'Открыть магазин', 'nav.logout': 'Выйти', 'nav.sales': 'Продажи', 'nav.catalog': 'Каталог', 'nav.system': 'Система',
  'search.orders': 'Поиск: заказ, email, телефон, AWB…',
  'dash.title': 'Привет, {name}', 'dash.sub': 'Что происходит в магазине.', 'dash.revenue_today': 'Продажи сегодня', 'dash.revenue_30': 'Продажи за 30 дней',
  'dash.orders_30': 'Заказы за 30 дней', 'dash.aov': 'Средний чек', 'dash.new_customers': 'Новые клиенты (30 дней)', 'dash.to_process': 'Ждут обработки',
  'dash.vs_prev': 'к прошлому периоду', 'dash.revenue_chart': 'Продажи по дням, последние 30 дней', 'dash.orders_chart': 'Заказы по дням',
  'dash.status': 'Заказы по статусам (30 дней)', 'dash.top': 'Лидеры продаж (30 дней)', 'dash.delivery': 'Способы доставки (30 дней)',
  'dash.low_stock': 'Заканчиваются на складе', 'dash.recent': 'Последние заказы', 'dash.see_all': 'Все', 'dash.table': 'Показать таблицей', 'dash.payment': 'Способы оплаты (30 дней)',
  'col.order': 'Заказ', 'col.date': 'Дата', 'col.customer': 'Клиент', 'col.total': 'Сумма', 'col.status': 'Статус', 'col.payment': 'Оплата', 'col.delivery': 'Доставка',
  'col.items': 'Товары', 'col.product': 'Товар', 'col.sku': 'Артикул', 'col.mpn': 'Код Apple', 'col.price': 'Цена', 'col.old_price': 'Старая цена', 'col.stock': 'Остаток',
  'col.active': 'Активен', 'col.category': 'Категория', 'col.variants': 'Варианты', 'col.revenue': 'Выручка', 'col.qty': 'Шт.', 'col.email': 'Email',
  'col.orders': 'Заказы', 'col.spent': 'Всего потрачено', 'col.registered': 'Регистрация', 'col.verified': 'Подтверждён', 'col.code': 'Код',
  'col.type': 'Тип', 'col.value': 'Значение', 'col.used': 'Использован', 'col.valid': 'Действует до', 'col.method': 'Способ', 'col.eta': 'Срок',
  'col.free_over': 'Бесплатно от', 'col.cod': 'Наложенный', 'col.day': 'День', 'col.action': 'Действие', 'col.who': 'Кто', 'col.details': 'Детали',
  'all': 'Все', 'filter': 'Фильтр', 'reset': 'Сбросить', 'save': 'Сохранить', 'saved': 'Изменения сохранены.', 'cancel': 'Отмена', 'yes': 'Да', 'no': 'Нет',
  'prev': 'Назад', 'next': 'Вперёд', 'export_csv': 'Экспорт CSV', 'results': 'Найдено: {n}', 'none': 'Пока пусто.', 'back': 'Назад',
  'orders.title': 'Заказы', 'order.items': 'Товары', 'order.customer': 'Клиент', 'order.delivery': 'Доставка', 'order.payment': 'Оплата', 'order.billing': 'Счёт',
  'order.timeline': 'История', 'order.change_status': 'Изменить статус', 'order.notify': 'Отправить письмо клиенту', 'order.note': 'Комментарий (попадёт в письмо)',
  'order.awb': 'AWB (трек-номер)', 'order.apply': 'Применить', 'order.admin_notes': 'Внутренние заметки', 'order.invoice': 'Счёт / проформа', 'order.mark_paid': 'Отметить оплаченным',
  'order.customer_notes': 'Комментарий клиента', 'order.open_map': 'На карте', 'order.track': 'Отследить', 'order.guest': 'без аккаунта', 'order.company': 'Компания',
  'products.title': 'Товары', 'products.new': 'Новый товар', 'products.edit': 'Редактирование товара', 'products.basic': 'Основное', 'products.variants': 'Варианты, цены и остатки',
  'products.images': 'Фото', 'products.upload': 'Загрузить фото', 'products.for_key': 'Для', 'products.name': 'Название', 'products.slug': 'Slug (URL)',
  'products.tagline': 'Короткое описание', 'products.description': 'Описание (абзац на строку)', 'products.highlights': 'Ключевые преимущества (по одному на строку)',
  'products.badge': 'Метка', 'products.featured': 'Рекомендовать на главной', 'products.active': 'Показывать в магазине', 'products.preorder': 'Предзаказ',
  'products.release': 'Текст предзаказа', 'products.group': 'Группа', 'products.view': 'Открыть в магазине', 'products.low': 'мало', 'products.out': 'нет в наличии',
  'products.price_hint': 'Цены в леях, с НДС.', 'products.bulk': 'Массовое изменение', 'products.bulk_pct': 'Изменить цены на (%)',
  'customers.title': 'Клиенты', 'customer.block': 'Заблокировать', 'customer.unblock': 'Разблокировать', 'customer.resend': 'Отправить письмо подтверждения повторно',
  'customer.addresses': 'Адреса', 'customer.orders': 'Заказы', 'customer.marketing': 'Подписан на рассылку',
  'promos.title': 'Промокоды', 'promos.new': 'Новый промокод', 'promos.percent': 'Процент', 'promos.fixed': 'Фикс. сумма', 'promos.shipping': 'Бесплатная доставка',
  'promos.min': 'Мин. сумма заказа (лей)', 'promos.max': 'Макс. скидка (лей)', 'promos.uses': 'Макс. использований', 'promos.until': 'Действует до', 'promos.desc': 'Описание',
  'promos.value': 'Значение (% или лей)', 'promos.first': 'первый заказ', 'promos.first_hint': 'Только на первый заказ клиента', 'promos.expired': 'истёк', 'promos.delete': 'Удалить',
  'delivery.title': 'Доставка', 'delivery.methods': 'Способы доставки', 'delivery.lockers': 'Постаматы', 'delivery.import': 'Импорт постаматов (JSON)',
  'delivery.import_hint': 'Формат: {"lockers":[{"id","provider","kind","name","address","city","county","postcode","lat","lng","hours","note"}]} — kind: locker или point. Полный список пересобирается tools/build_lockers.py.',
  'delivery.sources': 'Источники и покрытие', 'delivery.official': 'официальный список', 'delivery.partial': 'частично (OpenStreetMap) — подключите аккаунт Sameday', 'delivery.updated': 'Обновлено {date}',
  'col.lockers': 'Постаматы', 'col.points': 'Пункты', 'col.counties': 'Уезды', 'col.source': 'Источник',
  'emails.title': 'Письма', 'emails.templates': 'Шаблоны', 'emails.log': 'Отправленные письма', 'emails.test': 'Отправить тест себе', 'emails.smtp_off': 'SMTP не настроен: письма сохраняются локально (var/outbox).',
  'emails.smtp_on': 'SMTP активен: {host}', 'messages.title': 'Сообщения с сайта', 'messages.handled': 'Обработано', 'messages.mark': 'Отметить обработанным',
  'settings.title': 'Настройки', 'settings.company': 'Реквизиты компании', 'settings.showroom': 'Шоурум', 'settings.store': 'Магазин', 'settings.placeholder': 'Это примерные данные — замените реальными реквизитами.',
  'audit.title': 'Журнал действий', 'lang': 'Язык',
  'login.title': 'Панель Livada', 'login.sub': 'Вход для команды', 'login.btn': 'Войти в панель',
};

const STATUS_RU = { new: 'Новый', confirmed: 'Подтверждён', processing: 'Собирается', shipped: 'Отправлен', ready: 'Готов к выдаче', delivered: 'Доставлен', cancelled: 'Отменён', returned: 'Возврат' };
const PAY_RU = { pending: 'Ожидает', paid: 'Оплачен', cod: 'Наложенный', refunded: 'Возвращён', failed: 'Не прошёл' };
const PAYM_RU = { card: 'Карта онлайн', cod: 'Наложенный платёж', transfer: 'Банковский перевод', installments: 'Рассрочка 0%' };

export function translator(lang) {
  const dict = lang === 'ru' ? RU : RO;
  const t = (key, vars = {}) => String(dict[key] ?? RO[key] ?? key).replace(/\{(\w+)\}/g, (_, k) => vars[k] ?? '');
  t.lang = lang === 'ru' ? 'ru' : 'ro';
  t.status = (s, fallback) => (lang === 'ru' ? STATUS_RU[s] : null) || fallback || s;
  t.pay = (s, fallback) => (lang === 'ru' ? PAY_RU[s] : null) || fallback || s;
  t.paym = (s, fallback) => (lang === 'ru' ? PAYM_RU[s] : null) || fallback || s;
  /** „3 comenzi” / „3 заказа” — pluralul corect în ambele limbi */
  t.orders = (n) => {
    if (t.lang === 'ru') {
      const m10 = n % 10; const m100 = n % 100;
      return `${n} ${m10 === 1 && m100 !== 11 ? 'заказ' : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? 'заказа' : 'заказов'}`;
    }
    return plural(n, 'comandă', 'comenzi', 'de comenzi');
  };
  return t;
}
