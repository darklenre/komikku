# Bubble Zoom per Komikku — Documento di Progetto

*Sintesi di analisi e decisioni. Stato: pianificazione dei fabbisogni, piano tecnico implementativo non ancora definito.*

---

## 1. Obiettivo

Implementare in **Komikku** (komikku-app/komikku) una feature "Bubble Zoom": long tap su una speech bubble per zoomare esclusivamente su quella, swipe per navigare alla bubble successiva/precedente mantenendo l'esperienza a schermo intero. Ispirata a Google Play Books e Seeneva.

Target: smartphone/tablet Android, device di test **Samsung Z Fold8** (hardware di fascia alta, usato come *best case* per le performance).

Valutazione dell'uso di **Claude Code** per l'implementazione.

---

## 2. Il progetto target

- Repo: `github.com/komikku-app/komikku` — fork Kotlin/Android di **Mihon/Tachiyomi**, licenza **Apache-2.0**
- 100% Kotlin, UI Compose + reader legacy basato su `ViewPager`
- Reader: `PagerViewer` (pagina-per-pagina) e `WebtoonViewer` (scroll continuo), ciascuno con un "holder" (`PagerPageHolder` / `WebtoonPageHolder`) che decodifica il bitmap e lo passa a `ReaderPageImageView`
- Il rendering/zoom delle pagine si appoggia su **SubsamplingScaleImageView** (libreria di davemorrissey), che espone metodi nativi per animare programmaticamente scala e centro (`animateScaleAndCenter()`) — riusabile per l'animazione di Bubble Zoom senza reinventarla
- I gesti sono mappati tramite l'interfaccia `ViewerNavigation`
- Il progetto ha già "page preload customization" tra le feature esistenti (meccanismo di prefetch immagini già presente, punto di aggancio naturale per l'analisi ML)
- `CONTRIBUTING.md` richiede di aprire un'issue prima di modifiche importanti

---

## 3. Riferimenti di mercato analizzati

| Prodotto | Tipo | Rilevanza |
|---|---|---|
| **Google Play Books – Bubble Zoom** | Proprietario, closed-source | Tap/doppio tap su bubble, ML per il rilevamento, ma disponibile solo per collane Marvel/DC pre-processate lato server. Nessun codice riusabile, solo riferimento di prodotto. |
| **Seeneva** (Android, GPLv3) | Open source | Riferimento diretto più utile: rilevamento bubble via ML **on-device**, OCR (Tesseract) + TTS, richiede un pre-processing del file all'importazione. Qualità peggiore su manga che su comic occidentali (limite noto dai maintainer). |
| **comiXology Guided View** | Proprietario | Curatela **manuale** da parte di editor digitali, non automatizzata — utile solo come riferimento di UX (panel-to-panel), non di algoritmo. |
| **Comic Smart Panels Creator** | Open source (Windows) | Formato JSON aperto (CPD) per definire pannelli/bubble — riferimento di formato dati, non di detection automatica. |

**Storia delle richieste:** la feature è stata chiesta due volte nel fork TachiyomiJ2K (issue #573 nel 2020, #1566 nel 2023) e **rifiutata la prima volta per mancanza di modelli ML open source disponibili** — motivo che oggi non regge più, utile da citare in una eventuale issue ufficiale su Komikku/Mihon.

---

## 4. Panorama degli approcci tecnici

1. **Curatela manuale** (comiXology Guided View) — nessun algoritmo, editor umani
2. **CV classica euristica** (Kumiko per pannelli, Rigaud et al. per bubble) — solo contour detection OpenCV, nessun ML
3. **CNN/U-Net di segmentazione** — output più preciso (maschera) ma modelli minori/sperimentali, scarsa generalizzazione tra manga e comic occidentali
4. **Object detection YOLO** — famiglia più matura e popolare oggi, pesi pronti su HuggingFace
5. **Modelli unificati accademici** (Magi/Magi v2/v3, Oxford) — stato dell'arte per trascrizione completa (pannelli+testo+personaggi+ordine di lettura+OCR), ma pensati per GPU/CUDA, overkill per un reader mobile in tempo reale
6. **Approccio ibrido pragmatico** (es. Panelizer) — CV classica per i casi facili, ML come fallback sui casi difficili

**Scelta per Komikku:** categoria 4 (YOLO), per il bilanciamento tra pesi pronti, inferenza rapida on-device e assenza di dipendenza CUDA obbligatoria.

---

## 5. Selezione del modello

### Candidati valutati

| Modello | Copertura stile | Licenza | Architettura | Formato pronto | Metriche pubblicate |
|---|---|---|---|---|---|
| **kitsumed/yolov8m_seg-speech-bubble** ⭐ | Manga **e** comic occidentali (preview esplicite per entrambi) | GPL-3.0 | YOLOv8m, **segmentazione** (maschera) | ONNX pronto (assi dinamici) | Nessuna, ma ampio riuso pratico (15+ Space HF derivati) |
| **ogkalu/comic-speech-bubble-detector-yolov8m** (fallback) | Manga, webtoon, manhua, comic occidentali (8k immagini) | Apache-2.0 | YOLOv8m, detection (bbox) | Solo `.pt`, export da fare | Nessuna. Riusato in uno Space chiamato letteralmente "panelzoom" (precedente diretto sullo stesso caso d'uso) |
| Kiuyha/Manga-Bubble-YOLO | Solo manga (Manga109 + Mangadex EN/VI) | Apache-2.0 | YOLO26 nano, detection | ONNX pronto | mAP@50 = 0.947, 11ms su T4 GPU — dati concreti ma copertura insufficiente (niente comic occidentali) |
| kitsumed (già incluso sopra) | | | | | |

### Decisione

- **Candidato principale: kitsumed/yolov8m_seg-speech-bubble** — unico con copertura esplicita manga+occidentale, output a segmentazione (hit-test più preciso su bubble ravvicinate/sovrapposte, frequenti nei comic d'azione occidentali)
- **Fallback: ogkalu/comic-speech-bubble-detector-yolov8m** — in caso di ostacoli tecnici con kitsumed (performance, complessità di parsing maschere) o di problemi con il flusso di download separato
- **Nota sulla licenza GPL-3.0**: per ora ignorata nella scelta del candidato principale. Approccio previsto: **non bundlare i pesi nel binario Apache-2.0**, ma scaricarli separatamente a runtime (stesso pattern di ML Kit di Google) — risolve la questione in modo pulito perché il binario distribuito non conterrebbe mai codice/pesi GPL. Da approfondire nel dettaglio implementativo/legale.
- **Correzione tecnica verificata**: OpenCV ha un SDK Android ufficiale con binding Kotlin completi (`Imgproc.findContours()`), quindi il flusso maschera→contorno→poligono necessario per usare l'output di kitsumed **non** manca di strumenti in Kotlin come inizialmente ipotizzato — il costo reale è l'aggiunta di OpenCV come dipendenza nativa nel build Gradle (qualche MB in più sull'APK), non l'assenza dell'algoritmo.
- Nessuno dei due modelli principali pubblica metriche quantitative → **validazione empirica non opzionale** prima della scelta definitiva, su un set misto manga + comic occidentale.

---

## 6. Decisioni su interazione/gesture

- **Attivazione Bubble Zoom: long tap** su una bubble (non doppio tap, per evitare il conflitto con lo zoom nativo di SubsamplingScaleImageView che nel reader Mihon usa già il doppio tap — confermato dalla issue #3249 su mihonapp/mihon)
- **Navigazione tra bubble: swipe** (avanti/indietro nell'ordine di lettura)
- **⚠️ Conflitto aperto rilevato**: anche il **long tap è già occupato** nel reader Mihon/Komikku — assegnato al menu contestuale della pagina (Salva/Condividi/Imposta come), con una relativa opzione "long press action" già presente nelle Reader Settings. Due strade possibili, non ancora scelte:
  1. Rendere l'assegnazione del gesto **configurabile** (come già avviene per altre opzioni "long press action")
  2. **Disambiguare per posizione**: long-press dentro una bubble rilevata → Bubble Zoom; long-press altrove → menu contestuale esistente
- **Raccomandazione di processo**: fare l'inventario completo di `ViewerNavigation` (quali zone/gesti sono già mappati) prima di fissare la scelta definitiva, per evitare di scoprire altri conflitti uno alla volta come accaduto finora con doppio tap e long tap.

---

## 7. Piano dei fabbisogni

### 7.1 Gestione sovrapposizione gesture
- Opzione nelle impostazioni reader per assegnare il comportamento del gesto (globale o per-manga, da definire — Komikku supporta già override per-manga per altre impostazioni)
- Comportamento di fallback quando Bubble Zoom è attivo ma la pagina non ha bubble rilevate
- Disponibilità in modalità Webtoon oltre che Pager — da chiarire come requisito (lo scroll continuo rende "swipe per bubble successiva" meno naturale)
- Modo scopribile per uscire dalla modalità Bubble Zoom

### 7.2 Download e gestione del modello
- Trigger del download (primo utilizzo, con consenso esplicito vista la dimensione ~50MB)
- Origine hosting (HuggingFace diretto vs mirror del progetto)
- Verifica integrità (checksum)
- Storage escluso da backup automatici
- Degrado pulito in assenza di connessione/download fallito
- Attribuzione di licenza GPL-3.0 visibile (schermata licenze open source)
- Versionamento/re-download futuro
- Comportamento del fallback su ogkalu: se cambia anche la modalità di distribuzione (bundle vs download), va reso esplicito

### 7.3 Pipeline di analisi e prefetch
- Analisi per-pagina agganciata alla decodifica immagine esistente (non batch separato)
- Ampiezza del prefetch da validare su Z Fold8 ma pensata per degradare bene su hardware più debole
- Cancellazione delle analisi in corso quando la navigazione supera il prefetch
- Cache dei risultati: persistente tra sessioni vs solo in memoria (impatto storage da quantificare)
- Euristica di ordine di lettura delle bubble (clustering righe + verso RTL/LTR), da validare su entrambi gli stili

### 7.4 Requisiti non funzionali
- Budget di latenza percepita massimo accettabile (target numerico da definire)
- Impatto batteria/termico su sessioni di lettura lunghe
- Elaborazione interamente locale, nessun invio dati esterno
- Fascia hardware minima supportata, con disabilitazione automatica sotto soglia

### 7.5 Validazione e test
- Set di test misto manga + comic occidentale, con casi difficili noti (bubble sovrapposte, pagine senza testo, layout non standard)
- Criterio di accettazione numerico per falsi negativi/positivi, ancora da definire

---

## 8. Punti aperti da chiudere prima del piano tecnico

- [ ] Bubble Zoom disponibile anche in modalità Webtoon?
- [ ] Opzione globale o per-manga?
- [ ] Origine hosting definitiva del modello
- [ ] Criterio di accettazione qualità (soglie numeriche)
- [ ] Risoluzione conflitto long-tap: configurabile vs disambiguazione per posizione
- [ ] Inventario completo di `ViewerNavigation` per escludere altri conflitti di gesture

## 9. Prossimi passi proposti

1. Chiudere i punti aperti in Sezione 8
2. Script di validazione empirica kitsumed vs ogkalu su set misto manga/occidentale (qualità detection + costo inferenza reale su Z Fold8)
3. Apertura di un'issue su komikku-app/komikku per allinearsi con i maintainer prima di sviluppare (richiesto da CONTRIBUTING.md), citando il precedente storico su TachiyomiJ2K e la disponibilità odierna di modelli ML open
4. Solo dopo: stesura del piano tecnico implementativo dettagliato (architettura moduli, contratto `BubbleDetector` comune tra i due modelli, integrazione con `PagerPageHolder`/`ReaderPageImageView`/`ViewerNavigation`)
