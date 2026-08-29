# Bubble Zoom per Komikku — Lavoro residuo

*Documento operativo. Il grosso dell'implementazione è fatto e verificato; qui resta solo ciò che manca. Requisiti in `komikku-bubble-zoom-progetto.md`. Base: commit `936e25bf99` (1.14.1, vc 81).*

---

## 1. Stato — fatto e verificato

**Implementazione completa** (Pager + Webtoon), tutte le modifiche nel working tree:

- Primitiva zoom: `ReaderPageImageView.focusOnRect()` / `resetZoom()` / `viewToSourceCoord()` / `sourceImageSize()` — animazione `withInterruptible(false)`.
- `BubbleZoomOverlayView` — overlay full-screen che consuma i gesti: swipe = bubble successiva/precedente, tap = esci, back = esci; contatore "N / M"; hint di uscita alla prima attivazione (2.5 s); haptic enter/step; page-turn su swipe oltre la prima/ultima bubble (`advanceBubbleZoom` con poll).
- Package `eu.kanade.tachiyomi.ui.reader.bubble`: `Bubble`, `BubbleDetector` (interface) + `NoopBubbleDetector` + `OnnxBubbleDetector`, `BubbleDetection` (façade: `isSupported`, LruCache), `BubbleReadingOrder` (LTR/RTL/VERTICAL), `bubbleKeyFor`.
- Detection agganciata a `PagerPageHolder`/`WebtoonPageHolder.setImage()` sul **bitmap di display finale** (post split/merge/rotate/crop — Opzione A).
- Guardia device: `BubbleDetection.isSupported` = 64-bit + `!isLowRamDevice` + RAM ≥ 2 GiB → altrimenti switch disabilitato e detection no-op.
- Preferenza `bubbleZoom()` (`bubble_zoom`, default `false`) + `SwitchPreference` in `SettingsReaderScreen.getActionsGroup()` sotto "Show actions with long tap"; stringhe base `KMR` (`pref_bubble_zoom_long_tap`, `_summary`, `bubble_zoom_hint`). **→ da spostare in una sezione dedicata (item 4).**
- Dip.: `com.microsoft.onnxruntime:onnxruntime-android:1.20.0` + `noCompress "onnx"` + keep rules R8 per `ai.onnxruntime.**` in `proguard-rules.pro`.

**Modello int8 — pronto e bundlato:**

- `app/src/main/assets/models/bubble_detector.onnx` — **26,321,282 B (26.3 MB)**, SHA-256 `3eecb94fe991553ea8d923d1134d97496f1bb884be69fd2b48a61eabe6fdc8b3`.
- Prodotto con **export int8 nativo Ultralytics** (`YOLO(pt).export(format='onnx', imgsz=640, int8=True, data=calib_yolo/data.yaml, simplify=True)`) da `ogkalu/comic-speech-bubble-detector-yolov8m` (Apache-2.0). Calibrazione: 340 immagini bilanciate (17 titoli, manga B&W RTL + manhwa + comic occidentali).
- La quantizzazione **generica** ONNX Runtime (`quantize_static` QDQ) **distrugge il modello** (0 detection): la testa `/model.22/*` è troppo sensibile. L'export Ultralytics la esclude ed è l'unica via che funziona. Riprodotto 3× in processi puliti — non era un artefatto del crash OOM.
- Accuratezza int8 **identica** a fp16 su 5 pagine con ground-truth (k006=10, k009=14, 5-9bed=8, 4-4d9c=9, 8-b8a3=11), box stretti, nessun falso positivo su SFX.

**Verifiche:**

- Emulatore x86 (debug): flusso completo Pager + Webtoon — long-tap → zoom, swipe → navigazione, tap/back → uscita + ripristino, page-turn, hint. `spotlessKotlinCheck` ✅.
- **Z Fold8 (build `preview`/release, R8 attivo):** installato via wireless debugging. Primo tentativo → crash nativo ONNX Runtime (`JNI DETECTED ERROR ... mid == null` in `OrtSession.run`): R8 rimuoveva i costruttori di `ai.onnxruntime.*` usati via JNI. **Risolto** con le keep rules; reader ora apre e funziona sul device.

---

## 2. Cosa resta da fare

| # | Item | Note |
|---|---|---|
| 1 | **Latenza reale su ARM** — ✅ misurata su Fold8 | **~217 ms mediana** (min 213, max 257 warmup, media 221), 19 pagine, int8 @640, 1 thread, input ≤1024 px lato lungo. Detection gira al decode pagina, off-UI: al long-tap è già in cache. Ampio margine. Il Fold8 è best-case (SoC di punta); un mid-range può stare ~2–4× → ~0.5–0.9 s, comunque accettabile perché asincrono + pre-calcolato, e coperto dalla barra dell'item 3. **Resta opzionale**: una misura su un device mid-range (il motore TFLite dell'item 5, con delegate NNAPI/GPU, è la leva se serve). |
| 2 | **Peso APK release** — ✅ misurato, **accettato** | Confronto `preview` baseline `936e25bf99` vs `feature/bubble-zoom`: arm64 **39.8 → 84.7 MB (+44.9)**, v7a 33.9 → 73.4 (+39.5), universal 96.6 → 196.8 (+100.2). Delta arm64 = 26.3 (`bubble_detector.onnx`, `Stored`) + 17.6 (`libonnxruntime.so`, `Stored`) + 0.9 (`libonnxruntime4j_jni.so`) + ~0.1 dex. **Delta accettato dall'utente.** Con **entrambi** i motori bundlati (item 5) il costo arm64 sale a ~+62 MB (ONNX ~45 + TFLite ~17: `libtensorflowlite_jni.so` ~3–4 MB + `bubble_detector.tflite` ~13 MB) — anch'esso accettato. Feature comunque `default = false`. |
| 3 | **Indicazione visiva del tempo di elaborazione** — ✅ fatto | `BubbleDetection.activeDetections: StateFlow<Int>` (incrementato/decrementato attorno a `detect()`); in `ReaderActivity.setComposeOverlay`, dentro il `Box`, un `LinearProgressIndicator` indeterminato `align(TopCenter).fillMaxWidth().statusBarsPadding()` visibile finché `activeDetections > 0`. Deviazione voluta da §2.1: "una detection qualsiasi in corso" invece di "la pagina corrente" — nessun plumbing per-pagina nel compose, e in pratica coincide (le detection attive sono quelle degli holder vicini). |
| 4 | **Sezione "Bubble Zoom" nelle impostazioni Lettore + selettore motore** — ✅ fatto (Antigravity) | `SettingsReaderScreen.getBubbleZoomGroup()` (categoria dedicata: `SwitchPreference` attivazione + `ListPreference` motore, `enabled = supported && bubbleZoom`, `onValueChanged → BubbleDetection.onEngineChanged()`); voce vecchia rimossa da `getActionsGroup`. `ReaderPreferences.bubbleZoomEngine()` (`bubble_zoom_engine`, default `"onnx"`). Stringhe §2.2 in `base`. Il motore `tflite` diventa funzionante con l'item 5. |
| 5 | **Secondo motore: TFLite + LiteRT — a fianco di ONNX, selezionabile** | Si bundlano **entrambi** i runtime ed **entrambi** i modelli; l'utente sceglie con `bubble_zoom_engine` (`onnx` = default / `tflite`). **Spec dettagliata delegata ad Antigravity in §5.** |
| 6 | **Attribuzione licenza** — ✅ fatto | `app/src/main/assets/models/LICENSE` (testo Apache-2.0) + `NOTICE` (attribuzione ogkalu, commit `21d5af0a`, natura degli export int8). Voce nella schermata licenze open source via AboutLibraries: `aboutLibraries { collect { configPath = file("aboutlibraries-config") } }` in `app/build.gradle.kts` + `aboutlibraries-config/libraries/comic-speech-bubble-detector-yolov8m.json` (uniqueId `com.ogkalu:…`, tag "Bundled model", licenza `Apache-2.0`) + `aboutlibraries-config/licenses/apache-2-0.json` (testo completo, risolve offline). Verificato: la voce compare in `aboutlibraries.json` generato. Il `.tflite` (item 5) ricade sotto la stessa voce. |
| 7 | **Traduzioni** | Le stringhe `KMR` (quelle esistenti + le nuove di §2.2) sono solo in `base` (EN). IT e altre lingue via Weblate dopo il merge. |
| 8 | **Issue upstream** | Aprire issue su `komikku-app/komikku` prima della PR (come da `CONTRIBUTING.md` per modifiche importanti): citare il precedente TachiyomiJ2K (#573, #1566), feature dietro preferenza `default = false`, modelli Apache-2.0 bundlati, peso APK. |
| 9 | **Soglia banda reading-order** — ✅ validato + migliorato | Test playground su pagine dense RTL (k006/k009 One Piece, Berserk, JJK, Dr Stone) + LTR (Saga). `bandFactor = 0.5` **tenuto**. Trovato un errore reale col vecchio algoritmo (banda + sort per sola X): una coppia di bubble **impilate in verticale sullo stesso lato** (X quasi identica) usciva in ordine arbitrario/sbagliato (k006: coppia destra letta dal basso). Fix: dentro ogni banda, si **clusterizzano le bubble in colonne per X** (stessa soglia `bandFactor·medianW`), colonne in direzione di lettura, ogni colonna dall'alto in basso. Corregge k006 (RTL) e i pannelli 2/4 di Saga (LTR) senza regressioni su k009/JJK. Limite residuo noto: su griglie di pannelli complesse la clusterizzazione Y a media mobile può ancora unire bubble di righe di pannelli adiacenti (Saga p12 #8) → serve panel/gutter awareness, rimandato a §4 (segmentazione). |
| 10 | **Page-turn in Webtoon** — ✅ fatto | `WebtoonViewer.advanceBubbleZoom(forward)`: da `currentPage` trova la prossima/precedente `ReaderPage` in `adapter.items` (salta le `ChapterTransition`), scrolla (`smoothScrollToPosition` o `layoutManager.scrollToPositionWithOffset`), poi poll (100 ms × 25) di `recycler.findViewHolderForAdapterPosition(pos).itemView as ReaderPageImageView` + `BubbleDetection.cached` → `onScrolled(pos)` per sincronizzare `currentPage` + `activity.enterBubbleZoom(image, pxRects, first/last)`. Ref del poll in campo, `removeCallbacks` in `destroy()`, guard `isFinishing/isDestroyed`. `ReaderActivity.enterBubbleZoom` `onEdge` → `when (viewer) { is PagerViewer -> …; is WebtoonViewer -> …; else -> false }`. Estratto `bubblePxRects(page, image)` condiviso con `tryEnterBubbleZoom`. |

**Review `antigravity-analisys.md` (29 ago) — tutti chiusi:** collisione cache key `InsertPage` con dual-page split (suffisso `:insert` in `bubbleKeyFor`); leak/uso su activity morta del poll in `PagerViewer.advanceBubbleZoom` (ref in campo + `removeCallbacks` in `destroy()` + guard `isFinishing/isDestroyed`); allocazioni per-inferenza in `OnnxBubbleDetector` (buffer `FloatArray`/`IntArray` preallocati e riusati, dispatcher single-thread); overlay non chiuso al cambio viewer/orientamento (`bubbleZoomOverlay.exit()` in `updateViewer()`); page-turn Webtoon → item 10 (fatto).

### 2.1 Indicazione di elaborazione

**Barra sottile indeterminata** — `LinearProgressIndicator` Compose indeterminato (~2–3 dp, larghezza piena) **ancorata sotto la top bar** del reader, stile "caricamento pagina" di browser/YouTube. Visibile finché `cached(currentPageKey) == null`, nascosta appena la detection della pagina corrente è pronta. Non invasiva, non ruba spazio, nessuna interazione col gesto.

Con la detection lanciata al caricamento pagina, al long-tap è quasi sempre già pronta: la barra si vede in pratica solo su prima pagina / device lenti.

### 2.2 Sezione impostazioni "Bubble Zoom"

Nuova categoria in `SettingsReaderScreen` (stesso pattern di `getNavigationGroup()` / `getActionsGroup()`: `@Composable fun getBubbleZoomGroup(): Preference.PreferenceGroup`), registrata nella lista dei gruppi della schermata *Impostazioni → Lettore*. L'intero gruppo è nascosto (o tutte le voci `enabled = false`) se `!BubbleDetection.isSupported(context)`.

Voci nell'ordine:

| Voce | Tipo | Pref | Note |
|---|---|---|---|
| attivazione | `SwitchPreference` | `bubble_zoom` (esiste, default `false`) | prima voce del gruppo |
| motore di rilevamento | `ListPreference` | `bubble_zoom_engine` (nuova, `String`, default `"onnx"`) | `entries` = ONNX / TFLite; `enabled = bubbleZoom` |

Stringhe base `KMR` (EN in `i18n-kmk/.../base/strings.xml`; IT indicativa, da rifinire con i traduttori):

| key | EN | IT |
|---|---|---|
| `pref_category_bubble_zoom` | `Bubble Zoom` | `Zoom su nuvoletta` |
| `pref_bubble_zoom_long_tap` *(riusata come titolo dello switch)* | `Bubble zoom with long tap` | `Zoom su nuvoletta con un tocco prolungato` |
| `pref_bubble_zoom_long_tap_summary` *(esiste)* | `Long-tap a speech bubble to zoom into it. Long-tap elsewhere still opens the page actions.` | `Tieni premuto su una nuvoletta per ingrandirla. Tenendo premuto altrove si aprono comunque le azioni della pagina.` |
| `pref_bubble_zoom_engine` | `Detection engine` | `Motore di rilevamento` |
| `pref_bubble_zoom_engine_summary` | `%s` | `%s` |
| `bubble_zoom_engine_onnx` | `ONNX Runtime` | `ONNX Runtime` |
| `bubble_zoom_engine_tflite` | `TensorFlow Lite` | `TensorFlow Lite` |
| `bubble_zoom_engine_onnx_desc` *(riga di aiuto nel dialog, opzionale)* | `Reference build. Slightly larger, no hardware acceleration.` | `Build di riferimento. Un po' più pesante, nessuna accelerazione hardware.` |
| `bubble_zoom_engine_tflite_desc` *(idem)* | `Lighter, can use NNAPI / GPU acceleration.` | `Più leggera, può usare l'accelerazione NNAPI / GPU.` |

`bubble_zoom_hint` resta invariata. La voce vecchia in `getActionsGroup()` viene **rimossa** (spostata qui).

---

## 3. Riferimenti tecnici da conservare

- **Sorgente `.pt`** (per rigenerare l'ONNX, non a runtime): `https://huggingface.co/ogkalu/comic-speech-bubble-detector-yolov8m/resolve/21d5af0a9c69bb522b9968d6cc915f898d71a264/comic-speech-bubble-detector.pt` — SHA-256 `10bc9f702698148e079fb4462a6b910fcd69753e04838b54087ef91d5633097b`.
- **Playground** `/mnt/data/src/kotlin/bubbleZoomPlayground/` — engine Kotlin puro (letterbox 640, decode `[1,5,8400]`, NMS IoU 0.45, reading-order, hit-test) con `YoloDetBubbleDetector` come **fonte di verità** per il postprocess. `models/` tiene int8 (26.3 MB), fp16 di riferimento (51.9 MB), fp32 sorgente (103.6 MB). `calib_set/` = 340 immagini di calibrazione. Immagini di ground-truth separate dalla calibrazione.
- **I/O modello**: input `images` letterbox 640×640 `/255` grey 114; output detect `[1, 5, 8400]` = `cx,cy,w,h,score` in spazio 640; soglia 0.30, NMS 0.45; box normalizzati per `contentW = 640 - 2·padX`. Nessuna maschera. **Il `.tflite` (item 5) esce dallo stesso `.pt`**, stesso input/postprocess; l'export TFLite può dare l'output trasposto (`[1, 8400, 5]`) o già con NMS — verificare il layout e disattivare l'NMS nell'export (`nms=False`) per riusare il decode esistente.
- **Gotcha 1** — `focusOnRect`/`resetZoom` **devono** usare `animateScaleAndCenter(...).withInterruptible(false)`: con `true` gli eventi di coda del long-press raggiungono la `SubsamplingScaleImageView` e annullano l'animazione. `focusCurrent()` chiama `t.post { }` per lo stesso motivo.
- **Gotcha 2** — R8 (build `preview`/`release`) elimina le classi `ai.onnxruntime.*` istanziate solo via JNI dal nativo → `SIGABRT` `mid == null` in `OrtSession.run`. Necessarie in `proguard-rules.pro`:
  ```
  -keep class ai.onnxruntime.** { *; }
  -keepclassmembers class ai.onnxruntime.** { *; }
  -dontwarn ai.onnxruntime.**
  ```
- **Gotcha 3** — `Bubble.rect` è normalizzato 0..1 sul **bitmap finale** che la SSIV riceve (`sWidth×sHeight`), non sul file originale: nessuna matematica di trasformazione bbox per double-page/rotate/crop.
- **Decisione chiusa** — il modello rileva anche caption/narration box: **si tengono** come target validi (è testo da ingrandire), nessun filtro per forma.
- **Test device** — Fold8 via `adb pair` + `adb connect` (wireless debugging); package `app.komikku.beta` (build `preview`, debug-signed → il beta ufficiale va disinstallato). Estensioni risultano "non attendibili" per via della firma diversa.

---

## 4. Post-v1 (fuori scope, dopo il merge)

**Modello a segmentazione + OpenCV.** Oggi ogni bubble è un **rettangolo** e l'hit-test è `rect.contains(x, y)`: su bubble oblique, ovali o sovrapposte il rettangolo include sfondo/altre bubble, quindi un tap vicino al bordo può selezionare quella sbagliata e lo zoom si assesta impreciso. Un modello **seg** (es. `yolov8m-seg`) dà la **maschera della forma reale**; con `opencv-mobile` (~0.5 MB/ABI) si fa hit-test sul poligono e si ricava un box più aderente. Costo: modello più grande, dipendenza nativa in più, postprocess con prototipi di maschera. È il principale salto di qualità, non necessario per un v1 usabile.

**Override per-manga dell'on/off.** Ora c'è un solo switch **globale** (Impostazioni → Lettore). Komikku ha già i viewer flags per-manga (`SetMangaViewerFlags`, come la modalità di lettura forzabile su una singola serie): esporre "Bubble Zoom: predefinito / sempre attivo / sempre disattivo" nelle impostazioni della serie, per attivarlo solo dove ci sono nuvolette con testo.

**Toggle nei quick settings in-reader.** Il pannello dal basso del reader (`GeneralSettingsPage.kt`) ha gli interruttori rapidi (crop bordi, rotazione, …): aggiungere lì il toggle di Bubble Zoom per accenderlo/spegnerlo **senza uscire dalla lettura**. In v1 sta solo nelle impostazioni principali.

**Classi testo per OCR/TTS.** Il modello v1 ha **una sola classe** ("bubble"). Modelli come `ogkalu/comic-text-and-bubble-detector` distinguono anche **testo**/caption: avendo la regione di testo separata si può alimentare un OCR (estrazione testo) e da lì TTS / traduzione al volo. Feature distinta che riusa solo l'infrastruttura di detection.

---

## 5. Work order — secondo motore TFLite + LiteRT (delegato ad Antigravity)

*Spec autosufficiente. Chi la esegue parte a freddo: seguirla alla lettera, in ordine. Obiettivo: aggiungere un secondo motore di rilevamento bubble basato su TensorFlow Lite / LiteRT, **a fianco** di quello ONNX già esistente e **senza toccarne il comportamento**, selezionabile a runtime da una preferenza. Al termine, con `bubble_zoom_engine = tflite` la feature Bubble Zoom deve comportarsi in modo indistinguibile da `onnx` (stesse bubble, stesso ordine, stesso zoom) sulle pagine di test.*

### 5.0 Contesto (cosa esiste già)

- Package `eu.kanade.tachiyomi.ui.reader.bubble`:
  - `Bubble(rect: RectF /* 0..1 */, confidence: Float)`
  - `interface BubbleDetector { val isAvailable: Boolean; suspend fun detect(bitmap: Bitmap): List<Bubble> }`
  - `object NoopBubbleDetector` — `isAvailable = false`, ritorna lista vuota
  - `class OnnxBubbleDetector(context) : BubbleDetector` — ONNX Runtime, modello `assets/models/bubble_detector.onnx`
  - `object BubbleDetection` — façade: `isSupported(context)` (64-bit + `!isLowRamDevice` + RAM ≥ 2 GiB), `LruCache<String, List<Bubble>>(32)`, `detectorFor(appContext)` che istanzia **una volta** `OnnxBubbleDetector` o `NoopBubbleDetector` e lo tiene in `@Volatile var detector`
  - `BubbleReadingOrder`, `bubbleKeyFor(page)` — non si toccano
- `OnnxBubbleDetector` fa: letterbox a 640 (gain = `min(640/w, 640/h)`, pad centrato, riempimento grigio `114/255`), tensore **NCHW** `[1,3,640,640]` float32 `[0,1]`, `OrtSession.run`, output atteso `[1,5,8400]` → transpose logica a `[features][anchors]`, poi `decode()`: soglia `CONF_THRESHOLD = 0.30`, box `cx,cy,w,h` → `xyxy` in **spazio 640**, NMS `IOU_THRESHOLD = 0.45`, infine mappa da 640-letterbox a **0..1 normalizzato** togliendo il padding (`(coord - pad) / (640 - 2*pad)`).
- `preview`/`release` hanno R8 attivo; le classi usate solo via JNI vanno tenute (vedi §5.7).

### 5.1 Primo passo — estrarre il postprocess condiviso (nessun cambio di comportamento)

Per garantire parità bit-a-bit del postprocess tra i due motori, estrarre da `OnnxBubbleDetector` in un nuovo file `bubble/BubblePostprocess.kt` (funzioni top-level o `internal object`):

```kotlin
internal data class Letterbox(val gain: Float, val padX: Int, val padY: Int, val inputSize: Int = 640)

internal fun letterboxOf(srcW: Int, srcH: Int, inputSize: Int = 640): Letterbox

/** [featureMajor] = [features][anchors], features = 4 + numClassi (qui 5).
 *  [coordsIn640Space] = true se cx,cy,w,h sono in pixel 0..640 (ONNX),
 *  false se già normalizzati 0..1 rispetto al quadrato letterbox (tipico export TFLite). */
internal fun decodeDetections(
    featureMajor: Array<FloatArray>,
    lb: Letterbox,
    coordsIn640Space: Boolean,
    confThreshold: Float = 0.30f,
    iouThreshold: Float = 0.45f,
): List<Bubble>
```

`OnnxBubbleDetector` dopo il refactor deve chiamare `decodeDetections(out, lb, coordsIn640Space = true)` e restare **funzionalmente identico** (verificare con §5.8 prima di procedere). Spostare qui anche `Det`, `iou`, e la costruzione del letterbox. La preparazione dell'input (packing dei pixel nel tensore) **non** è condivisa: NCHW per ONNX, NHWC per TFLite.

### 5.2 Dipendenze

In `gradle/libs.versions.toml` (sezione KMK, vicino a `onnxruntime-android`):

```toml
# LiteRT (ex TensorFlow Lite) — secondo motore Bubble Zoom
litert = "com.google.ai.edge.litert:litert:<ultima stabile>"
litert-gpu = "com.google.ai.edge.litert:litert-gpu:<stessa versione>"
```

*(Se la versione LiteRT dà problemi di risoluzione/AGP, ripiegare su `org.tensorflow:tensorflow-lite:2.16.1` + `org.tensorflow:tensorflow-lite-gpu:2.16.1`; l'API `Interpreter` è la stessa.)*

In `app/build.gradle.kts`, sezione KMK:

```kotlin
implementation(libs.litert)
implementation(libs.litert.gpu) // opzionale ma consigliato: delegate GPU
```

`androidResources { noCompress += "tflite" }` accanto all'esistente `noCompress += "onnx"` (stesso blocco).

### 5.3 Export del modello `.tflite`

Nel playground (`/mnt/data/src/kotlin/bubbleZoomPlayground/`), con l'ambiente `ultralytics` già usato per l'export ONNX:

```python
from ultralytics import YOLO
m = YOLO("models/ogkalu_yolov8m.pt")   # stesso .pt sorgente dell'ONNX
m.export(format="tflite", int8=True, imgsz=640, nms=False,
         data="calib_yolo/data.yaml")  # stesso data.yaml di calibrazione dell'ONNX
```

Output atteso: `..._full_integer_quant.tflite` (o `_int8.tflite`) ~11–14 MB. Rinominare in `bubble_detector.tflite`.

**Verificare** (ispezione con `Interpreter` o Netron):
- shape input: `[1, 640, 640, 3]` **NHWC**;
- dtype input: `uint8`/`int8` (con `quantization: scale, zero_point`) oppure `float32` se Ultralytics ha inserito i nodi quantize/dequantize ai bordi — **gestire il caso reale**, non assumere;
- shape output: `[1, 5, 8400]` oppure `[1, 8400, 5]` (trasposto) — annotare quale;
- scala delle coordinate output: normalizzate `0..1` (più probabile per TFLite) o pixel `0..640` — annotare quale, serve per il flag `coordsIn640Space`.

Copiare il file in `app/src/main/assets/models/bubble_detector.tflite`. Annotare dimensione byte + SHA-256 in §3 del piano.

### 5.4 `TfliteBubbleDetector`

Nuovo file `bubble/TfliteBubbleDetector.kt`, `class TfliteBubbleDetector(context: Context) : BubbleDetector`. Struttura speculare a `OnnxBubbleDetector`:

- **Dispatcher**: `Executors.newSingleThreadExecutor { Thread(it, "bubble-detect-tfl").apply { isDaemon = true } }.asCoroutineDispatcher()`.
- **Init** (nel costruttore, in `try/catch`, `isAvailable = interpreter != null`):
  - caricare il modello con `FileUtil.loadMappedFile(context, "models/bubble_detector.tflite")` **oppure** leggere gli asset in un `MappedByteBuffer`/`ByteBuffer` diretto (l'asset è `noCompress`, quindi mmap possibile);
  - `Interpreter.Options()`:
    - provare in ordine: **GPU delegate** (`GpuDelegateFactory` / `new GpuDelegate()`) se `CompatibilityList().isDelegateSupportedOnThisDevice`, altrimenti **XNNPACK** (`setUseXNNPACK(true)`, on di default) su `setNumThreads(2)`;
    - **non** usare il delegate NNAPI (deprecato da Android 15 / API 35);
    - se la creazione col GPU delegate fallisce, fare fallback a CPU e loggare (`logcat`).
  - tenere riferimenti a `interpreter`, all'eventuale `delegate` (per `close()`), e ai parametri di quantizzazione input/output letti da `interpreter.getInputTensor(0)` / `getOutputTensor(0)`.
- **`detect(bitmap)`**: `withContext(dispatcher) { runCatching { infer(bitmap) }.getOrDefault(emptyList()) }`, con log di latenza opzionale allineato a quello ONNX.
- **`infer(bitmap)`**:
  1. `val lb = letterboxOf(bitmap.width, bitmap.height)`;
  2. scalare il bitmap a `nw×nh` (come ONNX), `getPixels` in un `IntArray` **preallocato come campo** (`IntArray(640*640)`), poi impacchettare in un **buffer NHWC preallocato**:
     - se input `float32`: `ByteBuffer.allocateDirect(1*640*640*3*4).order(nativeOrder()).asFloatBuffer()`, riempire con grigio `114/255f` e poi i pixel `R,G,B` nell'ordine HWC (`idx = (y*640 + x)*3`), valori `[0,1]`;
     - se input `int8`/`uint8`: `ByteBuffer.allocateDirect(1*640*640*3)`, applicare la quantizzazione `q = round(v/scale) + zeroPoint` con `v` in `[0,1]` (o `[0,255]` a seconda di scale) e clamp al range del dtype; grigio letterbox = quant di `114/255` (o `114`).
  3. output: `Array(1){ Array(F){ FloatArray(A) } }` con `F,A` secondo lo shape reale; se il tensore d'uscita è int8/uint8, dequantizzare (`(q - zeroPoint) * scale`) in un `FloatArray` prima del decode;
  4. normalizzare la forma a `featureMajor: Array<FloatArray>` `[5][8400]` (transpose in memoria se l'output è `[1,8400,5]`);
  5. `return decodeDetections(featureMajor, lb, coordsIn640Space = <valore annotato in §5.3>)`.
- **`close()`** (aggiungere anche a `BubbleDetector`? no — aggiungere un `fun close()` opzionale solo su `Onnx`/`Tflite`, chiamato da `BubbleDetection` quando cambia motore): `interpreter?.close(); delegate?.close(); dispatcher.close()`.

Tutti i buffer grandi (input `ByteBuffer`/`FloatBuffer`, `IntArray` pixel, `FloatArray` output dequantizzato) **preallocati come campi** e riusati — coerente con la stessa ottimizzazione già fatta su `OnnxBubbleDetector` (inferenza serializzata sul dispatcher).

### 5.5 Selezione del motore in `BubbleDetection`

- Nuova pref in `ReaderPreferences.kt` (blocco KMK, accanto a `bubbleZoom()`):
  ```kotlin
  fun bubbleZoomEngine() = preferenceStore.getString("bubble_zoom_engine", "onnx")
  ```
  *(String semplice; valori ammessi `"onnx"` | `"tflite"`. In alternativa un `enum class BubbleEngine { ONNX, TFLITE }` con `getEnum`.)*
- `BubbleDetection`:
  - `detectorFor(appContext)` sceglie in base a `Injekt.get<ReaderPreferences>().bubbleZoomEngine().get()`:
    `"tflite"` → `TfliteBubbleDetector(appContext)`, altrimenti `OnnxBubbleDetector(appContext)`; se `!isSupported` → `NoopBubbleDetector` (invariato).
  - aggiungere `fun onEngineChanged()`: `synchronized(this) { (detector as? …)?.close(); detector = null; cache.evictAll() }`. Chiamarla da un `bubbleZoomEngine().changes()` collector (scope applicativo) **oppure** registrando nel punto in cui si registrano le altre reader pref. Deve bastare che alla successiva pagina la detection riparta col nuovo motore; le bubble già in cache per pagine viste vanno buttate.
  - `isSupported` invariato (vale per entrambi i motori).
- `ViewerConfig` / `PagerPageHolder` / `WebtoonPageHolder` / `PagerViewer` / `WebtoonViewer` / `ReaderActivity`: **nessuna modifica** — vedono solo `BubbleDetection`.

### 5.6 Impostazioni (sezione dedicata)

Implementare la sezione "Bubble Zoom" descritta in **§2.2** (categoria dedicata in `SettingsReaderScreen`, switch attivazione + `ListPreference` motore) con le stringhe lì elencate. Se questa parte viene fatta separatamente, il work order TFLite può intanto pilotare `bubble_zoom_engine` via `adb shell` / editor prefs per i test.

`ListPreference`: `entries` = `[bubble_zoom_engine_onnx, bubble_zoom_engine_tflite]`, `values` = `["onnx", "tflite"]`, `enabled = bubbleZoom` (segue lo switch). `onValueChanged` → `BubbleDetection.onEngineChanged()`.

### 5.7 R8 / ProGuard

In `app/proguard-rules.pro`, blocco KMK accanto alle regole `ai.onnxruntime.**`:

```
# LiteRT / TensorFlow Lite (Bubble Zoom, secondo motore): JNI reflection dal nativo
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.ai.edge.litert.**
```

Verificare che un build `preview` (R8 attivo) **non** crashi con `mid == null` / `NoSuchMethodError` al primo `detect` col motore TFLite (stesso tipo di problema visto con ORT — vedi §3 Gotcha 2).

### 5.8 Test e criteri di accettazione

1. **Playground / parità postprocess** — dopo §5.1, rieseguire la validazione ONNX esistente sulle 5 pagine ground-truth (`k006=10, k009=14, 5-9bed=8, 4-4d9c=9, 8-b8a3=11`): i conteggi **non devono cambiare**.
2. **Parità TFLite vs ONNX** — sulle stesse 5 pagine, il motore TFLite deve dare gli **stessi conteggi** (±1 tollerato solo se dovuto alla quantizzazione, da giustificare) e box visivamente sovrapponibili (IoU alto). Se il `.tflite` sbaglia sistematicamente le coordinate → è quasi sempre il flag `coordsIn640Space` o l'ordine HWC/quantizzazione dell'input: ricontrollare §5.3/§5.4.
3. **Emulatore x86** (`Pixel_9_Pro_XL`, build debug): con `bubble_zoom_engine = tflite`, aprire un capitolo Pager e uno Webtoon → long-tap su bubble → zoom corretto; swipe → navigazione; tap/back → uscita. Cambio motore da impostazioni → la pagina successiva usa il nuovo motore, nessun crash.
4. **Z Fold8** (build `preview`, R8): reinstallare, ripetere il flusso col motore TFLite; misurare la latenza per pagina come fatto per ONNX (~217 ms mediana il riferimento) e annotarla in §2 item 1. Verificare quale delegate è attivo (log).
5. `./gradlew :app:compileDebugKotlin spotlessKotlinCheck` verde; build `:app:assemblePreview` verde.
6. **Peso**: annotare il nuovo delta APK arm64 (atteso ~+17 MB rispetto al solo-ONNX) in §2 item 2.

### 5.9 Rischi noti specifici TFLite

- **Layout NHWC** e **coordinate normalizzate 0..1**: differenze rispetto a ONNX; sono la causa n.1 di box sbagliati. Il flag `coordsIn640Space` e l'ordine di packing HWC vanno verificati sull'export reale, non assunti.
- **Quantizzazione I/O**: se l'export tiene input/output `int8`, servono le operazioni di (de)quantizzazione con `scale`/`zero_point` letti a runtime dal tensore. Se invece Ultralytics inserisce quantize/dequantize ai bordi (I/O `float32`), il codice è più semplice — decidere in base al modello prodotto.
- **GPU delegate**: su alcuni chip il delegate GPU fallisce o dà risultati leggermente diversi; il fallback CPU/XNNPACK deve essere sempre presente e testato.
- **Dimensione LiteRT**: se `litert` + `litert-gpu` insieme superano l'atteso (~4 MB/ABI), valutare di tenere solo `litert` (CPU/XNNPACK) e rimandare il GPU delegate.
- Il motore ONNX resta il **default** e la via di riferimento: qualunque regressione sul path ONNX durante il refactor §5.1 è bloccante.
