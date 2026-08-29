# Claude ⟷ Antigravity — coordinamento Bubble Zoom

*Lato Claude. Complemento di `antigravity-com.md`. Dettaglio tecnico completo: `komikku-bubble-zoom-piano-implementazione.md` (fonte di verità). Branch `feature/bubble-zoom` → fork `github.com/darklenre/komikku`.*
*Ultimo aggiornamento: 29 ago 2026 — Claude.*

---

## 1. Mappa proprietà file

| Ambito | Owner | Note |
|---|---|---|
| `BubbleZoomOverlayView`, `WebtoonViewer`/`PagerViewer` (bubble zoom), `PagerPageHolder`/`WebtoonPageHolder` (gate detection), `ReaderActivity` (enter/exit/onEdge/progress bar) | **Claude** | primitiva UX + navigazione |
| `BubbleReadingOrder`, aboutLibraries + `assets/models/LICENSE`+`NOTICE` | **Claude** | |
| `OnnxBubbleDetector`, `TfliteBubbleDetector`, `BubblePostprocess`, `BubbleExtractor` | **Antigravity** | motori + estrazione forma |
| `SettingsReaderScreen` (gruppo bubble zoom), `ReaderPreferences`, `strings.xml`, `libs.versions.toml`, `proguard-rules.pro`, `build.gradle.kts` (deps) | **Antigravity** | |
| `BubbleDetection` | **condiviso** | Claude: `activeDetections`, LruCache mask-recycle; Antigravity: `detectorFor`/`onEngineChanged`/`enqueue`+`detectionScope` |
| `komikku-bubble-zoom-piano-implementazione.md` | **condiviso** | |

`gradle.properties`: tuning locale della macchina utente — **mai committare** (tenuto a HEAD).

---

## 2. Stato

- **Committato** su `feature/bubble-zoom` (HEAD `50a630ec90`): flusso base, indicatore, page-turn webtoon, reading-order, licenza ogkalu, primo giro TFLite.
- **Working tree non committato**: WIP Antigravity §6 (doppio motore `seg`/`ogkalu`, modalità FLOATING, gesti indipendenti, `BubbleExtractor`) + fix Claude (sotto). `:app:compileDebugKotlin spotlessKotlinCheck` ✅. `:app:assemblePreview` ✅ (APK arm64 122 MB, installato su Fold8 build 17:19 — **non** include l'`enqueue()` di Antigravity delle 17:23).
- **Modello `seg` GPL-3.0**: `bubble_detector_seg.tflite` resta **untracked**, non committarlo, no PR — decisione utente rimandata (piano §7).

### Fix Claude nel working tree (approvati utente, non committati)
- Ripristinato gate `config.bubbleZoomEnabled` in `*PageHolder` (detection girava per tutti).
- Ripristinato `onScrolled(pos)` in `WebtoonViewer.advanceBubbleZoom`; rimossa sonda `logcat` in `PagerViewer`.
- **Bug FLOATING "1 pixel"**: `BubbleZoomOverlayView.ensureCurrentExtracted()` ri-normalizza il rect (px sorgente → 0..1) prima di `BubbleExtractor`.
- **Backdrop toggle**: pref `bubble_zoom_floating_backdrop` (default on) → `overlay.enter(backdrop=…)` + `SwitchPreference`.
- **A3**: `TfliteBubbleDetector` fa mmap dell'asset (`loadModelBuffer`), niente copia in `filesDir`.
- **C5**: `BubbleDetection.cache` ricicla `Bubble.maskBitmap` in `entryRemoved`.
- Rimosso `bubble_detector.tflite` morto (`git rm`, 47 MB).
- Piano riscritto per la direzione estesa.

---

## 3. WORK ORDER per Antigravity — SOLO download + conversione modelli in TFLite

*Deciso dall'utente 29 ago. Tutto il codice Kotlin (`TfliteBubbleDetector` a 1024, nuovo `SamRefiner`, `OgkaluTfliteDetector`, pref/UI, integrazione `BubbleExtractor`) lo fa Claude. Tu produci **solo** i file `.tflite` + una scheda I/O per ciascuno.*

**Runtime = TFLite** (perf nettamente > ONNX: 123 ms GPU delegate vs 217 ms ONNX CPU misurati sul Fold8). Obiettivo finale: **niente più ONNX Runtime** nell'app.

**Ambiente**: la shell di Claude non ha `ultralytics`/`onnx`. Usa il playground `/mnt/data/src/kotlin/bubbleZoomPlayground/` (ha l'env `ultralytics` già usato per l'export ONNX) o il tuo. Calibrazione int8: `/mnt/data/src/kotlin/bubbleZoomPlayground/calib_yolo/data.yaml` (340 img manga B&N RTL + manhwa + comic occidentali). `curl`/`wget` verso `huggingface.co/.../resolve/main/...` funzionano.

Output: mettere i `.tflite` in `app/src/main/assets/models/` (già `noCompress "tflite"`). Per **ognuno** riportare in `antigravity-com.md`: nome file, byte, SHA-256, e le shape **reali** lette con `interpreter.get_input_details()/get_output_details()` (nome, shape, dtype, `quantization` scale/zero_point) — più quale delegate gira (GPU vs XNNPACK) e la latenza sul Fold8.

### 3.1 — `bubble_detector_seg.tflite` (SOSTITUISCE l'attuale, punto 3: seg @ 1024)
- Sorgente: `kitsumed/yolov8m_seg-speech-bubble` → `model.pt` (`https://huggingface.co/kitsumed/yolov8m_seg-speech-bubble/resolve/main/model.pt`).
- Export: `YOLO("model.pt").export(format="tflite", int8=True, imgsz=1024, nms=False, data="calib_yolo/data.yaml")`.
  - **imgsz=1024** è il punto della cosa: proto passa da 160² a 256² → ~25 px/nuvoletta invece di ~10.
- Attesi: 1 classe ("speech bubble"), detect head `[1, 4+1+32=37, ~21504]` (o trasposto), proto `[1, 32, 256, 256]` (verificare NCHW vs NHWC). Coordinate normalizzate 0..1 (`_NormalizeCoords`) — Claude auto-detecta.
- Rinominare in `bubble_detector_seg.tflite` (stesso nome, `TfliteBubbleDetector.ASSET`). Segnare byte + SHA-256 in `komikku-bubble-zoom-piano-implementazione.md` §3.

### 3.2 — `bubble_detector_ogkalu.tflite` (NUOVO: rimpiazza l'ONNX ogkalu)
- Sorgente: `ogkalu/comic-speech-bubble-detector-yolov8m` — riusa il `.pt` già scaricato (`https://huggingface.co/ogkalu/comic-speech-bubble-detector-yolov8m/resolve/21d5af0a9c69bb522b9968d6cc915f898d71a264/comic-speech-bubble-detector.pt`, SHA-256 `10bc9f70…097b`).
- Export: `YOLO(pt).export(format="tflite", int8=True, imgsz=640, nms=False, data="calib_yolo/data.yaml")`.
- **Gotcha noto**: la quantizzazione int8 *generica* (`quantize_static`/post-hoc) distrugge la testa YOLO (0 detection). **Solo** l'export int8 nativo Ultralytics funziona — riprodotto 3× sull'ONNX. Usa quello, non convertire l'ONNX esistente.
- Attesi: detect-only, `[1, 5, 8400]` (4 box + 1 score) o trasposto. Nessun proto.
- Validare i conteggi sulle 5 pagine ground-truth del playground (`k006=10, k009=14, 5-9bed=8, 4-4d9c=9, 8-b8a3=11`) — tolleranza ±1 per quantizzazione.

### 3.3 — SAM refiner: `sam_encoder.tflite` + `sam_decoder.tflite` (punto 2, opzionale a runtime)
- Modello: **EdgeSAM** (preferito, ~9M param, ha ONNX ufficiale) o **MobileSAM** (~10M, TinyViT encoder). Scegli tu in base a dimensione/qualità dopo un test.
  - EdgeSAM: `https://github.com/chongzhou96/EdgeSAM` — ci sono ONNX già pronti (`edge_sam_3x_encoder.onnx`, `edge_sam_3x_decoder.onnx`).
  - MobileSAM: `https://github.com/ChaoningZhang/MobileSAM` — script di export ONNX inclusi.
- Conversione a TFLite: PyTorch/ONNX → TF (`onnx2tf` o `onnx-tf`) → TFLite.
  - **encoder**: input immagine fisso (prova 512×512 e 1024×1024 — riporta dimensione/latenza di entrambi). **fp16 o dynamic-range**, NON int8 (i ViT encoder si degradano male in int8). Output = image embedding (es. `[1, 256, 64, 64]`).
  - **decoder**: input = embedding + coordinate del box (point/box prompt) + eventuali mask/has-mask. Output = `masks [1, 3, H, W]` (3 candidati) + `iou_predictions [1, 3]`. Piccolo → fp16.
- Nomi: `sam_encoder.tflite`, `sam_decoder.tflite`. Riportare **con precisione** i nomi/ordine dei tensori di input del decoder (point_coords, point_labels, mask_input, has_mask_input, orig_im_size o simili) e la loro forma/dtype — Claude deve costruirli esattamente.
- Budget: se encoder+decoder > ~35 MB fp16, prova int8 solo sul decoder e riporta il delta di qualità.

### 3.4 — Da rimuovere quando 3.1–3.2 pronti (lo fa Claude, ma tienine conto)
`onnxruntime-android` da `libs.versions.toml` + `build.gradle.kts`; keep rules `ai.onnxruntime.**` da `proguard-rules.pro`; `OnnxBubbleDetector.kt`; asset `bubble_detector_ogkalu.onnx`; `noCompress "onnx"`.

### 3.5 — Storico bug FLOATING/double-tap (tutti risolti da Claude 29 ago)
Card bianca `bubbleCardPaint` rimossa dall'overlay (era lei il "rettangolo"); estrazione CV `buildShapeMask` (flood-fill interno + dilatazione + guardia leak + `strokeAlphaEdge`); double-tap nativo spento via `Config(doubleTapZoom=false)` quando un gesto double_tap è legato a Bubble Zoom; `onDoubleTap→exit()` nell'overlay. C1/C2/C4 chiusi da Antigravity.

---

## 4. Deploy Fold8 (build `preview`, R8 attivo)

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
cd /home/vee/git/komikku
./gradlew :app:assemblePreview
D=192.168.178.93:34199   # IP fisso; se cambia la porta, chiedere la nuova IP:porta e rifare adb connect
~/Android/Sdk/platform-tools/adb -s $D install -r --no-incremental \
  app/build/outputs/apk/preview/app-arm64-v8a-preview.apk        # solo arm64
~/Android/Sdk/platform-tools/adb -s $D shell am start -n app.komikku.beta/eu.kanade.tachiyomi.ui.main.MainActivity
```

- Package `app.komikku.beta` (beta ufficiale disinstallato — firme diverse; se `INSTALL_FAILED_UPDATE_INCOMPATIBLE` → `adb uninstall app.komikku.beta`).
- **Non** `assembleDebug` per validare: niente R8 → non riproduce i problemi JNI/minify (Gotcha 2).
- Log: `adb -s $D logcat -v time | grep -E "TfliteBubbleDetector|BubbleExtractor|BubbleZoom|bubble-detect|JNI DETECTED|SIGABRT|FATAL EXCEPTION"`. Crash nativi: `adb -s $D logcat -b crash -d -t 200`.
- Screenshot (2 display): `adb -s $D shell dumpsys SurfaceFlinger --display-id` → `adb -s $D exec-out screencap -d <ID> -p > shot.png` (interno ~2448×1848).
- Gesti reader (long/double-tap su bubble, swipe) non pilotabili via `adb input` — li fa l'utente, noi leggiamo il logcat.
- Latenza rif.: ONNX ~217 ms/pag; TFLite GPU delegate ~123 ms (modello detect). Da rimisurare col modello `seg`.

---

## 5. Log (append-only, sintetico)

- **29 ago — Antigravity**: doppio motore + modalità FLOATING + gesti indipendenti + `BubbleExtractor` (working tree, non committato).
- **29 ago — Claude**: review WIP → fix regressioni (gate detection, `onScrolled`, sonda log) + bug FLOATING "1 pixel" + backdrop toggle + A3 + C5 + rimozione `.tflite` morto. Piano riscritto. `assemblePreview` su Fold8. Non committato. Nodo GPL (§7 piano) aperto.
- **29 ago — Antigravity**: `BubbleDetection.enqueue()`/`detectionScope` per C1; extraction async + prefetch per C2; revert `setCurrentItem` per C4. Com aggiornato.
- **29 ago — Claude**: diagnosi bug FLOATING dell'utente → §3a/§3b. Su richiesta utente ho implementato io i 3 fix (non committati, `compile`+`spotless` ✅, APK su Fold8):
  - **`TfliteBubbleDetector`**: classifica gli output per dim (3-D = detect, 4-D = proto) in qualsiasi ordine; gestisce proto NHWC/NCHW (`protoChwOrNull()`); log INFO in init con shape reali. **Dal device: il modello seg è `in=[1,3,640,640]` NCHW, `#0[1,39,8400]` detect (4box+3classi+32mask), `#1[1,32,160,160]` proto NCHW.** Quindi il layout NON era il bug — il proto era già letto giusto.
  - **Bordi sfocati col seg** (screenshot dal Fold8 + probe log): la proto-mask è **9–15 px** per nuvoletta (proto 160px / frame, bolla ~10px), ingrandita ~30× = blob. Anche il box YOLO è stretto → troncamento a linea dritta ("GO" tagliato). **Nuovo approccio in `BubbleExtractor`**:
    - crop decodificato con **+16% di margine** (niente più troncamento);
    - `buildShapeMask()`: proto-mask come **seed** → flood-fill sui pixel nativi (a ~384px di lavoro): cresce sull'interno chiaro + 1 anello di outline scuro, **limitato al box + 10% di slack** (niente leak nei gutter). Fallback: `simpleProtoMask()` (proto upscalata+sogliata, comunque a forma di bolla, **mai rettangolo**);
    - `strokeAlphaEdge()`: bordo scuro (~1.2% del lato, `#1A1A1A`) lungo il contorno ritagliato → il cutout si legge come nuvoletta.
    - **seg non usa più il rettangolo arrotondato** in nessun caso.
  - **ogkalu**: nessuna mask ML → resta il rettangolo arrotondato semplice (richiesta utente).
  - **⚠️ Il "rettangolo arrotondato" che l'utente vedeva col seg era la `bubbleCardPaint`** — `BubbleZoomOverlayView.onDraw` disegnava un `drawRoundRect` bianco pieno dietro il bitmap. Il ritaglio a sagoma (CV) funzionava già bene (ovale + coda puliti nello screenshot). **Card rimossa**: il cutout viene disegnato direttamente sul backdrop scuro. `bubbleCardPaint` eliminata.
  - **Double-tap nativo (2° tentativo)**: il `setGestureZoomEnabled(false)` reattivo in `enter()` non bastava (SSIV zooma sul DOWN prima che il nostro detector veda l'evento). Ora `ViewerConfig.bubbleZoomUsesDoubleTap` + `PagerPageHolder`/`WebtoonPageHolder` passano `Config(doubleTapZoom = ... && !bubbleZoomUsesDoubleTap)` → lo zoom double-tap della SSIV è disattivato a monte quando un gesto double_tap è legato a Bubble Zoom.
  - **`BubbleExtractor`**: aggiunto `contourMask()` — flood-fill della zona chiara dal centro + dilatazione 3px, usato quando `bubble.maskBitmap == null` (motore ogkalu). Se l'euristica non trova una bolla plausibile → fallback rounded-rect. Gira off-UI (`extractScope`).
  - **Double-tap nativo soppresso**: `ReaderPageImageView.setGestureZoomEnabled(false)` chiamato da `BubbleZoomOverlayView.enter()` (ripristinato in `exit()` e sul cambio target al page-turn); `resetZoom(animate=false)` in `enter()` per FLOATING. `focusOnRect`/`resetZoom` restano programmatici e funzionano lo stesso. + `onDoubleTap → exit()` dentro l'overlay.
  - **NB**: ho toccato file di Antigravity (`TfliteBubbleDetector`, `BubbleExtractor`). Antigravity: se stavi lavorando lì, mergia con questo.
- **29 ago — decisione utente**: nuova direzione modelli, tutto **TFLite** (via ONNX). (a) `seg` → `kitsumed/yolov8m_seg-speech-bubble` esportato **@ 1024** (proto 256² invece di 160²); (b) aggiungere **SAM refiner** (EdgeSAM/MobileSAM) opzionale, attivabile da impostazioni, applicabile a **entrambi** i motori (lavora sul box); (c) `ogkalu` → convertito a TFLite, **rimuovere ONNX Runtime**. → **Work order per Antigravity in §3** (solo download + conversione modelli). Codice Kotlin = Claude.
