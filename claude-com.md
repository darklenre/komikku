# Claude ⟷ Antigravity — coordinamento Bubble Zoom

*Lato Claude. Complemento di `antigravity-com.md`. Dettaglio: `komikku-bubble-zoom-piano-implementazione.md`.*
*Branch `feature/bubble-zoom` → fork `github.com/darklenre/komikku`. Ultimo agg.: 30 ago 2026 — Claude.*

---

## 1. Proprietà file

| Ambito | Owner |
|---|---|
| Tutto il codice Kotlin Bubble Zoom (overlay, viewer, page holder, `ReaderActivity`, detector TFLite, `SamRefiner`, `BubbleExtractor`, `BubblePostprocess`, `BubbleDetection`, prefs, settings UI, `strings.xml`, gradle/proguard) | **Claude** |
| Download + conversione modelli `.tflite` + scheda I/O in `antigravity-com.md` | **Antigravity** |
| `komikku-bubble-zoom-piano-implementazione.md` | condiviso |

`gradle.properties`: tuning locale — **mai committare**.

---

## 2. Stato

**Committato+pushato** (`c500eb802f`): flusso base, doppio motore, FLOATING, gesti, backdrop, `BubbleExtractor` CV, fix double-tap/1px, A3, C5, `.gitattributes` binary.

**Working tree** — motore unico ogkalu + rifinitura MobileSAM, **tutto Apache-2.0** (seg AGPL rimosso). In fase di commit:
- **Modelli** (in `assets/models/`, SHA verificati, vedi `antigravity-com.md`):
  - `bubble_detector_ogkalu.tflite` — ogkalu YOLOv8m-detect @ 640, `[1,3,640,640]` → `[1,6,8400]`. Apache-2.0.
  - `sam_encoder.tflite` (MobileSAM TinyViT → emb `[1,256,64,64]`) + `sam_decoder.tflite` (emb + box `[1,4]` px → mask `[1,1,256,256]` logit). Apache-2.0.
  - `bubble_detector_seg.tflite` + `bubble_detector_ogkalu.onnx` → `git rm` (seg AGPL + ONNX dismesso).
- **Codice**:
  - `TfliteBubbleDetector(context)` — asset ogkalu hardcoded, un solo output detect (niente proto), auto-detect layout/transpose.
  - `SamRefiner` — encoder per-pagina (`LruCache(4)`, ~4 MB/emb) + decoder per-box; init lazy solo se metodo = `sam`; GPU→XNNPACK 4-thread (NNAPI scartato sul Fold8); post: resample bilineare logit → connected-component + clamp box → box-blur feather. `activeEncodes` conta solo gli encode **interattivi**.
  - `BubbleExtractor` — `bubble_zoom_cropping_method`: `none`=rettangolo arrotondato · `sam`=maschera `SamRefiner`. **Outline sticker vettoriale**: `traceContour` (Moore) → `chaikinClosed` ×2 → `Path` `drawPath` nero `FILL_AND_STROKE` (`strokeWidth=2·ring`, `ring = bubble_zoom_outline_width`% del lato corto).
  - **Coordinate unificate**: `Bubble.rect` normalizzato 0..1 ovunque; px solo a `focusOnRect`/`cropSourceRect`/box decoder SAM (via `RectF.scaledToPx()` + `sourceSize` passato a `overlay.enter`).
  - Pref: `bubble_zoom_cropping_method` (`sam`) · `bubble_zoom_outline` (on) · **`bubble_zoom_outline_width`** (slider 1..8) · `bubble_zoom_floating_backdrop` (on). Rimosso `bubble_zoom_engine`.
  - **ONNX Runtime rimosso** (dep, keep rules, `OnnxBubbleDetector.kt`). Barra `ReaderActivity` agganciata a `activeDetections > 0 || SamRefiner.activeEncodes > 0`. Toast "Detecting…" se detection in corso al gesto.
- **Build**: `:app:compilePreviewKotlin` ✅ · `spotlessCheck` ✅ · `assemblePreview` ✅ · APK arm64 su Fold8.

**Da verificare a runtime sul device** (serve interazione reader): IN_PLACE/FLOATING, hit-test ai bordi pagina, page-turn tra bubble, aspetto outline vettoriale + slider spessore, barra solo su encode SAM interattivo, toast "Detecting…", animazione d'ingresso del cutout, override per-serie (chip in impostazioni reader) + quick toggle bottom bar.

**Fase 0** — **fatta**: log INFO diagnostici tolti · gate device adattivo (`SamRefiner.disabledForSlowDevice` dopo 2 encode > 9 s) · SHA-256 al load (`ModelIntegrity`) + `releaseDetector()` su `onTrimMemory`/`onDestroy` · cancel warmup SAM stantii (`prewarmHandles`, max 2) · 22 unit test (`app/src/test/.../bubble/`).

**Fase 1** — **fatta**: animazione d'ingresso FLOATING (`BubbleZoomOverlayView` interpola dal rect on-page al centro; `ReaderPageImageView.sourceToViewRect` nuovo) · **override per-serie** (`BubbleZoomOverride` DEFAULT/ENABLED/DISABLED, `viewerFlags` bit 6-7 mask `0xC0`, `SetMangaViewerFlags.awaitSetBubbleZoom`, `Manga.bubbleZoomForced`; `ViewerConfig.bubbleZoomEnabled = mangaOverride ?: global`, applicato in `updateViewer()`; chip row in `ReadingModePage`) · **quick toggle** `ReaderBottomButton.BubbleZoom` ("bz", opt-in, toast on/off) · warmup di background saltato su `isPowerSaveMode` / `currentThermalStatus ≥ MODERATE`.

---

## 3. Deploy Fold8 (`preview`, R8 attivo)

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk ; cd /home/vee/git/komikku
./gradlew :app:assemblePreview
adb install -r app/build/outputs/apk/preview/app-arm64-v8a-preview.apk   # SOLO arm64 (x86 fallisce: NO_MATCHING_ABIS)
adb shell am start -n app.komikku.beta/eu.kanade.tachiyomi.ui.main.MainActivity
adb logcat -v time | grep -E "TfliteBubbleDetector|SamRefiner|BubbleExtractor|BubblePostprocess|BubbleZoom|FATAL|SIGABRT"
```

- **Non** `assembleDebug` per validare (niente R8 → non riproduce i problemi JNI/minify).
- Gesti reader non pilotabili via `adb input` — li fa l'utente, noi leggiamo il logcat.

---

## 4. Log (append-only, sintetico)

- **29 ago — Antigravity**: doppio motore + FLOATING + gesti + `BubbleExtractor` (working tree).
- **29 ago — Claude**: fix regressioni + bug FLOATING 1px + backdrop + A3 + C5; `TfliteBubbleDetector` classifica output per rank e gestisce proto NHWC/NCHW; `BubbleExtractor` CV (seed proto → flood-fill + `strokeAlphaEdge`); card bianca overlay rimossa (era lei il "rettangolo"); double-tap nativo spento via `Config(doubleTapZoom)`. Commit+push `c500eb802f`.
- **29 ago — decisione utente**: modelli → tutto TFLite (seg kitsumed @1024, SAM refiner opzionale, ogkalu→TFLite, via ONNX). Work order §3.
- **30 ago 00:05 — Antigravity**: consegnati 4 `.tflite` (seg@1024, ogkalu, sam encoder+decoder) + scheda I/O in `antigravity-com.md`.
- **30 ago — Claude**: implementato **cropping method** (pref + settings + 4 opzioni + toggle outline), `SamRefiner`, `TfliteBubbleDetector` a input-size variabile, `BubbleDetection` a doppio asset TFLite, **rimozione ONNX Runtime**. Compila + spotless + `assemblePreview` ✅, installato su Fold8. Da testare a runtime (SAM norm, latenza seg@1024). Non committato.
- **30 ago — Claude** (decisioni utente): **rimosso il motore seg** (qualità < ogkalu + licenza AGPL) → cropping method a 2 opzioni (`none`/`sam`), **tutto Apache-2.0**. Fix caption-leak SAM (`largestComponentNear`), staircase (resample bilineare + soft alpha), warmup SAM per-pagina + cue "…". **Outline sticker vettoriale** (Moore trace → Chaikin → `Path`), **slider spessore** (`bubble_zoom_outline_width`). **Coordinate unificate** su 0..1 (fix round-trip "1px", `sourceSize` in `overlay.enter`). Barra agganciata all'encode SAM interattivo. Code-review: nessun codice commentato, tolti param inutilizzati + log per-bubble. Piano riscritto (roadmap 6 fasi), PR upstream rimandata. Verificato: reading order segue la direzione del viewer. Commit+push `69e38ce496`.
- **30 ago — Claude**: **Fase 0** (roadmap piano §2). Tolti tutti i log INFO diagnostici (restano WARN/ERROR). `ModelIntegrity.kt` (nuovo): mmap + SHA-256 dei 3 modelli al load, condiviso da `TfliteBubbleDetector`/`SamRefiner` — mismatch = init fallita pulita. `SamRefiner.disabledForSlowDevice`: 2 encode interattivi > 9 s → fallback rettangolo per la sessione. `BubbleDetection.releaseDetector()` chiamato da `ReaderActivity.onTrimMemory(≥ RUNNING_LOW)` + `onDestroy`. `BubbleDetection.prewarmSam`: solo i 2 warmup più recenti restano vivi (`prewarmHandles`), gli altri cancellati + saltati via `stillWanted`. **22 unit test** (`app/src/test/.../bubble/`): `letterboxOf`/`iou`/`decodeDetsNormalized`+NMS/`orderIndices`/`traceContour`/`chaikinClosed`; refactor: `decodeDetections` core puro + wrapper, `BubbleReadingOrder.sort` → `orderIndices(List<Box>)`. `compileReleaseKotlin` + `testReleaseUnitTest` + `spotlessKotlinCheck` ✅. Commit+push.
- **30 ago — Claude**: **Fase 2 + 3** (roadmap piano §2). **3.2** slider `bubble_zoom_confidence` (`BubbleDetector.detect(bitmap, conf)`, cache clear al cambio). **3.3** `BubbleHit.hitTest` a ellisse inscritta + tie-break area. **3.4** tap-to-add dietro `bubble_zoom_tap_anywhere` (off). **2.1** `BubbleCutout` — outline non più bakato, `Path` unità stroke-ato live dall'overlay (`drawCutout` + Matrix). **2.3** `bodyCentre` (erosione silhouette) → framing sul corpo, non sul centro geometrico. **2.2** `ScaleGestureDetector` nell'overlay: pinch/pan del cutout (userScale 1..5, focal anchor, pan clamp; double-tap = reset-a-fit se zoomato). **3.1** tiling webtoon: `WebtoonPageHolder` affetta le strisce alte in tile a larghezza piena (`BitmapRegionDecoder`), `BubbleDetection.enqueueTiled`/`detectTiled` rimappa + NMS globale. 8 unit test nuovi (`BubbleHitTest`, 37 tot). compile+test+spotless ✅, APK su Fold8. Commit+push (3 commit: `1f2d46221b`, `f681a76677`, +2.2/3.1). **Da testare a runtime**: outline live a scala/anim, pinch/pan, framing tail, tiling su webtoon reale (OOM/latenza).
- **30 ago — Claude**: **Fase 1**. Animazione ingresso+uscita cutout FLOATING (ingresso 200 ms ease-out/fade-in, uscita 160 ms ease-in/fade-out + backdrop; `exit(animate=true)` da tap/double-tap/back, immediato su page-turn; `BubbleZoomOverlayView` + `ReaderPageImageView.sourceToViewRect`). Override per-serie via `viewerFlags` bit 6-7 (`BubbleZoomOverride`, `SetMangaViewerFlags.awaitSetBubbleZoom`, `Manga.bubbleZoomForced`), `ViewerConfig.bubbleZoomEnabled` ora `override ?: global`, applicato in `ReaderActivity.updateViewer()`, chip row in `ReadingModePage` (gated `isSupported`). Quick toggle `ReaderBottomButton.BubbleZoom` → bottom bar (`ReaderBottomBar`/`ReaderAppBars`/`ReaderActivity`), togglea `bubble_zoom` + toast. `prewarmSam` salta su power-save/thermal. 13 file + `BubbleZoomOverride.kt`. `compileReleaseKotlin` + test + spotless ✅. Commit+push.
