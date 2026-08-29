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
- Preferenza `bubbleZoom()` (`bubble_zoom`, default `false`) + `SwitchPreference` in `SettingsReaderScreen.getActionsGroup()` sotto "Show actions with long tap"; stringhe base `KMR` (`pref_bubble_zoom_long_tap`, `_summary`, `bubble_zoom_hint`).
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
| 1 | **Latenza reale su ARM** | Mai misurata (solo x86 desktop: ~500 ms fp32 1-thread). Fold8 ora accoppiato: misurare ms/pagina int8 @640 su `OnnxBubbleDetector`, e su un mid-range se possibile. Se troppo lenta → alzare la soglia RAM in `BubbleDetection` o valutare NNAPI EP. |
| 2 | **Peso APK release** | `preview` arm64 = 84.7 MB, universal = 196.8 MB (con modello 26.3 MB flat + ORT ~8–16 MB/ABI). Misurare il delta vs build senza feature su `assembleRelease` e decidere se accettabile. Piano B se troppo: `onnxruntime-mobile` (build ridotta) o item 3. |
| 3 | *(condizionato a 1–2)* **TFLite int8 + LiteRT** | Percorso maturo per YOLO su mobile: modello ~13 MB, veloce con delegate NNAPI/GPU. Costo: sostituire `onnxruntime-android` → `org.tensorflow:tensorflow-lite` (+ `-gpu`/`-support`) nell'app e riscrivere `OnnxBubbleDetector` → `TfliteBubbleDetector` (stesso pre/postprocess YOLOv8-detect, cambia solo l'API di inferenza e il layout tensori) — ~mezza giornata. Export: `YOLO(pt).export(format='tflite', int8=True, imgsz=640, data=…)`. Da fare **solo se** l'item 1 (latenza) o l'item 2 (peso) danno esito negativo con ORT. Il playground resta su ORT come riferimento del postprocess. |
| 4 | **Indicazione visiva del tempo di elaborazione** | Oggi: se al long-tap la detection della pagina non è ancora pronta (`BubbleDetection.cached(key) == null`) si ricade in silenzio sul menu pagina. Serve un feedback — vedi §2.1. |
| 5 | **Attribuzione licenza** | Aggiungere `ogkalu/comic-speech-bubble-detector-yolov8m` (Apache-2.0) alla schermata licenze open source + file `LICENSE`/`NOTICE` accanto a `assets/models/bubble_detector.onnx`. |
| 6 | **Traduzioni** | Le 3 stringhe `KMR` sono solo in `base` (EN). IT e altre lingue via Weblate dopo il merge. |
| 7 | **Issue upstream** | Aprire issue su `komikku-app/komikku` prima della PR (come da `CONTRIBUTING.md` per modifiche importanti): citare il precedente TachiyomiJ2K (#573, #1566), feature dietro preferenza `default = false`, modello Apache-2.0 bundlato. |
| 8 | **Soglia banda reading-order** | `BubbleReadingOrder` usa `bandFactor = 0.5`. Validare su qualche pagina densa multi-colonna; ritoccare solo se sbaglia l'ordine. |
| 9 | **Page-turn in Webtoon** | `advanceBubbleZoom` esiste solo in `PagerViewer`; `ReaderActivity.enterBubbleZoom` fa `onEdge` con `as? PagerViewer`. In Webtoon lo swipe oltre l'ultima/prima bubble della pagina non gira pagina (resta fermo, no crash). Serve una `WebtoonViewer.advanceBubbleZoom(forward)`: scroll del `RecyclerView` alla pagina succ./prec. + attesa holder/detection + re-enter sulla prima/ultima bubble; poi `onEdge` → `when (viewer) { is PagerViewer -> …; is WebtoonViewer -> … }`. |
| 10 | *(rimandato)* Guardia batteria/termico | `ActivityManager`/`PowerManager` per degradare la detection su sessioni lunghe. Esplicitamente rinviato dall'utente. |

**Review `antigravity-analisys.md` (29 ago) — chiusi:** collisione cache key `InsertPage` con dual-page split (suffisso `:insert` in `bubbleKeyFor`); leak/uso su activity morta del poll in `PagerViewer.advanceBubbleZoom` (ref in campo + `removeCallbacks` in `destroy()` + guard `isFinishing/isDestroyed`); allocazioni per-inferenza in `OnnxBubbleDetector` (buffer `FloatArray`/`IntArray` preallocati e riusati, dispatcher single-thread); overlay non chiuso al cambio viewer/orientamento (`bubbleZoomOverlay.exit()` in `updateViewer()`). Resta aperto solo il page-turn Webtoon → item 9.

### 2.1 Indicazione di elaborazione

**Barra sottile indeterminata** — `LinearProgressIndicator` Compose indeterminato (~2–3 dp, larghezza piena) **ancorata sotto la top bar** del reader, stile "caricamento pagina" di browser/YouTube. Visibile finché `cached(currentPageKey) == null`, nascosta appena la detection della pagina corrente è pronta. Non invasiva, non ruba spazio, nessuna interazione col gesto.

Con la detection lanciata al caricamento pagina, al long-tap è quasi sempre già pronta: la barra si vede in pratica solo su prima pagina / device lenti.

---

## 3. Riferimenti tecnici da conservare

- **Sorgente `.pt`** (per rigenerare l'ONNX, non a runtime): `https://huggingface.co/ogkalu/comic-speech-bubble-detector-yolov8m/resolve/21d5af0a9c69bb522b9968d6cc915f898d71a264/comic-speech-bubble-detector.pt` — SHA-256 `10bc9f702698148e079fb4462a6b910fcd69753e04838b54087ef91d5633097b`.
- **Playground** `/mnt/data/src/kotlin/bubbleZoomPlayground/` — engine Kotlin puro (letterbox 640, decode `[1,5,8400]`, NMS IoU 0.45, reading-order, hit-test) con `YoloDetBubbleDetector` come **fonte di verità** per il postprocess. `models/` tiene int8 (26.3 MB), fp16 di riferimento (51.9 MB), fp32 sorgente (103.6 MB). `calib_set/` = 340 immagini di calibrazione. Immagini di ground-truth separate dalla calibrazione.
- **I/O modello**: input `images` letterbox 640×640 `/255` grey 114; output detect `[1, 5, 8400]` = `cx,cy,w,h,score` in spazio 640; soglia 0.30, NMS 0.45; box normalizzati per `contentW = 640 - 2·padX`. Nessuna maschera.
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

- Modello a **segmentazione** + OpenCV per hit-test su forme reali (bubble sovrapposte).
- **Override per-manga** dell'on/off (meccanismo `SetMangaViewerFlags` già disponibile).
- Voce nei **quick settings** in-reader (`GeneralSettingsPage.kt`).
- Classi testo per OCR/TTS (richiede un modello con quelle classi).
