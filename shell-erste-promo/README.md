# Shell × Erste — „25 liter üzemanyag ingyen!" (15", HU)

Рекламный ролик 15 секунд для акции Shell Hungary и Erste Bank: **25 литров топлива бесплатно для всех клиентов Erste Bank, 12–20 октября 2026**.

| Файл | Что это |
|---|---|
| `out/Shell_Erste_25L_15s_1080p25.mp4` | мастер, 1920×1080, 25 fps, H.264 ~24 Мбит/с, AAC 320k, −14 LUFS |
| `out/Shell_Erste_25L_15s_web.mp4` | лёгкая версия для мессенджеров и соцсетей (~8 МБ) |
| `out/storyboard.jpg` | раскадровка по готовому ролику |
| `out/banners/*.png` | баннеры для Facebook/Instagram: A «Aranyóra» и B «0 Ft», в форматах 4:5 и 1:1 |
| `out/banners/FB_ad_copy.md` | тексты объявлений (HU + RU) |

## Монтажный лист

Склейки стоят на сетке трека (126 BPM), дроп музыки приходится ровно на пэкшот (11,0 с).

| Время | Кадр | Звук / текст |
|---|---|---|
| 0,00–1,55 | Полёт над озером на закате (Балатон) | VO: „Minden út tartogat valami jót." Титр синхронно со словами |
| 1,55–3,45 | Тёмный SUV в контровом солнце, блик скользит по крыше | засветка на склейке |
| 3,45–4,40 | Станция Shell на закате, 2.5D-движение камеры | свиш-панорама; VO: „Erste Bank ügyfélként most…"; плашка *Erste Bank ügyfeleknek* |
| 4,40–5,34 | Станция Shell, колонка V-Power | |
| 5,34–7,23 | Крупно: рука берёт пистолет «95» | „…huszonöt liter / üzemanyag / vár Önre ingyen!" — титры **25 LITER / ÜZEMANYAG / INGYEN!** точно на слова |
| 7,23–9,13 | Графика: касание карты Erste → «ELFOGADVA», счётчик 0,00 → **25,00 L**, к оплате **0 Ft**, штамп INGYEN! | бип оплаты, тики счётчика, сигнал на 25,00 |
| 9,13–10,54 | Водитель садится, уверенно захлопывает дверь | хлопок двери на кадре закрытия |
| 10,54–11,00 | SUV уезжает в солнце, вспышка в белое | старт двигателя, реверс-свуш в дроп |
| 11,00–15,00 | Пэкшот: логотипы Shell и Erste, **25 LITER ÜZEMANYAG INGYEN!**, *Erste Bank ügyfeleknek.*, плашка **2026. október 12–20.**, теглайн и юридическая строка | дроп; VO: „Shell és Erste. Több lehetőség az Ön mindennapjaira." |

Юридическая строка: *„Az akció 2026. október 12-től 20-ig minden Erste Bank-ügyfél számára érvényes a Shell-töltőállomásokon. A kép illusztráció."*

## Источники и права

- **Видео:** Mixkit (Mixkit Free License) — *Flying over a lake at sunset* (7829), *SUV in a road at sunset, tracking shot* (35261), *Man's hand taking the fuel nozzle at the gas station* (31961); Coverr (Coverr License) — *Woman approaching and entering her car*. Бесплатно для коммерческого использования, без указания авторства. Mixkit в бесплатном тарифе отдаёт 720p, поэтому эти клипы апскейлены до 1080p.
- **Станция Shell:** правая часть ключевых визуалов кампании, присланных заказчиком (`assets/kv`). Карты глубины для параллакса посчитаны моделью Depth Anything V2 Small.
- **Музыка:** „Golden Storm" — Diego Nava (Mixkit Free License).
- **SFX:** Mixkit Sound Effects (свуши, дверь, двигатель, тоны оплаты).
- **Голос:** синтез Microsoft Edge TTS, `hu-HU-TamasNeural`, с пословными таймкодами (`assets/vo/*.json`).
- **Логотипы Shell / Erste и карта:** векторные реконструкции в коде (`src/brand.js`).

Сырые стоковые файлы в репозиторий не кладутся: лицензии запрещают распространять их как сток. Их скачивает `tools/fetch_assets.sh`.

## Перед эфиром

1. Заменить векторные логотипы на официальные файлы из брендбуков Shell и Erste.
2. Перезаписать голос профессиональным диктором. Тайминг уже рассчитан на текущие реплики: VO1 0,25 с, VO2 3,55 с, VO3 11,25 / 12,60 с.
3. Для ТВ: перевести громкость на −23 LUFS (EBU R128) и подложить стоковые клипы в 4K, либо снять свою станцию Shell.
4. Согласовать юридическую формулировку условий акции с банком.

## Баннеры

`node src/banner_render.mjs` рендерит `src/banners.js` в PNG и JPG. Фото станции увеличено в 4 раза нейросетью Real-ESRGAN (`tools/upscale.py`, файл `assets/kv/station_a_x4.jpg`). Перед выпуском баннеры прошли независимую проверку по четырём направлениям: эффективность в ленте и читаемость на телефоне, вёрстка, венгерский текст и юридическая строка, фирменный стиль. Принятые правки внесены.

## Сборка

```bash
./build.sh            # fetch assets → plates → mix → frames → MP4
```

Нужны: Python 3 с `numpy scipy opencv-python-headless onnxruntime librosa edge-tts`, Node 22 с Playwright (Chromium) и ffmpeg.

- `tools/plates.py`: ретайм, апскейл, цветокоррекция (тёплые света и бирюзовые тени), 2.5D-параллакс для фотографий.
- `src/*.js`: композитинг в headless Chromium (титры, блики, засветки, графика оплаты, пэкшот, зерно, letterbox).
- `tools/audio.py`: монтаж трека под дроп, дакинг под голос, SFX, лимитер.
