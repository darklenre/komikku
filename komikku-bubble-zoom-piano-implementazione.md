# Bubble Zoom per Komikku — Piano / roadmap

*Documento operativo. Requisiti in `komikku-bubble-zoom-progetto.md`. Coordinamento team in `claude-com.md` / `antigravity-com.md`. Branch `feature/bubble-zoom` → fork `github.com/darklenre/komikku`. Base: `936e25bf99`.*

*Direzione corrente (30 ago): **motore unico ogkalu** (detect YOLOv8m TFLite, Apache-2.0) + **rifinitura opzionale MobileSAM**. Il motore di segmentazione è stato rimosso (licenza AGPL) → **tutto Apache-2.0, nodo licenza chiuso**. Feature dietro `bubble_zoom` (default `false`).*

*Stato: **Fasi 0-3 fatte**, committate e pushate sul fork, installate sul Fold8. Restano solo Fase 4 (performance SAM) e Fase 5 (OCR/TTS). Commit chiave: `69e38ce496` (motore unico), `c1e1deab90` (Fase 0), `37c700e1ea` (Fase 1), `1f2d46221b`+`f681a76677`+`2f0365d686`+`5e0d9f8895` (Fase 2/3).*

---

## 1. Architettura attuale

### 1.1 Overlay + primitiva

- `ReaderPageImageView`: `focusOnRect(px)` / `resetZoom()` / `viewToSourceCoord()` / `sourceToViewRect(srcRect)` / `sourceImageSize()` / `cropSourceRect(pxRect)` / `setGestureZoomEnabled()`. `focusOnRect` usa `animateScaleAndCenter(...).withInterruptible(false)` (Gotcha 1).
- `BubbleZoomOverlayView` — overlay full-screen che consuma i gesti. `enum ZoomStyle { IN_PLACE, FLOATING }`:
  - **IN_PLACE**: zoom della tavola sulla nuvoletta via `focusOnRect`.
  - **FLOATING**: `BubbleCutout` mostrato centrato (≤ 92% W / ≤ 85% H) su backdrop scuro **opzionale**; "…" mentre il cutout non è pronto.
    - **Animazione ingresso/uscita**: il cutout interpola tra il rect on-page della nuvoletta (`sourceToViewRect`) e il centro — ingresso 200 ms ease-out+fade-in, uscita 160 ms ease-in+fade-out (+ backdrop). `exit(animate=true)` da tap/double-tap/back; `exit()` immediato su page-turn/teardown.
    - **Framing tail-aware**: inquadra sul *corpo* della nuvoletta (`BubbleCutout.bodyOffsetX/Y`), non sul centro geometrico.
    - **Outline vettoriale live**: `drawCutout` mappa `BubbleCutout.outline` (Path in coord unità) sul rect con una `Matrix` e lo `STROKE`a (`strokeWidth = outlineFraction·min(w,h)·2`) → bordo netto a qualsiasi scala.
    - **Pinch / pan**: `ScaleGestureDetector` → `userScale` (1..5) + pan clampato alla dimensione fit del cutout (`fitHalf·(scale-1)`), focal-point anchoring. Da zoomato: scroll/fling = pan, double-tap = reset-a-fit. Reset su enter / cambio bubble / exit.
  - Comune: swipe = bubble succ./prec. (page-turn ai bordi via `advanceBubbleZoom` + poll); tap/back = esci; contatore "N / M"; hint di uscita (2.5 s) alla prima attivazione; haptic.
  - `enter(target, page, bubbles, startIndex, sourceSize, style, backdrop, …)` — `bubbles` con **rect normalizzati 0..1**; estrazione su dispatcher **single-thread** (`Dispatchers.IO.limitedParallelism(1)`) + prefetch di **1** avanti.

### 1.2 Package `eu.kanade.tachiyomi.ui.reader.bubble`

- `Bubble(rect: RectF /* 0..1 */, confidence: Float)`.
- `BubbleCutout(bitmap, outline: Path? /* unità 0..1 */, outlineFraction, bodyOffsetX, bodyOffsetY)` — output di `BubbleExtractor`.
- `interface BubbleDetector { detect(bitmap, confThreshold) }` + `NoopBubbleDetector`.
- `TfliteBubbleDetector(context)` — TensorFlow Lite, `models/bubble_detector_ogkalu.tflite` @ 640. GPU delegate → fallback XNNPACK CPU; **mmap + SHA-256** via `ModelIntegrity`; auto-detect layout input (NCHW/NHWC) + `inputSize` + trasposizione output; un solo output detect (niente proto).
- `ModelIntegrity` — `mmapVerifiedModel(context, asset, sha)` + `sha256Hex`, condiviso da detector e `SamRefiner`.
- `BubblePostprocess` — `Letterbox`, `letterboxOf`, `decodeDetsNormalized` (core puro: decode YOLOv8-detect + NMS → `Det` 0..1) + `decodeDetections` (wrapper → `Bubble`), `iou`, `Det`.
- `SamRefiner` (object) — MobileSAM box-prompted:
  - encoder `[1,3,1024,1024]` → embedding `[1,256,64,64]`, **per-pagina** (LruCache 4); decoder `(embedding, box[1,4] px)` → mask logit `[1,1,256,256]`.
  - `prewarm(..., stillWanted)` (background) / `shapeMask(...)` (interattivo, conta in `activeEncodes`). GPU→XNNPACK 4-thread (NNAPI scartato sul Fold8). Lock separati.
  - **Gate device adattivo**: 2 encode interattivi consecutivi > 9 s → `disabledForSlowDevice` (per-processo) → fallback rettangolo. `close()` NON lo resetta.
  - post-processing maschera: resample bilineare logit → connected-component attorno al centro box + clamp box +14% → box-blur raggio 3.
- `BubbleExtractor` (object) — dato `page.stream` + `Bubble` + `sourceSize` → `BubbleCutout`:
  - `BitmapRegionDecoder.decodeRegion` del box +22% di margine a risoluzione nativa.
  - forma per `bubble_zoom_cropping_method`: `"none"` = rettangolo arrotondato 12% · `"sam"` = maschera `SamRefiner` (`DST_IN`, upscale `FILTER_BITMAP`), fallback rettangolo.
  - `analyzeSilhouette` su griglia coarse `SILHOUETTE_GRID = 256`: `traceContour` (Moore) → `chaikinClosed` ×2 → `Path` in unità (outline live) + `bodyCentre` (erosione → bbox del corpo senza coda).
- `BubbleHit` (object) — `hitTest(bubbles, nx, ny)` (ellisse inscritta + tie-break area, core puro `pickIndex`), `syntheticBubbleAt(nx, ny, pageBubbles)` (tap-to-add).
- `BubbleDetection` (façade) — `isSupported` (64-bit + `!isLowRamDevice` + RAM ≥ 2 GiB), `LruCache<String, List<Bubble>>(32)`, `activeDetections: StateFlow<Int>`, `isPending`, `clearCache`, `releaseDetector` (chiuso interpreti; cache tenuta), `prewarmSam` (no-op se metodo ≠ `sam` o device sotto stress power-save/thermal), `enqueue`/`detect` (legge `bubble_zoom_confidence`), `enqueueTiled`/`detectTiled` (webtoon: rimappa i box dei tile in 0..1 di pagina + NMS globale `rectIoU > 0.4`), `detectorFor()` → sempre `TfliteBubbleDetector`.
- `BubbleReadingOrder` (LTR/RTL/VERTICAL) — `sort` → `orderIndices` (core puro su `Box`): bande per Y → colonne per X (`bandFactor = 0.5`) → colonna dall'alto in basso.
- `bubbleKeyFor(page)` — `"${chapterId}:${index}"` + `:insert` per `InsertPage`.

### 1.3 Aggancio

- `PagerPageHolder.setImage()`: `enqueue(...)` sul bitmap di display finale (long-side ≤ 1024) + `prewarmSam(...)`.
- `WebtoonPageHolder.setImage()` — `decodeForDetection` decide: pagina normale → un bitmap (`DetectionInput.Single` → `enqueue`); striscia più alta di `width·2.2` → tile a larghezza piena (`DetectionInput.Tiled` → `enqueueTiled`, `BitmapRegionDecoder`, sample sul width, `MAX_TILES = 12`).
- Gesto → `tryEnterBubbleZoom(page, x, y, style)`: bubble **in cache** (no attesa), hit-test `BubbleHit.hitTest` in spazio normalizzato. `hit < 0` → se `config.bubbleZoomTapAnywhere` && style FLOATING → `syntheticBubbleAt`; altrimenti return false (gesto nativo). Detection in corso → toast "Detecting…" (rate-limited 2.5 s).
- Barra di elaborazione (`ReaderActivity`): `LinearProgressIndicator` se `activeDetections > 0` **||** `SamRefiner.activeEncodes > 0`. `onTrimMemory(≥ RUNNING_LOW)` + `onDestroy` → `BubbleDetection.releaseDetector()`.

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
| `bubble_zoom_confidence` | `30` | slider 10..60 (% soglia detector); cambio → `clearCache()` |
| `bubble_zoom_tap_anywhere` | `false` | tap FLOATING a vuoto → sintetizza una bubble sul tocco |
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

### Fase 2 — profondità cutout — **fatta**

- [x] **Outline vettoriale live**: `BubbleExtractor` non rasterizza più il contorno; restituisce `BubbleCutout(bitmap, outline: Path?, outlineFraction, bodyOffsetX/Y)` con il `Path` in coord unità 0..1. L'overlay (`drawCutout`) lo mappa sul rect di disegno con una `Matrix` e lo `STROKE`a live (`strokeWidth = outlineFraction·min(w,h)·2`) → bordo netto (AA del Canvas) a qualsiasi scala, anche in animazione ingresso/uscita. Traccia su griglia coarse `SILHOUETTE_GRID = 256` per tenere il `Path` leggero.
- [x] **Pinch / pan dentro il cutout FLOATING**: `ScaleGestureDetector` nell'overlay → `userScale` (1..5) + `userTransX/Y` con focal-point anchoring e pan clampato ai bordi. `onScroll`/`onFling` fanno pan invece di cambiare bubble quando `zoomed`; double-tap: reset-a-fit se zoomato, altrimenti esce. Reset dello zoom su enter / cambio bubble / exit (l'anim d'uscita parte pulita da fit).
- [x] **Framing tail-aware**: `BubbleExtractor.bodyCentre` erode la silhouette (~`min(gw,gh)/12` iter) per togliere la coda sottile, bbox del residuo → centro del *corpo*; l'overlay inquadra su quello (`bodyOffset` clampato a ±12 %, rect tenuto on-screen) invece che sul centro geometrico del bitmap.

### Fase 3 — profondità detection — **fatta**

- [x] **Tiling per webtoon**: `WebtoonPageHolder.decodeForDetection` — una pagina più alta di `width·2.2` viene affettata in tile a **larghezza piena** (`BitmapRegionDecoder`, sample sul *width* così il lato corto sopravvive; tile ≈ `decW·1.3`, overlap 16 %, cap `MAX_TILES = 12` allargando i tile). `BubbleDetection.enqueueTiled` → `detectTiled` rimappa ogni box in coord 0..1 di pagina e fa NMS globale (`rectIoU > 0.4`). Prima: `inSampleSize` sul lato lungo → su 1000×15000 il bitmap diventava ~68 px di largo. **Da validare su webtoon reale (memoria/latenza).**
- [x] **Slider soglia confidence**: pref `bubble_zoom_confidence` (10..60 %, default 30), letta in `BubbleDetection.detect` e passata a `BubbleDetector.detect(bitmap, confThreshold)`; cambio → `BubbleDetection.clearCache()`. (Toggle "includi caption/narration" rimandato: serve verificare la semantica delle 2 classi ogkalu sul device.)
- [x] **Hit-test poligonale**: `BubbleHit.hitTest` — tra i rect che contengono il tocco vince quello la cui **ellisse inscritta** lo contiene (scarta gli angoli = gutter/pannello vicino), tie-break sull'area minore. Mai più stretto di `rect.contains`. Niente maschera SAM (il detector è detect-only → nessuna maschera al momento del gesto; la versione SAM richiederebbe maschere in fase di detection).
- [x] **Tap-to-add** dietro pref `bubble_zoom_tap_anywhere` (**default off**, `ViewerConfig.bubbleZoomTapAnywhere`): quando on, il gesto FLOATING che non colpisce nessuna bubble sintetizza una `Bubble` (`BubbleHit.syntheticBubbleAt`: box centrato sul tocco, `confidence = 1f`, dimensione = mediana delle bubble della pagina · fallback 22%×14%) ed entra in FLOATING con quella; SAM la rifinisce col box come prompt, fallback rettangolo. Pref **off** = comportamento invariato (miss → gesto nativo). Modifiche isolate al ramo `hit < 0` in `tryEnterBubbleZoom` (Pager/Webtoon); niente tocco a `BubbleExtractor`/`SamRefiner`/overlay.

### Fase 4 — performance SAM *(residua)*

*I ~2 s/pagina dell'encoder MobileSAM sul Fold8 sono il vero collo di bottiglia UX. Mitigazioni già in piedi: warmup di background, barra, cue "…", gate device adattivo.*

- [ ] **Encoder più veloce**: valutare **EdgeSAM** (RepViT, ONNX ufficiali) / **NanoSAM** (ResNet18) / re-export encoder a **input 512** (dimezza il compute) / int8 sull'encoder. Serve conversione modelli → **work order Antigravity in `claude-com.md` §3**; il decoder resta com'è salvo quanto emerga dalla scheda I/O. Poi Claude parametrizza `SamRefiner` e fa lo swap.

*(Cache su disco degli embedding/cutout: **scartata** — decisione utente, non si implementa.)*

### Fase 5 — OCR / TTS / traduzione *(residua)*

- [ ] Feature a sé che riusa la pipeline detection + cutout: OCR del testo ritagliato → read-aloud (accessibilità) e/o overlay tradotto. Scope grande (modello OCR da bundlare/scaricare per JP/KO/EN, TTS/traduzione), ma il pezzo difficile — isolare la nuvoletta — è fatto. Indipendente dalle altre fasi. Direzione probabile: Tesseract4Android (FOSS, `jpn_vert`) + `TextToSpeech` nativo; traduzione rimandata.

---

## 3. Gotcha / riferimenti

- **Gotcha 1** — `focusOnRect`/`resetZoom` **devono** usare `animateScaleAndCenter(...).withInterruptible(false)`; `focusCurrent()` fa `t.post { }` (la coda eventi del long-press annulla l'animazione).
- **Gotcha 2** — R8 (`preview`/`release`) elimina le classi istanziate solo via JNI → `SIGABRT`. Keep rules per `org.tensorflow.lite.**`, `com.google.ai.edge.litert.**`.
- **Gotcha 3** — coordinate: `Bubble.rect` è **normalizzato 0..1** sul bitmap finale SSIV, ovunque. Conversione a px solo dove serve (`focusOnRect`, `cropSourceRect`, box del decoder SAM).
- **SAM su HW** — sul Fold8 (Qualcomm) GPU delegate e NNAPI rifiutano gli op TinyViT → encoder su XNNPACK CPU ~2 s/pagina. Mitigazioni: warmup di background + barra + cue "…".
- **Export modelli** — solo l'export int8 nativo Ultralytics funziona per la testa YOLO (`quantize_static` QDQ generico → 0 detection). `_NormalizeCoords` → coord 0..1 (il detector auto-detecta con `maxCoord > 1.5`).
- **Sorgente `.pt` ogkalu** — `https://huggingface.co/ogkalu/comic-speech-bubble-detector-yolov8m/resolve/21d5af0a…/comic-speech-bubble-detector.pt` (SHA-256 `10bc9f70…097b`).
- **Playground** `/mnt/data/src/kotlin/bubbleZoomPlayground/` — engine Kotlin puro (letterbox 640, decode `[1,5,8400]`, NMS 0.45, reading-order), 5 pagine ground-truth con conteggi noti.
- **Test device** — Fold8 wireless, `192.168.178.93:<porta>` (la porta del wireless debugging cambia a ogni riavvio del servizio — chiedere all'utente), package `app.komikku.beta` (`preview`, R8 attivo). Procedura in `claude-com.md` §3.
- **Tiling webtoon** — `MAX_TILES = 12` inferenze su pagine molto lunghe; se una bubble a cavallo di due tile appare doppia, alzare `TILE_OVERLAP` o aggiungere un merge verticale in `dedupeBubbles`.
