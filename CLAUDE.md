# Проект: DressRentDubai

Сайт студии аренды образов в Дубае. Код сайта — в подпапке `dressrentdubai/`.

## Актуальные ссылки

> Держать этот раздел в актуальном состоянии: при каждой смене ветки/URL/PR — обновлять
> здесь ссылки в том же коммите, а не только сообщать их в чате.

- Репозиторий: https://github.com/trueshine34-stack/true (публичный)
- Рабочая ветка: `claude/dressrentdubai-website-w9cyx2`
- Live-сайт (GitHub Pages): https://trueshine34-stack.github.io/true/dressrentdubai/
- Админка: https://trueshine34-stack.github.io/true/dressrentdubai/admin.html
  (логин `admin`, пароль `Admin555`; код доступа GitHub вводится один раз на устройство)
- Открытый PR: https://github.com/trueshine34-stack/true/pull/1

GitHub Pages сейчас настроен на источник — ветка `claude/dressrentdubai-website-w9cyx2`, папка `/ (root)`.
После мерджа PR в `main` нужно: переключить источник Pages на `main` (ссылка на сайт останется той же)
и обновить в админке (Настройки GitHub) поле "Ветка" на `main`.

## Архитектура

- Статический сайт (HTML/CSS/JS), без сервера.
- Каталог образов публикуется в `dressrentdubai/data/catalog.json`, публичная страница
  просто делает `fetch()` этого файла.
- Изображения коммитятся в `dressrentdubai/images/catalog/`.
- Админка (`admin.html`) пишет в репозиторий напрямую из браузера через GitHub Git Data API:
  все изменения (новые фото + `catalog.json`) уходят **одним коммитом**.
- Owner/repo зашиты в `js/admin.js`; ветка определяется автоматически (ветка разработки,
  иначе `main`), поэтому после мерджа PR настраивать ничего не нужно.
- Токен НЕ хранится в репозитории (он публичный — GitHub отозвал бы любой закоммиченный
  токен). Администратор вводит его один раз, дальше он лежит в localStorage его браузера.
- Админка мобильная: массовый выбор фото, групповая простановка тегов на выделенные
  карточки, bottom-sheet редактор. Теги в админке показываются коротко (`#desert`),
  на публичном сайте — полностью (`#rentdressdubai_desert`).
