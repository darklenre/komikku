# Claude ⟷ Antigravity — coordinamento Bubble Zoom

*Lato Claude. Complemento di `antigravity-com.md`. Dettaglio: `komikku-bubble-zoom-piano-implementazione.md` (fonte di verità).*
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

**Fasi 0-3 fatte, committate e pushate**, APK `preview` arm64 sul Fold8. `feature/bubble-zoom` == `fork/feature/bubble-zoom` @ `5e0d9f8895`. Build sempre verde: `:app:compileReleaseKotlin` + `:app:testReleaseUnitTest` (37 test, `app/src/test/.../bubble/`) + `spotlessKotlinCheck`.

Architettura corrente completa in `piano §1`. In breve:
- **Motore unico** ogkalu YOLOv8m-detect TFLite @ 640 + **rifinitura opzionale MobileSAM**. Tutto **Apache-2.0** (seg AGPL e ONNX Runtime rimossi). Modelli in `assets/models/` con SHA-256 verificato al load (`ModelIntegrity`).
- **Overlay**: IN_PLACE (zoom tavola) / FLOATING (cutout `BubbleCutout`). Anim ingresso/uscita, framing tail-aware, **outline vettoriale live** (Path stroke-ato dall'overlay), **pinch/pan** (userScale 1..5).
- **Detection**: `BubbleHit.hitTest` a ellisse; tap-to-add (`bubble_zoom_tap_anywhere`, off); slider `bubble_zoom_confidence`; **tiling webtoon** (`enqueueTiled`/`detectTiled` su strisce > width·2.2).
- **Config**: 9 pref `bubble_zoom_*` (tabella in `piano §1.4`) + override per-serie in `viewerFlags` bit 6-7 (`BubbleZoomOverride`) + `ReaderBottomButton.BubbleZoom`.
- Gate device: `RAM ≥ 2 GB` + `SamRefiner.disabledForSlowDevice` (2 encode > 9 s). `releaseDetector()` su `onTrimMemory`/`onDestroy`.

**Restano**: **Fase 4** (encoder SAM più veloce / cache disco — coinvolge conversione modelli = Antigravity) · **Fase 5** (OCR/TTS/traduzione, indipendente).

**Da validare a runtime**: tiling su webtoon reale (memoria/latenza/doppioni ai bordi tile) — vedi `piano §3`.

---

## 3. Deploy Fold8 (`preview`, R8 attivo)

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk ; cd /home/vee/git/komikku
./gradlew :app:assemblePreview
D=192.168.178.93:<porta>   # la porta del wireless debugging cambia a ogni riavvio → chiedere all'utente; adb connect $D
adb -s $D install -r --no-incremental app/build/outputs/apk/preview/app-arm64-v8a-preview.apk   # SOLO arm64
adb -s $D shell am start -n app.komikku.beta/eu.kanade.tachiyomi.ui.main.MainActivity
adb -s $D logcat -v time | grep -E "TfliteBubbleDetector|SamRefiner|BubbleExtractor|BubbleDetection|ModelIntegrity|BubbleZoom|FATAL|SIGABRT"
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
- **30 ago — Claude**: **Fase 2 + 3** (roadmap piano §2). **3.2** slider `bubble_zoom_confidence` (`BubbleDetector.detect(bitmap, conf)`, cache clear al cambio). **3.3** `BubbleHit.hitTest` a ellisse inscritta + tie-break area. **3.4** tap-to-add dietro `bubble_zoom_tap_anywhere` (off). **2.1** `BubbleCutout` — outline non più bakato, `Path` unità stroke-ato live dall'overlay (`drawCutout` + Matrix). **2.3** `bodyCentre` (erosione silhouette) → framing sul corpo, non sul centro geometrico. **2.2** `ScaleGestureDetector` nell'overlay: pinch/pan del cutout (userScale 1..5, focal anchor, pan clamp; double-tap = reset-a-fit se zoomato). **3.1** tiling webtoon: `WebtoonPageHolder.decodeForDetection` affetta le strisce > width·2.2 in tile a larghezza piena (`BitmapRegionDecoder`, sample sul width), `BubbleDetection.enqueueTiled`/`detectTiled` rimappa in 0..1 di pagina + NMS globale. 8 unit test nuovi (`BubbleHitTest`, 37 tot). compile+test+spotless ✅, APK su Fold8. Commit `1f2d46221b` (3.x) + `f681a76677` (2.1/2.3) + `2f0365d686` (2.2/3.1).
- **30 ago — Claude**: fix `clampPan` — il pan verticale non funzionava (limite rispetto a metà viewport; una nuvoletta non riempie lo schermo in verticale). Ora limite = `metàLatoFit·(userScale-1)` simmetrico. Commit `5e0d9f8895`. Utente: "sembra funzionare". Piano §1 + com §2 riscritti allo stato Fasi 0-3 fatte.
- **30 ago — Claude**: **Fase 1**. Animazione ingresso+uscita cutout FLOATING (ingresso 200 ms ease-out/fade-in, uscita 160 ms ease-in/fade-out + backdrop; `exit(animate=true)` da tap/double-tap/back, immediato su page-turn; `BubbleZoomOverlayView` + `ReaderPageImageView.sourceToViewRect`). Override per-serie via `viewerFlags` bit 6-7 (`BubbleZoomOverride`, `SetMangaViewerFlags.awaitSetBubbleZoom`, `Manga.bubbleZoomForced`), `ViewerConfig.bubbleZoomEnabled` ora `override ?: global`, applicato in `ReaderActivity.updateViewer()`, chip row in `ReadingModePage` (gated `isSupported`). Quick toggle `ReaderBottomButton.BubbleZoom` → bottom bar (`ReaderBottomBar`/`ReaderAppBars`/`ReaderActivity`), togglea `bubble_zoom` + toast. `prewarmSam` salta su power-save/thermal. 13 file + `BubbleZoomOverride.kt`. `compileReleaseKotlin` + test + spotless ✅. Commit+push.
