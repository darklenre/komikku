# Bubble Zoom per Komikku — Piano / roadmap

*Documento operativo. Requisiti in `komikku-bubble-zoom-progetto.md`. Coordinamento team in `claude-com.md` / `antigravity-com.md`. Branch `feature/bubble-zoom` → fork `github.com/darklenre/komikku`. Base: `936e25bf99`.*

*Direzione corrente (30 ago): **motore unico ogkalu** (detect YOLOv8m TFLite, Apache-2.0) + **rifinitura opzionale MobileSAM**. Il motore di segmentazione è stato rimosso (licenza AGPL) → **tutto Apache-2.0, nodo licenza chiuso**. Feature dietro `bubble_zoom` (default `false`). Lavoro nel working tree, non ancora committato dopo `c500eb802f`.*

---

## 1. Architettura attuale

### 1.1 Overlay + primitiva

- `ReaderPageImageView`: `focusOnRect(px)` / `resetZoom()` / `viewToSourceCoord()` / `sourceImageSize()` / `cropSourceRect(pxRect)` / `setGestureZoomEnabled()`. `focusOnRect` usa `animateScaleAndCenter(...).withInterruptible(false)` (Gotcha 1).
- `BubbleZoomOverlayView` — overlay full-screen che consuma i gesti. `enum ZoomStyle { IN_PLACE, FLOATING }`:
  - **IN_PLACE**: zoom della tavola sulla nuvoletta via `focusOnRect`.
  - **FLOATING**: ritaglio (`BubbleExtractor`) mostrato centrato (≤ 92% W / ≤ 85% H) su backdrop scuro **opzionale**; "…" mentre il cutout non è pronto.
  - Comune: swipe = bubble succ./prec. (page-turn ai bordi via `advanceBubbleZoom` + poll); tap/back = esci; contatore "N / M"; hint di uscita (2.5 s) alla prima attivazione; haptic.
  - `enter(target, page, bubbles, startIndex, sourceSize, style, backdrop, …)` — `bubbles` con **rect normalizzati 0..1**; estrazione su dispatcher **single-thread** (`Dispatchers.IO.limitedParallelism(1)`) + prefetch di **1** avanti.

### 1.2 Package `eu.kanade.tachiyomi.ui.reader.bubble`

- `Bubble(rect: RectF /* 0..1 */, confidence: Float)`.
- `interface BubbleDetector` + `NoopBubbleDetector`.
- `TfliteBubbleDetector(context)` — TensorFlow Lite, `models/bubble_detector_ogkalu.tflite` @ 640. GPU delegate (`CompatibilityList`) → fallback XNNPACK CPU; **mmap** dell'asset; auto-detect layout input (NCHW/NHWC) + `inputSize` + trasposizione output; un solo output 3-D (detect head, niente proto).
- `BubblePostprocess` — `Letterbox`, `letterboxOf`, `decodeDetections(featureMajor, lb, coordsIn640Space, conf, iou)` (decode YOLOv8-detect + NMS), `iou`, `Det`.
- `SamRefiner` (object) — MobileSAM box-prompted:
  - encoder `[1,3,1024,1024]` → embedding `[1,256,64,64]`, **per-pagina** (LruCache 4, ~4 MB/embedding); decoder `(embedding, box[1,4] px)` → mask logit `[1,1,256,256]`.
  - `prewarm(...)` (background, `foreground=false`) / `shapeMask(...)` (interattivo, conta in `activeEncodes`). GPU→XNNPACK 4-thread (NNAPI valutato e scartato sul Fold8). Lock separati `initLock`/`encLock`/`decLock`.
  - post-processing maschera: resample bilineare dei logit → filtro **connected-component** attorno al centro box + clamp al box +14% → **box-blur** raggio 3 (feather morbido).
- `BubbleExtractor` (object) — dato `page.stream` + `Bubble` normalizzato + `sourceSize`:
  - `BitmapRegionDecoder.decodeRegion` del box +22% di margine a **risoluzione nativa**.
  - forma per `bubble_zoom_cropping_method`: `"none"` = rettangolo arrotondato 12% · `"sam"` = maschera `SamRefiner` (`DST_IN`, upscale `FILTER_BITMAP`), fallback rettangolo se SAM non disponibile.
  - **outline sticker vettoriale** (se `bubble_zoom_outline`): `traceContour` (Moore-neighbor boundary trace) → `chaikinClosed` ×2 → `Path` → `drawPath` nero `FILL_AND_STROKE` (`strokeWidth = 2·ring`, join/cap ROUND, `ring = pct%` del lato corto) + cutout sopra. Bordo netto con AA del Canvas, niente blur.
  - legge le pref via `Injekt.get<ReaderPreferences>()`.
- `BubbleDetection` (façade) — `isSupported` (64-bit + `!isLowRamDevice` + RAM ≥ 2 GiB), `LruCache<String, List<Bubble>>(32)`, `activeDetections: StateFlow<Int>`, `isPending(key)`, `prewarmSam(context, key, streamFn)` (no-op se metodo ≠ `sam`), `detectorFor()` → sempre `TfliteBubbleDetector`.
- `BubbleReadingOrder` (LTR/RTL/VERTICAL) — bande per Y → colonne per X dentro la banda (`bandFactor = 0.5`) → colonna dall'alto in basso.
- `bubbleKeyFor(page)` — `"${chapterId}:${index}"` + `:insert` per `InsertPage`.

### 1.3 Aggancio

- `PagerPageHolder` / `WebtoonPageHolder.setImage()`: su `bubbleZoomEnabled && isSupported && cache miss` → `BubbleDetection.enqueue(...)` sul bitmap di display finale (background, `recycle()` in `finally`); + `BubbleDetection.prewarmSam(...)`.
- Gesto → `tryEnterBubbleZoom(page, x, y, style)`: legge le bubble **in cache** (no attesa), hit-test in spazio **normalizzato** (`src / size`), `activity.enterBubbleZoom(target, page, bubbles, index, sourceSize, style)`. Se la detection è ancora in corso → toast "Detecting…" (rate-limited 2.5 s).
- Barra di elaborazione (`ReaderActivity`): `LinearProgressIndicator` indeterminato se `BubbleDetection.activeDetections > 0` **||** `SamRefiner.activeEncodes > 0` (solo encode interattivi).

### 1.4 Preferenze

| Chiave | Default | Uso |
|---|---|---|
| `bubble_zoom` | `false` | gate globale |
| `bubble_zoom_in_place_gesture` | `long_tap` | `long_tap` \| `double_tap` \| `disabled` |
| `bubble_zoom_floating_gesture` | `double_tap` | idem |
| `bubble_zoom_cropping_method` | `sam` | `none` (rettangolo) \| `sam` (rifinitura MobileSAM) |
| `bubble_zoom_outline` | `true` | contorno sticker nero |
| `bubble_zoom_outline_width` | `3` | slider 1..8 (% del lato corto) |
| `bubble_zoom_floating_backdrop` | `true` | oscura la pagina dietro il cutout |
| `bubble_zoom_hint_shown` | `false` | interno |

UI: `SettingsReaderScreen.getBubbleZoomGroup()` dopo `getActionsGroup`. Stringhe base `KMR` (EN) in `i18n-kmk/.../base/strings.xml`.

**Override per-serie** (non è una pref): `Manga.viewerFlags` bit 6-7 (`BubbleZoomOverride.MASK = 0xC0`) — `DEFAULT` / `ENABLED` / `DISABLED`. `ViewerConfig.bubbleZoomEnabled = bubbleZoomMangaOverride ?: bubbleZoomGlobal`. Toggle rapido: `ReaderBottomButton.BubbleZoom` (opt-in).

### 1.5 Modelli & build

| File | Byte | Sorgente | Licenza |
|---|---|---|---|
| `bubble_detector_ogkalu.tflite` | 26,516,834 | `ogkalu/comic-speech-bubble-detector-yolov8m` | Apache-2.0 |
| `sam_encoder.tflite` | 28,170,064 | MobileSAM (TinyViT) | Apache-2.0 |
| `sam_decoder.tflite` | 20,640,744 | MobileSAM (mask decoder) | Apache-2.0 |

- Attribuzione: `assets/models/LICENSE` + `NOTICE` + voci AboutLibraries offline (`comic-speech-bubble-detector-yolov8m`, `mobilesam`). **Tutto Apache-2.0.**
- Deps: `org.tensorflow:tensorflow-lite:2.16.1` (+ `-gpu`, `-gpu-api`). **ONNX Runtime rimosso.**
- `app/build.gradle.kts`: `noCompress += "tflite"`. `proguard-rules.pro`: keep `org.tensorflow.lite.**`, `com.google.ai.edge.litert.**` (Gotcha 2).
- Peso APK arm64: ~+75 MB modelli + ~4–8 MB runtime TFLite vs baseline. `default = false`.

---

## 2. Roadmap

### Fase 0 — consolidamento *(si continua sul fork; PR upstream rimandata)* — **fatta**

- [x] Tolti i log INFO diagnostici (`TfliteBubbleDetector` session/page, `SamRefiner` ready/encoded). Restano solo WARN/ERROR.
- [x] **Gate device adattivo**: `SamRefiner` misura la latenza degli encode *interattivi*; dopo 2 encode consecutivi > 9 s → `disabledForSlowDevice` (in-memory, sopravvive a `close()`, un restart ri-valuta) → il cutout torna al rettangolo arrotondato. `RAM ≥ 2 GB` resta il gate d'ingresso.
- [x] SHA-256 dei modelli verificato al load (`ModelIntegrity.mmapVerifiedModel`, condiviso da detector + SAM) → mismatch = init fallita pulita invece di SIGABRT. Rilascio interpreti (`BubbleDetection.releaseDetector` + `SamRefiner.close`) su `ReaderActivity.onTrimMemory(≥ RUNNING_LOW)` e `onDestroy`; la cache dei risultati resta.
- [x] Cancel dell'encode SAM stantio al page-turn: `BubbleDetection` tiene solo i 2 warmup più recenti (`prewarmHandles`), gli altri sono cancellati e saltati all'`encLock` via `stillWanted`.
- [x] Unit test funzioni pure (`app/src/test/.../bubble/`, 22 test): `letterboxOf`, `iou`, `decodeDetsNormalized` (+NMS), `BubbleReadingOrder.orderIndices` (LTR/RTL/griglia 2×2), `traceContour`, `chaikinClosed`. Refactor: `decodeDetections` splittata in core puro + wrapper `Bubble`; `BubbleReadingOrder.sort` → `orderIndices` su `Box` puro.
- [x] Commit del working tree (`69e38ce496`) + questo giro.

### Fase 1 — polish a basso rischio — **fatta**

- [x] **Animazione ingresso/uscita**: `BubbleZoomOverlayView` interpola il cutout FLOATING tra il rect on-page della nuvoletta e il centro — ingresso 200 ms ease-out + fade-in, uscita 160 ms ease-in + fade-out (anche del backdrop) via `exit(animate = true)` da tap/double-tap/back. Start/end-rect via `ReaderPageImageView.sourceToViewRect` nuovo; fallback immediato se la SSIV non è pronta o su page-turn/teardown viewer (`exit()` senza animate).
- [x] **Override per-manga** (`viewerFlags` bit 6-7, mask `0xC0`): `BubbleZoomOverride { DEFAULT, ENABLED, DISABLED }`, `SetMangaViewerFlags.awaitSetBubbleZoom`, `Manga.bubbleZoomForced`. `ViewerConfig.bubbleZoomEnabled` = `mangaOverride ?: global`; applicato in `updateViewer()`. UI: chip row in `ReadingModePage` ("per questa serie"), gated da `isSupported`.
- [x] **Quick toggle in-reader**: `ReaderBottomButton.BubbleZoom` ("bz") → icona nella bottom bar (opt-in dal picker), togglea `bubble_zoom` + toast on/off; nascosta se il device non supporta.
- [x] Warmup più mirato: `BubbleDetection.prewarmSam` salta l'encode di background se `PowerManager.isPowerSaveMode` o `currentThermalStatus ≥ MODERATE`; già limitato ai 2 warmup più recenti in Fase 0 (≈ pagina corrente + successiva). Il cutout interattivo gira comunque.

### Fase 2 — profondità cutout

- [ ] **Outline vettoriale live**: disegnare il `Path` in `onDraw` dell'overlay invece di rasterizzarlo nel bitmap → razor-sharp a qualsiasi scala.
- [ ] **Pinch / pan dentro il cutout flottante** (dipende dal punto sopra) — per nuvolette lunghe il testo resta piccolo.
- [ ] Framing tail-aware: centrare sul testo, non sul centro geometrico del box.

### Fase 3 — profondità detection

- [ ] **Tiling per webtoon**: strisce alte → detection per-tile + merge (ora un solo pass downscalato → nuvolette ~10 px).
- [ ] Slider soglia confidence + toggle "includi caption/narration box".
- [ ] Hit-test poligonale (usare la maschera SAM anche per il hit-test, non solo per il ritaglio).
- [ ] Tap-to-add: long-press su zona non rilevata → cutout da box di default.

### Fase 4 — performance SAM

- [ ] Valutare **EdgeSAM** (RepViT) / **SAM2-tiny** / encoder a **input 512** (re-export, dimezza il compute) / int8 sull'encoder. I ~2 s attuali sono il vero dolore UX.
- [ ] Cache su disco di embedding o cutout finali per hash-pagina → riapertura chapter istantanea.

### Fase 5 — OCR / TTS / traduzione

- [ ] Feature a sé che riusa la pipeline detection + cutout: OCR del testo ritagliato → read-aloud (accessibilità) e/o overlay tradotto. Scope grande (modello OCR da bundlare/scaricare, TTS/traduzione), ma il pezzo difficile è già fatto.

**Dipendenze**: pinch-nel-cutout ⟵ outline vettoriale live · cache disco ⟵ hash-pagina stabile · OCR è indipendente.

---

## 3. Gotcha / riferimenti

- **Gotcha 1** — `focusOnRect`/`resetZoom` **devono** usare `animateScaleAndCenter(...).withInterruptible(false)`; `focusCurrent()` fa `t.post { }` (la coda eventi del long-press annulla l'animazione).
- **Gotcha 2** — R8 (`preview`/`release`) elimina le classi istanziate solo via JNI → `SIGABRT`. Keep rules per `org.tensorflow.lite.**`, `com.google.ai.edge.litert.**`.
- **Gotcha 3** — coordinate: `Bubble.rect` è **normalizzato 0..1** sul bitmap finale SSIV, ovunque. Conversione a px solo dove serve (`focusOnRect`, `cropSourceRect`, box del decoder SAM).
- **SAM su HW** — sul Fold8 (Qualcomm) GPU delegate e NNAPI rifiutano gli op TinyViT → encoder su XNNPACK CPU ~2 s/pagina. Mitigazioni: warmup di background + barra + cue "…".
- **Export modelli** — solo l'export int8 nativo Ultralytics funziona per la testa YOLO (`quantize_static` QDQ generico → 0 detection). `_NormalizeCoords` → coord 0..1 (il detector auto-detecta con `maxCoord > 1.5`).
- **Sorgente `.pt` ogkalu** — `https://huggingface.co/ogkalu/comic-speech-bubble-detector-yolov8m/resolve/21d5af0a…/comic-speech-bubble-detector.pt` (SHA-256 `10bc9f70…097b`).
- **Playground** `/mnt/data/src/kotlin/bubbleZoomPlayground/` — engine Kotlin puro (letterbox 640, decode `[1,5,8400]`, NMS 0.45, reading-order), 5 pagine ground-truth con conteggi noti.
- **Test device** — Fold8 wireless, `192.168.178.93:34199`, package `app.komikku.beta` (`preview`, R8 attivo). Procedura in `claude-com.md` §3.
