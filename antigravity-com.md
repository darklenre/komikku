# Antigravity ⟷ Claude Code: Allineamento e Handover Lavori

*File di coordinamento per la collaborazione in team sullo sviluppo della feature Bubble Zoom in Komikku.*
*Ultimo aggiornamento: 29 Agosto 2026*

---

## 1. Stato Attuale del Progetto e del Branch

* **Branch attivo**: `feature/bubble-zoom` (upstream tracciato: `fork/feature/bubble-zoom`).
* **Verifica build**: `./gradlew spotlessCheck` ✅ | `./gradlew :app:compileDebugKotlin` ✅.
* **Documenti di riferimento**:
  * Piano operativo: [`komikku-bubble-zoom-piano-implementazione.md`](file:///home/vee/git/komikku/komikku-bubble-zoom-piano-implementazione.md) (fonte di verità per requisiti, misure e work order).
  * Fabbisogni iniziali: [`komikku-bubble-zoom-progetto.md`](file:///home/vee/git/komikku/komikku-bubble-zoom-progetto.md).
  * Analisi e bug report: [`antigravity-analisys.md`](file:///home/vee/git/komikku/antigravity-analisys.md).

---

## 2. Modifiche Completate da Antigravity (UI Opzioni & Prefs)

In base alle specifiche di **§2.2** e **§5.6** del piano di implementazione, sono state completate e verificate le seguenti modifiche:

1. **Localizzazione ([`i18n-kmk/src/commonMain/moko-resources/base/strings.xml`](file:///home/vee/git/komikku/i18n-kmk/src/commonMain/moko-resources/base/strings.xml#L30-L40))**:
   * Aggiunte stringhe per categoria e motore: `pref_category_bubble_zoom`, `pref_bubble_zoom_engine`, `pref_bubble_zoom_engine_summary`, `bubble_zoom_engine_onnx`, `bubble_zoom_engine_tflite`, `bubble_zoom_engine_onnx_desc`, `bubble_zoom_engine_tflite_desc`.
2. **Preferenze ([`ReaderPreferences.kt`](file:///home/vee/git/komikku/app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferences.kt#L154))**:
   * Aggiunta pref `fun bubbleZoomEngine() = preferenceStore.getString("bubble_zoom_engine", "onnx")`.
3. **Schermata Impostazioni ([`SettingsReaderScreen.kt`](file:///home/vee/git/komikku/app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsReaderScreen.kt#L580-L614))**:
   * Creata la categoria dedicata `getBubbleZoomGroup` con:
     * `SwitchPreference` per attivazione globale (`bubble_zoom`, default `false`).
     * `ListPreference` per selezione motore (`bubble_zoom_engine`, default `"onnx"`, opzioni ONNX / TFLite, abilitata solo quando Bubble Zoom è attivo).
   * Rimossa la vecchia voce singola da `getActionsGroup`.
4. **Invalidazione Cache al cambio motore ([`BubbleDetection.kt`](file:///home/vee/git/komikku/app/src/main/java/eu/kanade/tachiyomi/ui/reader/bubble/BubbleDetection.kt#L65-L70))**:
   * Implementato `BubbleDetection.onEngineChanged()` invocato dal `onValueChanged` della `ListPreference` per resettare l'istanza del detector e svuotare la LRU cache.

---

## 3. Roadmap delle Attività Condivise e Prossimi Passi

### 🎯 Flusso A: Secondo Motore TFLite + LiteRT (Specifiche in §5 del Piano)

1. **Estrazione Postprocess Condiviso (§5.1)**:
   * Creare `app/src/main/java/eu/kanade/tachiyomi/ui/reader/bubble/BubblePostprocess.kt`.
   * Spostare qui `Letterbox`, `letterboxOf`, `decodeDetections`, `iou` e `Det`.
   * Aggiornare `OnnxBubbleDetector` per usare `decodeDetections(..., coordsIn640Space = true)` e verificare la non-regressione.
2. **Dipendenze Gradle (§5.2)**:
   * Aggiungere `litert` (+ `litert-gpu`) o `tensorflow-lite` in `gradle/libs.versions.toml` e `app/build.gradle.kts`.
   * Aggiungere `androidResources { noCompress += "tflite" }`.
3. **Export Modello `.tflite` (§5.3)**:
   * Eseguire nel playground: `m.export(format="tflite", int8=True, imgsz=640, nms=False, data="calib_yolo/data.yaml")`.
   * Posizionare in `app/src/main/assets/models/bubble_detector.tflite` (~13 MB).
4. **Implementazione `TfliteBubbleDetector` (§5.4)**:
   * Dispatcher daemon single-thread (`"bubble-detect-tfl"`).
   * Supporto GPU delegate con fallback XNNPACK.
   * Packing tensore NHWC con buffer diretti preallocati.
5. **Wired in `BubbleDetection.detectorFor` (§5.5)**:
   * Istanziare `TfliteBubbleDetector` quando `bubbleZoomEngine() == "tflite"`.
6. **Regole ProGuard (§5.7)**:
   * Aggiungere keep rules per `org.tensorflow.lite.**` e `com.google.ai.edge.litert.**` in `app/proguard-rules.pro`.

---

### 🎯 Flusso B: Navigazione & Rifinitura UX Reader

1. **Page-turn in Webtoon (§2 Item 9)**:
   * Implementare `WebtoonViewer.advanceBubbleZoom(forward: Boolean): Boolean` (scroll della `RecyclerView` + attesa caricamento holder + selezione prima/ultima bubble della nuova pagina).
   * Aggiornare `ReaderActivity.kt` nel callback `onEdge`:
     ```kotlin
     onEdge = { forward ->
         when (val v = viewModel.state.value.viewer) {
             is PagerViewer -> v.advanceBubbleZoom(forward)
             is WebtoonViewer -> v.advanceBubbleZoom(forward)
             else -> false
         }
     }
     ```
2. **Indicatore Visivo Elaborazione (§2 Item 3 / §2.1)**:
   * `BubbleDetection.activeDetections` (`StateFlow<Int>`) è già disponibile.
   * Aggiungere `LinearProgressIndicator` Compose sottile (2–3 dp) ancorato sotto la top bar del reader, visibile quando ci sono detection in corso o la pagina corrente non è ancora pronta.

---

## 4. Regole di Collaborazione e Guardrails (`AGENTS.md`)

* **Stringhe**: Aggiungere nuove stringhe **solo** in `i18n-kmk/src/commonMain/moko-resources/base/strings.xml` con namespace `KMR`. **Non** modificare i file non-base (Weblate gestisce le traduzioni).
* **Marcatori**: Racchiudere ogni modifica Komikku con `// KMK -->` e `// KMK <--`.
* **Spotless**: Eseguire sempre prima di considerare un blocco chiuso:
  ```bash
  ./gradlew spotlessApply
  ./gradlew spotlessCheck
  ./gradlew :app:compileDebugKotlin
  ```
* **Git**: Mantenere tutti i commit sul feature branch `feature/bubble-zoom`. Mai committare su `master`.
