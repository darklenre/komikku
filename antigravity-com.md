# Antigravity ⟷ Claude Code: Allineamento e Handover Lavori

*File di coordinamento per la collaborazione in team sullo sviluppo della feature Bubble Zoom in Komikku.*
*Ultimo aggiornamento: 29 Agosto 2026 — Antigravity*

---

## 1. Stato Attuale del Branch e Validazione

* **Branch attivo**: `feature/bubble-zoom` (upstream tracciato: `fork/feature/bubble-zoom`).
* **Verifica build combinato**:
  * `./gradlew spotlessCheck` ✅
  * `./gradlew :app:compileDebugKotlin` ✅
* **Documenti di riferimento**:
  * Piano operativo: [`komikku-bubble-zoom-piano-implementazione.md`](file:///home/vee/git/komikku/komikku-bubble-zoom-piano-implementazione.md)
  * File Claude: [`claude-com.md`](file:///home/vee/git/komikku/claude-com.md)

---

## 2. Aggiornamenti Completati

### ✅ Completato da Antigravity (Flusso A — Secondo Motore TFLite + LiteRT — §5)
* **§5.1 Postprocess Condiviso**: Creato [`BubblePostprocess.kt`](file:///home/vee/git/komikku/app/src/main/java/eu/kanade/tachiyomi/ui/reader/bubble/BubblePostprocess.kt) con `Letterbox`, `letterboxOf`, `decodeDetections`, `iou`, `Det`. Refactoring di [`OnnxBubbleDetector.kt`](file:///home/vee/git/komikku/app/src/main/java/eu/kanade/tachiyomi/ui/reader/bubble/OnnxBubbleDetector.kt) per usare il postprocess comune preservando i buffer preallocati e aggiungendo `close()`.
* **§5.2 Dipendenze & Resource NoCompress**:
  * `gradle/libs.versions.toml`: Aggiunti `tensorflow-lite = "2.16.1"`, `tensorflow-lite-gpu = "2.16.1"`, `tensorflow-lite-gpu-api = "2.16.1"` (risolve `GpuDelegateFactory.Options`).
  * `app/build.gradle.kts`: Aggiunte le dipendenze implementation e `noCompress += listOf("onnx", "tflite")`.
* **§5.3 Export Modello `.tflite` & NOTICE**:
  * Modello esportato con successo (`int8`, `imgsz=640`, calibrazione `calib_yolo/data.yaml`).
  * File: `app/src/main/assets/models/bubble_detector.tflite` (26,516,834 byte, 25.29 MB).
  * SHA-256: `98e3938c48ff0986429fad638aefa3a1f3ac6b506863ded78d10e6d10d2be282`.
  * `app/src/main/assets/models/NOTICE` aggiornato con le specifiche di `bubble_detector.tflite`.
  * Validazione quantitativa ground truth: `k006`=10, `k009`=13, `4-4d9c`=9, `5-9bed`=8, `8-b8a3`=11 (pari al 100% con ONNX).
* **§5.4 `TfliteBubbleDetector`**:
  * Creato [`TfliteBubbleDetector.kt`](file:///home/vee/git/komikku/app/src/main/java/eu/kanade/tachiyomi/ui/reader/bubble/TfliteBubbleDetector.kt) con dispatcher single-thread daemon (`"bubble-detect-tfl"`), supporto GPU delegate (`CompatibilityList`) con fallback XNNPACK CPU, zero-alloc in inference (buffer `pixelBuffer`, `chwBuffer`, direct `FloatBuffer` preallocati) e metodo `close()`.
* **§5.5 Cablaggio Dinamico**:
  * [`BubbleDetection.kt`](file:///home/vee/git/komikku/app/src/main/java/eu/kanade/tachiyomi/ui/reader/bubble/BubbleDetection.kt): `detectorFor(appContext)` legge la preferenza `bubbleZoomEngine()` e istanzia dinamicamente `TfliteBubbleDetector` o `OnnxBubbleDetector`. `onEngineChanged()` invoca `close()` sull'istanza attiva prima di azzerare il riferimento e svuotare la LRU cache.
* **§5.7 Regole ProGuard**:
  * In `app/proguard-rules.pro` aggiunte le keep rules per `org.tensorflow.lite.**` e `com.google.ai.edge.litert.**`.
* **§2.2 / §5.6 UI Opzioni**:
  * `SettingsReaderScreen.kt`, `ReaderPreferences.kt`, `i18n-kmk/.../base/strings.xml` completati e testati.

### ✅ Riconosciuto Completato da Claude (Flusso B + Licenze)
* **Flusso B**: `WebtoonViewer.advanceBubbleZoom` + dispatch `onEdge` in `ReaderActivity.kt` + `LinearProgressIndicator` Compose pilotato da `BubbleDetection.activeDetections`.
* **Item 6**: `LICENSE` e `NOTICE` in `assets/models/` + integrazione `aboutLibraries` in `app/build.gradle.kts` e `app/aboutlibraries-config/`.

---

## 3. Riepilogo File e Stato dei Task

Tutti gli item di sviluppo (Flusso A e Flusso B) sono completati con successo:

| Componente | Stato | Dettagli |
|---|---|---|
| ONNX Runtime Engine | ✅ | Funzionante e ottimizzato |
| TensorFlow Lite Engine | ✅ | Modello int8 bundlato, GPU delegate + CPU fallback |
| UI Impostazioni | ✅ | Categoria dedicata + selettore ONNX/TFLite con switch a caldo |
| Navigazione Pager + Webtoon | ✅ | Long-tap, swipe tra bubble, edge page-turn |
| Indicatori & Licenze | ✅ | Progress bar indeterminate Compose + AboutLibraries |

---

## 4. Misure di Latenza Reali su Galaxy Z Fold8 (`SM_F971B`)

Build `preview` arm64 con R8/minificazione attiva, testata in wireless debugging (`192.168.178.93:34199`):

| Motore | Runtime / Hardware | Latenza Mediana | Range | Dettagli |
|---|---|---|---|---|
| **ONNX Runtime** | CPU single-thread | **~217 ms** | 213–257 ms | int8 @640, 19 pagine |
| **TensorFlow Lite** | **GPU Delegate (Adreno)** | **~123 ms** | 120–125 ms (153 warmup) | int8 @640, 14 pagine |

**Risultato**: TensorFlow Lite con GPU delegate è **~1.75× più veloce** (-43% tempo di inferenza per pagina) rispetto a ONNX Runtime su CPU.

---

## 5. Nuova Feature Pianificata: Floating Extracted Bubble Zoom (§6 del Piano)

In risposta alla nuova richiesta utente, è stata inserita nel piano operativo la **Sezione 6**:
* **Trigger**: Double-tap sulla nuvoletta (intercetta le coordinate; se fuori nuvoletta esegue il classico double-tap zoom 2×).
* **Effetto**: Ritaglio sagoma reale (non-rettangolare) della nuvoletta via CV/Thresholding & Alpha mask + visualizzazione centrata ingrandita a schermo intero con backdrop oscurato.
* **Navigazione**: Swipe per nuvoletta successiva/precedente + tap singolo per uscire.
* **Opzioni**: `bubble_zoom_style` (`in_place` vs `floating_extracted`) e `bubble_zoom_trigger` (`long_tap` vs `double_tap`).
