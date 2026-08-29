# Antigravity ⟷ Claude Code: Allineamento e Handover Lavori

*File di coordinamento per la collaborazione in team sullo sviluppo della feature Bubble Zoom in Komikku.*
*Ultimo aggiornamento: 29 Agosto 2026 — Antigravity (Feature Completa: Doppio Motore + Doppio Zoom)*

---

## 1. Stato Attuale del Branch e Validazione

* **Branch attivo**: `feature/bubble-zoom` (upstream tracciato: `fork/feature/bubble-zoom`).
* **Verifica build combinato**:
  * `./gradlew spotlessCheck` ✅
  * `./gradlew assemblePreview` ✅
  * Test funzionali e di performance eseguiti con successo su Galaxy Z Fold8 via wireless debugging (`192.168.178.93:34199`).
* **Documenti di riferimento**:
  * Piano operativo: [`komikku-bubble-zoom-piano-implementazione.md`](file:///home/vee/git/komikku/komikku-bubble-zoom-piano-implementazione.md)
  * File Claude: [`claude-com.md`](file:///home/vee/git/komikku/claude-com.md)

---

## 2. Aggiornamenti Completati

### ✅ Doppio Motore AI Operativo
1. **Segmentazione Neurale (`YOLOv8-seg`, TFLite)**:
   * Modello: `assets/models/bubble_detector_seg.tflite` (46.88 MB, `AksharPatel/manga-speech-bubble-segmentation`).
   * Calcolo delle maschere poligonali esatte ad alta precisione tramite proto-maschere a 32 coefficienti (`[1, 32, 160, 160]`).
   * Supporto GPU delegate (`CompatibilityList`) con fallback XNNPACK CPU.
2. **Rilevamento Rapido (`ogkalu` + Algoritmo di Contorno, ONNX)**:
   * Modello: `assets/models/bubble_detector_ogkalu.onnx` (26.32 MB, `ogkalu/comic-speech-bubble-detector-yolov8m`).
   * Rilevamento ad altissima velocità + estrazione adattiva del contorno geometrico continuo via `BubbleExtractor` (luminance & connected-component flood-fill).

### ✅ Due Modalità di Zoom Indipendenti & Gesti Personalizzabili
1. **Ingrandimento Tavola (In-place Zoom)**: Ingrandisce e centra l'intera tavola sulla nuvoletta selezionata tramite animazione `SubsamplingScaleImageView.animateScaleAndCenter(withInterruptible = false)`.
2. **Nuvoletta Ritagliata Flottante (Floating Cutout Zoom)**: Ritaglia la sola sagoma della nuvoletta in risoluzione nativa lossless direttamente dallo stream della pagina (`BitmapRegionDecoder`), con angoli arrotondati antialiasing e maschera neurale poligonale. La nuvoletta viene visualizzata ingrandita al centro dello schermo (fino al 92% della larghezza / 85% altezza display) con effetto card shadow e sfondo oscurato (*lightbox 82% black*).
### ✅ Review Claude (C1, C2, C4) Risolti
* **C1 — Coroutine Scope**: `BubbleDetection.enqueue(context, key, bitmap)` gestito da un unico `SupervisorJob` a livello di processo (`Dispatchers.IO`); eliminati gli scope orfani usa-e-getta nei page holder.
* **C2 — Decodifica Asincrona & Prefetching**: In `BubbleZoomOverlayView`, la decodifica `BitmapRegionDecoder` opera ora in background su `extractScope` (`Dispatchers.IO`) con prefetching automatico delle nuvolette adiacenti (`index ± 1`) e `postInvalidate()` al completamento. Zero frame drop su UI thread.
* **C4 — Ripristino Pager**: Ripristinato `setCurrentItem(currentItem, false)` in `Pager.onRestoreInstanceState`.

---

## 3. Riepilogo File e Stato dei Task

| Componente | Stato | Dettagli |
|---|---|---|
| ONNX Runtime Engine | ✅ Attivo | `OnnxBubbleDetector.kt` per il modello compatto ogkalu (26.3 MB) |
| TensorFlow Lite Engine | ✅ Attivo | `TfliteBubbleDetector.kt` per il modello di segmentazione (46.8 MB) |
| UI Impostazioni | ✅ | Gruppo dedicato in `SettingsReaderScreen.kt`: selettore motore + preferenze gesti indipendenti |
| Nuova Modalità Floating | ✅ | `BubbleZoomOverlayView.kt` + `BubbleExtractor.kt` (asincrono + prefetch) |
| Navigazione Pager + Webtoon | ✅ | Long-tap, double-tap, swipe tra bubble con aptica, edge page-turn |
| Indicatori & Licenze | ✅ | Progress bar indeterminate Compose + AboutLibraries |
