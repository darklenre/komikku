# Bubble Zoom per Komikku — Piano / lavoro residuo

*Documento operativo. Requisiti in `komikku-bubble-zoom-progetto.md`. Coordinamento team in `claude-com.md` (Claude) e `antigravity-com.md` (Antigravity). Base: commit `936e25bf99` (1.14.1, vc 81). Branch: `feature/bubble-zoom` → fork `github.com/darklenre/komikku`.*

*Direzione corrente (aggiornata 29 ago): feature estesa a **doppio motore** + **doppia modalità di zoom**, tutto dietro `bubble_zoom` (`default = false`). L'utente ha accettato lo scope esteso; resta aperto il nodo licenza del modello di segmentazione (§7).*

---

## 1. Stato — architettura attuale

Implementazione completa (Pager + Webtoon). Modifiche nel working tree; parte committata su `feature/bubble-zoom`, parte ancora da committare (WIP Antigravity §6 + fix Claude 29 ago).

### 1.1 Primitiva + overlay

- `ReaderPageImageView`: `focusOnRect()` / `resetZoom()` / `viewToSourceCoord()` / `sourceImageSize()` — animazione `withInterruptible(false)` (Gotcha 1). Aggiunto `cropSourceRect(srcRect)` — rende una sotto-regione della view in un `Bitmap` (fallback per la modalità FLOATING).
- `BubbleZoomOverlayView` — overlay full-screen che consuma i gesti. Due stili (`ZoomStyle`):
  - **`IN_PLACE`**: zoom della tavola sulla nuvoletta via `focusOnRect` (SSIV).
  - **`FLOATING`**: ritaglio della nuvoletta (`BubbleExtractor`) mostrato ingrandito e centrato (fino a 92% W / 85% H), card bianca con ombra, **backdrop scuro opzionale** (`bubble_zoom_floating_backdrop`, default on).
  - Comune: swipe = bubble succ./prec., tap/back = esci; contatore "N / M"; hint di uscita alla prima attivazione (2.5 s); haptic enter/step; page-turn su swipe oltre la prima/ultima bubble (`advanceBubbleZoom` con poll).

### 1.2 Package `eu.kanade.tachiyomi.ui.reader.bubble`

- `Bubble(rect: RectF /* 0..1 */, confidence: Float, maskBitmap: Bitmap? = null)` — `maskBitmap` = maschera ARGB (solo motore `seg`).
- `interface BubbleDetector { val isAvailable; suspend fun detect(bitmap): List<Bubble> }` + `NoopBubbleDetector`.
- `OnnxBubbleDetector` — ONNX Runtime, modello `bubble_detector_ogkalu.onnx`. Buffer preallocati (`inputBuffer`/`pixelBuffer`), dispatcher single-thread, `close()`.
- `TfliteBubbleDetector` — TensorFlow Lite, modello `bubble_detector_seg.tflite`. GPU delegate (`CompatibilityList`) con fallback XNNPACK CPU; auto-detect shape input NCHW/NHWC e output detect/seg; **mmap dell'asset** (no copia in `filesDir`); buffer preallocati; `close()`.
- `BubblePostprocess` (condiviso): `Letterbox`, `letterboxOf`, `decodeDetections(featureMajor, lb, coordsIn640Space, …, protoMasks?)`, `iou`, `Det`. Con `protoMasks` != null costruisce la `maskBitmap` per box (sigmoid su 32 coeff. × proto `[1,32,160,160]`).
- `BubbleExtractor` — dato `page.stream` + rect normalizzato, `BitmapRegionDecoder.decodeRegion` a risoluzione nativa → `processCroppedBitmap` applica angoli arrotondati (12%) + eventuale `maskBitmap` neurale (`DST_IN`). Usato dalla modalità FLOATING.
- `BubbleDetection` (façade): `isSupported` (64-bit + `!isLowRamDevice` + RAM ≥ 2 GiB), `LruCache<String, List<Bubble>>(32)` che **ricicla le `maskBitmap`** in `entryRemoved`, `activeDetections: StateFlow<Int>` (indicatore), `detectorFor()` sceglie il motore da `bubbleZoomEngine()`, `onEngineChanged()` (chiude il detector, svuota la cache).
- `BubbleReadingOrder` (LTR/RTL/VERTICAL) — bande per Y poi **colonne per X dentro la banda** (`bandFactor = 0.5`), colonne in direzione di lettura, colonna dall'alto in basso.
- `bubbleKeyFor(page)` — `"${chapterId}:${index}"` + `:insert` per `InsertPage`.

### 1.3 Aggancio detection

- `PagerPageHolder` / `WebtoonPageHolder.setImage()`: se `viewer.config.bubbleZoomEnabled && BubbleDetection.isSupported && cache miss` → `BubbleDetection.detect(...)` sul **bitmap di display finale** (post split/merge/rotate/crop), in background, `bitmap.recycle()` in `finally`.
- Al long-tap / double-tap: `PagerViewer` / `WebtoonViewer.tryEnterBubbleZoom(page, x, y, style)` legge le bubble **già in cache** (no attesa) → hit-test → `activity.enterBubbleZoom(target, page, bubbles, index, style, backdrop)`.

### 1.4 Preferenze

| Pref | Chiave | Default | Uso |
|---|---|---|---|
| attivazione | `bubble_zoom` | `false` | gate globale della feature |
| motore | `bubble_zoom_engine` | `"seg"` | `"seg"` (TFLite YOLOv8-seg) \| `"ogkalu"` (ONNX detect + contour CV) |
| gesto zoom tavola | `bubble_zoom_in_place_gesture` | `"long_tap"` | `long_tap` \| `double_tap` \| `disabled` |
| gesto ritaglio flottante | `bubble_zoom_floating_gesture` | `"double_tap"` | `long_tap` \| `double_tap` \| `disabled` |
| backdrop scuro (FLOATING) | `bubble_zoom_floating_backdrop` | `true` | oscura la pagina dietro il ritaglio |
| hint mostrato | `bubble_zoom_hint_shown` | `false` | interno |

Tutte le pref reader `.register(...)` su `ViewerConfig` (tranne `bubble_zoom_floating_backdrop`, letto da `ReaderActivity.enterBubbleZoom`).

### 1.5 Dipendenze / build

- `com.microsoft.onnxruntime:onnxruntime-android:1.20.0` + `org.tensorflow:tensorflow-lite:2.16.1` (+ `-gpu`, `-gpu-api`).
- `app/build.gradle.kts`: `noCompress += "tflite"` (+ `"onnx"`), blocco `aboutLibraries { collect { configPath = file("aboutlibraries-config") } }`.
- `proguard-rules.pro`: keep rules per `ai.onnxruntime.**`, `org.tensorflow.lite.**`, `com.google.ai.edge.litert.**` (Gotcha 2).

### 1.6 Modelli bundlati

| File | Byte | Sorgente | Licenza |
|---|---|---|---|
| `bubble_detector_ogkalu.onnx` | 26,321,282 (26.3 MB) | `ogkalu/comic-speech-bubble-detector-yolov8m` @ `21d5af0a` | Apache-2.0 ✅ |
| `bubble_detector_seg.tflite` | ~46,880,952 (46.9 MB) | `AksharPatel/manga-speech-bubble-segmentation` | **GPL-3.0 ⚠️ — vedi §7** |

`bubble_detector.tflite` (vecchio, non referenziato) **rimosso** dal tracking il 29 ago.

---

## 2. Lavoro residuo

| # | Item | Stato / note |
|---|---|---|
| 1 | Latenza reale su ARM | ✅ Fold8: **ONNX** ~217 ms mediana (19 pag, CPU 1 thread); **TFLite GPU delegate** ~123 ms mediana (14 pag). Da rimisurare col modello `seg` (output multi-tensore + proto-mask decode). |
| 2 | Peso APK release | ⚠️ **da rimisurare.** Stima attuale con `_ogkalu.onnx` (26) + `_seg.tflite` (47) + runtime nativi ONNX (~18) + TFLite (~4–8) ≈ **+95–100 MB arm64** vs baseline. Oltre il ~+62 MB stimato prima; dipende dalla decisione §7. `default = false`. |
| 3 | Indicatore di elaborazione | ✅ `BubbleDetection.activeDetections` → `LinearProgressIndicator` indeterminato in cima all'overlay Compose di `ReaderActivity`. |
| 4 | Sezione impostazioni "Bubble Zoom" | ✅ `SettingsReaderScreen.getBubbleZoomGroup()` — switch attivazione + `ListPreference` motore (`onValueChanged → onEngineChanged()`) + `ListPreference` gesto tavola + `ListPreference` gesto flottante + `SwitchPreference` backdrop. Tutte `enabled = supported && bubbleZoom`. |
| 5 | Doppio motore (ONNX ogkalu + TFLite seg) | ✅ implementato (§5 work order storico). `detectorFor()` sceglie da `bubble_zoom_engine`; `BubblePostprocess` condiviso; `onEngineChanged()` su cambio da impostazioni. |
| 6 | Attribuzione licenza | ✅ per **ogkalu** (`LICENSE` Apache-2.0 + `NOTICE` + voce AboutLibraries offline). ⚠️ Il modello `seg` è GPL-3.0: **non** basta una voce NOTICE — vedi §7. |
| 7 | Traduzioni | Stringhe `KMR` solo in `base` (EN). IT e altre via Weblate dopo il merge. Non toccare i file non-`base`. |
| 8 | Issue upstream | Aprire issue su `komikku-app/komikku` prima della PR (`CONTRIBUTING.md`): precedente TachiyomiJ2K (#573, #1566), feature dietro pref `default = false`, modelli bundlati + peso APK, **stato licenza del modello seg**. |
| 9 | Reading-order | ✅ clustering in colonne dentro la banda, `bandFactor = 0.5`, validato su playground (k006 RTL, Saga LTR, k009, JJK). Limite residuo (band-merge su griglie di pannelli) → §4. |
| 10 | Page-turn in Webtoon | ✅ `WebtoonViewer.advanceBubbleZoom(forward)` con poll + `onScrolled(pos)` per sincronizzare `currentPage`. |
| 11 | **Modalità FLOATING — visualizzazione** | ✅ **bug "1 pixel" risolto** (29 ago): i `Bubble` passati all'overlay hanno rect in px sorgente, `BubbleExtractor` li vuole normalizzati → il ritaglio collassava a 1×1 nell'angolo. `ensureCurrentExtracted` ora ri-normalizza col `sourceImageSize()`. Da validare su device. |
| 12 | **Qualità codice §6 (WIP Antigravity)** | ⏳ vedi `claude-com.md` §9: **C1** scope per-pagina non gestito in `*PageHolder` (`CoroutineScope(Dispatchers.IO).launch`), **C2** `BubbleExtractor` chiamato da `onDraw()` (I/O su thread UI → pre-estrarre in `enter()`/`step()`), **C4** `Pager.onRestoreInstanceState` `setCurrentItem` → `super.` non spiegato. Da sistemare prima della PR. |
| 13 | Test on-device modalità estesa | ⏳ Fold8: FLOATING (long/double-tap, backdrop on/off), doppio gesto indipendente, cambio motore a caldo, page-turn in entrambe le modalità. |

### 2.1 Fix applicati il 29 ago (Claude, non committati) — dettaglio in `claude-com.md` §7/§9

- Ripristinato il gate `config.bubbleZoomEnabled` in `Pager`/`WebtoonPageHolder` (la detection girava per **tutti** gli utenti).
- Ripristinato `onScrolled(pos)` in `WebtoonViewer.advanceBubbleZoom`.
- Rimossa sonda `logcat(INFO)` in `PagerViewer.tryEnterBubbleZoom`.
- `gradle.properties` riportato a HEAD (tuning locale macchina — **mai committare**).
- Bug FLOATING "1 pixel" (item 11).
- **A3**: `TfliteBubbleDetector` mmap dell'asset invece di copia in `filesDir` + `readBytes()` intero; `loadModelBuffer` non è più dead code.
- **C5**: `BubbleDetection` LruCache ricicla le `maskBitmap` in `entryRemoved`.
- Rimosso `bubble_detector.tflite` morto (47 MB).

### 2.2 Sezione impostazioni "Bubble Zoom" (stato reale)

`getBubbleZoomGroup(readerPreferences)` in `SettingsReaderScreen`, registrata dopo `getActionsGroup`. Tutte le voci `enabled = bubbleZoomSupported && isBubbleZoomEnabled` (tranne lo switch di attivazione, `enabled = bubbleZoomSupported`).

| Voce | Tipo | Pref |
|---|---|---|
| Enable Bubble Zoom | `SwitchPreference` | `bubble_zoom` |
| Detection engine | `ListPreference` | `bubble_zoom_engine` — `seg` / `ogkalu`, `onValueChanged → BubbleDetection.onEngineChanged()` |
| In-place page zoom gesture | `ListPreference` | `bubble_zoom_in_place_gesture` — long/double/disabled |
| Floating bubble cutout gesture | `ListPreference` | `bubble_zoom_floating_gesture` — long/double/disabled |
| Dim the page behind the cutout | `SwitchPreference` | `bubble_zoom_floating_backdrop` |

Stringhe base `KMR` (EN) in `i18n-kmk/.../base/strings.xml`: `pref_category_bubble_zoom`, `pref_bubble_zoom_enable(_summary)`, `pref_bubble_zoom_engine`, `bubble_zoom_engine_seg(_desc)`, `bubble_zoom_engine_ogkalu(_desc)`, `pref_bubble_zoom_in_place_gesture`, `pref_bubble_zoom_floating_gesture`, `pref_bubble_zoom_floating_backdrop(_summary)`, `bubble_zoom_gesture_long_tap` / `_double_tap` / `_disabled`, `bubble_zoom_hint`. *(Note: `*_engine_*_desc` definite ma non ancora usate dal `ListPreference`.)*

---

## 3. Riferimenti tecnici

- **Sorgente `.pt` ogkalu** (rigenerare l'ONNX, non runtime): `https://huggingface.co/ogkalu/comic-speech-bubble-detector-yolov8m/resolve/21d5af0a9c69bb522b9968d6cc915f898d71a264/comic-speech-bubble-detector.pt` — SHA-256 `10bc9f702698148e079fb4462a6b910fcd69753e04838b54087ef91d5633097b`.
- **Modello seg**: `AksharPatel/manga-speech-bubble-segmentation` (HF), YOLOv8-seg, **GPL-3.0**. Output: detect head + proto-mask `[1, 32, 160, 160]`, 32 coeff. maschera per box.
- **`bubble_detector_ogkalu.onnx`**: 26,321,282 B, SHA-256 `3eecb94fe991553ea8d923d1134d97496f1bb884be69fd2b48a61eabe6fdc8b3`. Export int8 Ultralytics (`export(format='onnx', imgsz=640, int8=True, data=calib_yolo/data.yaml, simplify=True)`), calibrazione 340 immagini (17 titoli). NB: `quantize_static` QDQ generico distrugge il modello (0 detection) — solo l'export Ultralytics funziona.
- **Playground** `/mnt/data/src/kotlin/bubbleZoomPlayground/` — engine Kotlin puro (letterbox 640, decode `[1,5,8400]`, NMS IoU 0.45, reading-order, hit-test), `YoloDetBubbleDetector` = fonte di verità postprocess. `gradle -q installDist` (no wrapper, gradle di sistema). CLI `detect --model <onnx> --image <img> --dir RTL|LTR --band <f> --out <png>`.
- **I/O modello detect**: input letterbox 640 `/255` grey 114; output `[1,5,8400]` = `cx,cy,w,h,score` in spazio 640 (ONNX) o normalizzato 0..1 (TFLite — `TfliteBubbleDetector` auto-detecta con `maxCoord > 1.5`); soglia 0.30, NMS 0.45.
- **Gotcha 1** — `focusOnRect`/`resetZoom` **devono** usare `animateScaleAndCenter(...).withInterruptible(false)`; `focusCurrent()` fa `t.post { }` per lo stesso motivo (coda eventi del long-press annulla l'animazione).
- **Gotcha 2** — R8 (`preview`/`release`) elimina le classi istanziate solo via JNI dal nativo → `SIGABRT` `mid == null`. Keep rules in `proguard-rules.pro` per `ai.onnxruntime.**`, `org.tensorflow.lite.**`, `com.google.ai.edge.litert.**`.
- **Gotcha 3** — `Bubble.rect` normalizzato 0..1 sul **bitmap finale** SSIV (`sWidth×sHeight`), non sul file originale. I `Bubble` che `PagerViewer`/`WebtoonViewer.bubbleDetections()` passano all'overlay sono invece **in px sorgente** (per hit-test / `focusOnRect`): la modalità FLOATING ri-normalizza prima di `BubbleExtractor` (item 11).
- **Decisione chiusa** — il modello rileva anche caption/narration box: si tengono (è testo da ingrandire), nessun filtro per forma.
- **Test device** — Fold8 wireless debugging, `192.168.178.93:34199`, package `app.komikku.beta` (`preview`, debug-signed). Procedura completa in `claude-com.md` §8.

---

## 4. Post-v1 (fuori scope, dopo il merge)

- **Hit-test poligonale.** Anche col motore `seg` l'hit-test è ancora `rect.contains(x, y)` sul bounding box. Usare la `maskBitmap` (o il contorno CV di `BubbleExtractor`) anche per il **hit-test**, non solo per il ritaglio → selezione corretta su bubble oblique/ovali/sovrapposte.
- **Panel/gutter awareness nel reading-order.** Su griglie di pannelli complesse la clusterizzazione Y a media mobile unisce bubble di righe di pannelli adiacenti (Saga p12 #8). Serve rilevare i gutter / i pannelli.
- **Override per-manga on/off.** Un solo switch globale oggi; Komikku ha i viewer flags per-manga (`SetMangaViewerFlags`): esporre "Bubble Zoom: predefinito / sempre / mai" nelle impostazioni serie.
- **Toggle nei quick settings in-reader** (`GeneralSettingsPage.kt`): accendere/spegnere senza uscire dalla lettura.
- **Classi testo per OCR/TTS.** Modello a una sola classe ("bubble"); modelli come `ogkalu/comic-text-and-bubble-detector` separano il testo → OCR → TTS / traduzione.

---

## 5. Work order storico — secondo motore TFLite + LiteRT (delegato ad Antigravity) — ✅ completato

*Spec eseguita. Mantenuta come riferimento; la direzione è poi cambiata da "TFLite come secondo motore dallo stesso `.pt`" a "TFLite = modello di segmentazione dedicato". `coordsIn640Space` è auto-detectato a runtime (`maxCoord > 1.5`) anziché flag fisso. Il modello `seg` produce un tensore proto-mask in più, gestito in `decodeDetections(protoMasks=…)`.*

Punti chiave eseguiti: `BubblePostprocess` condiviso (5.1); deps + `noCompress "tflite"` (5.2); `TfliteBubbleDetector` con GPU delegate + XNNPACK fallback, buffer preallocati, `close()` (5.4); `detectorFor()` da `bubbleZoomEngine()` + `onEngineChanged()` (5.5); sezione impostazioni (5.6 → §2.2); keep rules R8 (5.7). Criteri di parità postprocess (5.8): rivalidare i conteggi ground-truth col modello `seg` (output diverso).

---

## 6. Modalità FLOATING (Ritaglio sagoma & doppio gesto) — ✅ implementata, in rifinitura

### 6.1 UX
- **Trigger**: gesto configurabile per stile (`bubble_zoom_in_place_gesture` / `bubble_zoom_floating_gesture`, ognuno long/double/disabled). Se il tocco cade fuori da ogni nuvoletta → comportamento nativo (menu azioni / zoom 2×).
- **Effetto FLOATING**: ritaglio della nuvoletta a risoluzione nativa (`BitmapRegionDecoder`), angoli arrotondati + eventuale maschera neurale, mostrato centrato (≤ 92% W / ≤ 85% H) su card bianca con ombra.
- **Backdrop**: scuro semitrasparente, **abilitabile/disabilitabile** da `bubble_zoom_floating_backdrop` (default on). Off → si vede il fumetto attorno al ritaglio.
- **A regime**: swipe = bubble succ./prec. (page-turn ai bordi); tap singolo / back = uscita.

### 6.2 Estrazione forma
- **`seg`**: maschera pixel dal modello (`BubblePostprocess` → `Bubble.maskBitmap` → `BubbleExtractor.processCroppedBitmap` con `DST_IN`).
- **`ogkalu`**: nessuna maschera ML → `BubbleExtractor` applica solo angoli arrotondati (contorno CV luminanza/flood-fill previsto ma minimale). Fallback ultimo: `ReaderPageImageView.cropSourceRect`.

### 6.3 Work order — ✅ fatto
1. [x] `BubbleExtractor.kt` — ritaglio nativo + mask alpha.
2. [x] `BubbleZoomOverlayView.kt` — `ZoomStyle` IN_PLACE / FLOATING, backdrop opzionale, card+ombra.
3. [x] Gesture routing in `Pager.kt` / `PagerViewer.kt` / `WebtoonRecyclerView.kt` / `WebtoonViewer.kt` (long + double tap, fallback nativo).
4. [x] Preferenze + UI (§2.2): motore `seg`/`ogkalu`, gesti indipendenti, backdrop toggle.
5. [x] Verifiche iniziali su Fold8.
6. [ ] **Rifinitura pre-PR**: C1/C2/C4 (item 12), validazione FLOATING su device dopo il fix "1 pixel" (item 11/13), decisione licenza (§7).

---

## 7. Nodo aperto — licenza del modello di segmentazione

`bubble_detector_seg.tflite` = `AksharPatel/manga-speech-bubble-segmentation`, **GPL-3.0**. Komikku/Mihon è **Apache-2.0**: la GPL-3.0 (copyleft) non è ridistribuibile dentro un APK Apache-2.0 e non si sana con l'attribuzione. Blocca l'item 8 (PR upstream) e è un rischio anche per il fork.

Opzioni (decisione utente rimandata):
1. **Solo `ogkalu` ONNX** (Apache-2.0). FLOATING resta ma senza maschere pixel (angoli arrotondati / contorno CV). Rimuovere `_seg.tflite`, la voce `seg` dal `ListPreference`, le stringhe relative, e — se non serve più TFLite — anche `tensorflow-lite*` + keep rules.
2. **Modello seg permissivo** — sostituire con un YOLOv8-seg Apache/MIT/BSD (cercare su HF o ri-esportare da un `.pt` permissivo). Tiene le maschere.
3. **GPL solo per il fork** — nessuna PR upstream con questo modello; documentare il vincolo nel `NOTICE` del fork.

Finché non deciso: **non committare `bubble_detector_seg.tflite`** e non fare la PR.
