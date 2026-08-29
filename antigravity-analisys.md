# Analisi Tecnica: Criticità e Bug Rilevati nella feature Bubble Zoom

*Documento di revisione tecnica del codice implementato per la feature Bubble Zoom su Komikku.*
*Data: 29 Agosto 2026*

---

## 1. Sommario Esecutivo

L'architettura della feature Bubble Zoom è solida, ben incapsulata nel package `eu.kanade.tachiyomi.ui.reader.bubble` e rispetta le convenzioni di progetto di Komikku (namespace `KMR`, isolamento `// KMK`, ProGuard/R8, `spotlessCheck`).

Tuttavia, dall'analisi approfondita del codice e dei flussi del reader sono emerse alcune **criticità funzionali, bug di collisione cache e opportunità di ottimizzazione della memoria/lifecycle** che devono essere corrette prima del rilascio.

---

## 2. Dettaglio Criticità e Bug Rilevati

### 🔴 1. Bug: Collisione Cache Key su `InsertPage` (Split Pagine Doppie)

* **Livello di gravità**: Alto (funzionale / corruzione logica)
* **File coinvolto**: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/bubble/BubbleKey.kt` (riga 6)
* **Descrizione**:
  La chiave di cache per memorizzare le bubble rilevate è definita come:
  ```kotlin
  fun bubbleKeyFor(page: ReaderPage): String = "${page.chapter.chapter.id}:${page.index}"
  ```
  Quando l'utente attiva l'opzione `dualPageSplit` (divisione delle pagine doppie / widescreen), Komikku crea un'istanza di `InsertPage` per rappresentare la seconda metà della pagina:
  ```kotlin
  class InsertPage(val parent: ReaderPage) : ReaderPage(parent.index, parent.url, parent.imageUrl)
  ```
  `InsertPage` eredita lo stesso identico `index` della pagina genitore `parent`.
* **Comportamento a runtime**:
  1. La prima metà della pagina (`ReaderPage`) viene decodificata, analizzata dal modello ML e salvata nella cache `LruCache` sotto la chiave `"12345:3"`.
  2. Quando la seconda metà della pagina (`InsertPage`) esegue `setImage()`, il controllo `BubbleDetection.cached(bubbleKeyFor(currentPage))` trova già presente la voce `"12345:3"` e **salta l'inferenza**.
  3. L'utente, effettuando un long-tap sulla seconda metà, si ritrova con le coordinate normalizzate (0..1) della **prima metà**, causando zoom su aree completamente vuote o errate.
* **Soluzione proposta**:
  Distinguere esplicitamente le istanze `InsertPage` nella chiave di cache:
  ```kotlin
  package eu.kanade.tachiyomi.ui.reader.bubble

  import eu.kanade.tachiyomi.ui.reader.model.InsertPage
  import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

  /** Stable per-page key for caching bubble-detection results. */
  fun bubbleKeyFor(page: ReaderPage): String =
      "${page.chapter.chapter.id}:${page.index}${if (page is InsertPage) ":insert" else ""}"
  ```

---

### 🟡 2. Bug: Mancata Transizione di Pagina in `WebtoonViewer` (`onEdge`)

* **Livello di gravità**: Medio (esperienza utente incompleta)
* **File coinvolto**: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt` (righe 1214–1216)
* **Descrizione**:
  Nel metodo `enterBubbleZoom` di `ReaderActivity`, il callback `onEdge` (invocato dall'overlay quando si fa swipe oltre la prima/ultima bubble della pagina) è implementato con un cast rigido a `PagerViewer`:
  ```kotlin
  onEdge = { forward ->
      (viewModel.state.value.viewer as? PagerViewer)?.advanceBubbleZoom(forward) ?: false
  }
  ```
* **Comportamento a runtime**:
  In modalità Webtoon (`WebtoonViewer`), quando l'utente raggiunge l'ultima bubble della pagina corrente e fa swipe per andare alla successiva, il cast a `PagerViewer` restituisce `null`. `onEdge` ritorna `false` e la visualizzazione rimane bloccata sull'ultima bubble senza avanzare alla pagina successiva del webtoon.
* **Soluzione proposta**:
  1. Definire un metodo `advanceBubbleZoom(forward: Boolean): Boolean` comune o implementarlo specificamente in `WebtoonViewer` (scorrendo la `RecyclerView` all'item successivo e richiamando `enterBubbleZoom` sulla prima bubble della pagina successiva).
  2. Aggiornare `ReaderActivity.kt`:
     ```kotlin
     onEdge = { forward ->
         when (val v = viewModel.state.value.viewer) {
             is PagerViewer -> v.advanceBubbleZoom(forward)
             is WebtoonViewer -> v.advanceBubbleZoom(forward)
             else -> false
         }
     }
     ```

---

### 🟡 3. Rischio di Memory Leak e Chiamate su Activity Distrutta in `PagerViewer.advanceBubbleZoom`

* **Livello di gravità**: Medio (stabilità / lifecycle)
* **File coinvolto**: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewer.kt` (righe 249–270)
* **Descrizione**:
  La logica di transizione tra pagine usa un polling ricorsivo tramite `postDelayed` su `pager`:
  ```kotlin
  val poll = object : Runnable {
      var attempts = 0
      override fun run() {
          val now = currentPage as? ReaderPage
          if (now != null && now != before) {
              val holder = getPageHolder(now)
              val size = holder?.sourceImageSize()
              val pxRects = if (size != null) bubbleRectsPx(now, size) else null
              if (holder != null && pxRects != null) {
                  activity.enterBubbleZoom(holder, pxRects, if (forward) 0 else pxRects.lastIndex)
                  return
              }
          }
          if (++attempts < 25) {
              pager.postDelayed(this, 100)
          } else {
              activity.exitBubbleZoom()
          }
      }
  }
  pager.postDelayed(poll, 120)
  ```
* **Comportamento a runtime**:
  Se l'utente chiude l'activity del reader o preme rapidamente Back mentre il polling è in esecuzione (può durare fino a 2.5 secondi), il `Runnable` rimane accodato nel `MessageQueue` della view. Quando viene eseguito:
  - Mantiene un riferimento rigido a `activity` e `PagerViewer`.
  - Richiama `activity.enterBubbleZoom()` o `activity.exitBubbleZoom()` su un'activity potenzialmente terminata o distrutta.
* **Soluzione proposta**:
  - Salvare il `Runnable` in una variabile membro `private var pendingBubbleZoomPoll: Runnable? = null`.
  - Rimuoverlo tramite `pager.removeCallbacks(pendingBubbleZoomPoll)` in `cleanup()`, `destroy()` o all'uscita da Bubble Zoom.
  - Inserire un guard all'inizio del `run()`: `if (activity.isFinishing || activity.isDestroyed) return`.

---

### 🟢 4. Ottimizzazione: Allocazione Eccessiva di Array per Inferenza in `OnnxBubbleDetector`

* **Livello di gravità**: Basso (performance / pressione sul Garbage Collector)
* **File coinvolto**: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/bubble/OnnxBubbleDetector.kt` (righe 74–92)
* **Descrizione**:
  Per ogni singola pagina analizzata vengono allocati dinamicamente nuovi array di grandi dimensioni:
  ```kotlin
  val pixels = IntArray(nw * nh)              // fino a 409.600 Int (~1.6 MB)
  val plane = INPUT * INPUT
  val chw = FloatArray(3 * plane)             // 1.228.800 Float (~4.9 MB)
  ```
* **Impatto**:
  Durante una sessione di lettura continua con caricamento rapido delle pagine, vengono allocati e rilasciati ~6.5 MB di heap ad ogni pagina, causando frequenti pause del Garbage Collector (GC).
* **Soluzione proposta**:
  Dato che l'inferenza è serializzata su un singolo thread dedicato (`dispatcher = Executors.newSingleThreadExecutor(...)`), i buffer possono essere preallocati una sola volta a livello di classe `OnnxBubbleDetector`:
  ```kotlin
  private val inputBuffer = FloatArray(3 * INPUT * INPUT)
  private val pixelBuffer = IntArray(INPUT * INPUT)
  ```
  Riutilizzando tali buffer con `java.util.Arrays.fill(inputBuffer, 114f / 255f)` e `getPixels(pixelBuffer, ...)`, l'allocazione per-inferenza scende a zero.

---

### 🟢 5. Caso Limite: Ripristino Zoom su Transizione Viewer / Configurazione Immagine

* **Livello di gravità**: Basso (edge case visivo)
* **File coinvolti**: `ReaderActivity.kt`, `ReaderPageImageView.kt`
* **Descrizione**:
  Se l'utente cambia orientamento dello schermo, modalità di scala o modalità di lettura (es. da Pager a Webtoon) mentre l'overlay `BubbleZoomOverlayView` è attivo, l'overlay rimane visibile ma il viewer sottostante viene ricreato, lasciando lo stato disallineato.
* **Soluzione proposta**:
  Garantire che in `ReaderActivity.onConfigurationChanged` o nel cambio di viewer (`setViewer`) venga sempre invocato `binding.bubbleZoomOverlay.exit()`.

---

## 3. Matrice di Conformità con `AGENTS.md`

| Requisito Komikku | Stato | Note |
|---|:---:|---|
| **Namespace Stringhe (`KMR`)** | ✅ Conforme | Inserite in `i18n-kmk/.../base/strings.xml`. Nessun file non-base toccato. |
| **Marcatori Fork (`// KMK`)** | ✅ Conforme | Tutti i blocchi nuovi sono racchiusi correttamente. |
| **ProGuard / R8** | ✅ Conforme | Regole `ai.onnxruntime.**` incluse in `app/proguard-rules.pro`. |
| **Spotless Check** | ✅ Conforme | `./gradlew spotlessCheck` passa con successo. |
| **Compilazione Kotlin** | ✅ Conforme | `./gradlew :app:compileDebugKotlin` compila pulito. |
| **Licenza Modello ML** | ✅ Conforme | `ogkalu/comic-speech-bubble-detector-yolov8m` è rilasciato sotto licenza Apache-2.0. |

---

## 4. Checklist Azioni Consigliate per il Completamento

1. [ ] **Applicare il fix per `InsertPage`** in `BubbleKey.kt`.
2. [ ] **Gestire il ciclo di vita del polling** in `PagerViewer.kt` con rimozione dei callback all'uscita.
3. [ ] **Implementare `advanceBubbleZoom` per `WebtoonViewer`** per consentire il salto alla pagina successiva/precedente.
4. [ ] **Preallocare i buffer `FloatArray`/`IntArray`** in `OnnxBubbleDetector.kt`.
5. [ ] **Creare feature branch Git** (es. `feature/bubble-zoom`) prima del commit, come da linee guida di contribuzione.
