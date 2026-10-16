# Bubble Zoom — Riprogettazione dai fabbisogni (v2)

*Avviata il 16 ott 2026 su richiesta dell'utente: "molti problemi nell'identificare le nuvolette, quelle multiple o vicine vengono zoomate insieme, cropping con bordi imprecisi o sgranati — ripartiamo dalle basi".*
*Sostituisce come direzione tecnica `komikku-bubble-zoom-piano-implementazione.md` §1-2 (che resta come descrizione dello stato v1). Requisiti originari: `komikku-bubble-zoom-progetto.md`.*
*Evidenze (immagini, script): `/mnt/data/src/kotlin/bubbleZoomPlayground/eval/` (`scripts/`, `out/det/`, `out/mask*/`).*

---

## 1. Cosa abbiamo osservato (v1, commit `97750255ee` + working tree)

Riprodotto sull'emulatore (Pixel 9 Pro XL, API 36, build `debug` x86_64, FLOATING su long-tap, cropping `sam`) e offline sulle stesse pagine.

| # | Sintomo | Dove | Causa radice (verificata nel codice / negli esperimenti) |
|---|---|---|---|
| S1 | Bordo "sgranato": alone bianco sfumato al posto del tratto nero, bordo frastagliato | tutte le nuvolette | Maschera EdgeSAM calcolata sull'**intera pagina**: 256×256 celle per il frame 1024 → su una pagina 1988×3057 **1 cella ≈ 12 px di pagina**; una nuvoletta 300×140 px ha ~25×12 celle. Poi `WORK_MAX = 400`, box-blur, upscaling bilineare e soglia → bordo morbido e "a gradini". |
| S2 | Il tratto d'inchiostro del balloon sparisce o diventa una banda scura sfocata | idem | La maschera segue il *riempimento bianco*, non il bordo; l'outline viene poi **ridisegnato** (traccia su griglia 256 + Chaikin ×3) e non coincide col bordo vero → doppio bordo / banda. |
| S3 | Forma sbagliata: ovale reso quasi rettangolare, coda o fondo "morsi" | "SOMEONE SAID…", "I AM JOÃO!" (Absolute Superman #1 p.4) | Box del detector **stretti sul testo** + `CROP_MARGIN` 0.10 (working tree) + `clipToLobeRegion` → tutto ciò che sporge dal box viene tagliato. |
| S4 | Nuvolette vicine / che condividono lo sfondo zoomate insieme | segnalato dall'utente | Il detector le separa correttamente (verificato, §3.1). Le unisce il post-processing: `mergeLinked` conferma il link se il corridoio tra i box è "chiaro e poco saturo" → **due nuvolette distinte su fondo bianco passano il test**; il flood-fill Tier-1 esce dal balloon attraverso il bianco condiviso (`BOUND_GROW`); la "colla" SAM sul box unione. |
| S5 | Ordine di lettura errato | p.4: "I AM JOÃO!" (vignetta destra) = 6/11 prima di "I AM SORRY…" (vignetta sinistra) = 7/11 | `BubbleReadingOrder` raggruppa per bande Y su tutta la pagina, **ignora le vignette**. |
| S6 | Ogni correzione rompe qualcos'altro | storico v5→v6/v7→rollback | 8 strati euristici in cascata (flood, SAM, snap, bridge-zone, union-glue, clip, merge, outline) con ~20 soglie magiche interdipendenti e nessuna metrica: si valuta "a occhio" su 1-2 pagine. |

Note emulatore: GPU delegate non disponibile → EdgeSAM su CPU 863 ms/encode (atteso). Il dump `BZDump` non ha prodotto file (da indagare, irrilevante per v2).

---

## 2. Fabbisogni (rivisti)

### 2.1 Funzionali

- **F1 — Una nuvoletta per gesto.** Il tocco seleziona *una* nuvoletta. Due nuvolette distinte non vengono mai mostrate insieme, anche se si toccano o condividono lo sfondo.
- **F2 — Nuvolette collegate** (lobi uniti da un collo, stesso parlante): ⚠️ *decisione utente* — (a) lobi separati in sequenza [proposta default], (b) gruppo unico, (c) lobo selezionato + lobi collegati come contesto attenuato.
- **F3 — Navigazione** swipe avanti/indietro nell'ordine di lettura **per vignetta**, poi dentro la vignetta (LTR/RTL/verticale).
- **F4 — Testo fuori balloon** (didascalie, narrazione, `text_free`): zoomabile come rettangolo, opzionale.
- **F5 — Degrado sicuro.** Se la forma non è affidabile si mostra un ritaglio rettangolare arrotondato *con il contesto*, mai una forma sbagliata.

### 2.2 Qualità (misurabili — nuovo)

| Id | Criterio | Soglia proposta |
|---|---|---|
| Q1 | Recall nuvolette (IoU box ≥ 0.5) sul set di test | ≥ 95 % |
| Q2 | Unioni errate (cutout che contiene ≥ 2 blocchi di testo distinti) | 0 sul set di test |
| Q3 | Qualità del contorno: Boundary F-score (tolleranza 2 px a risoluzione nativa) | ≥ 0.85 mediana |
| Q4 | Coda e bordo d'inchiostro inclusi | ≥ 90 % delle nuvolette con coda |
| Q5 | Pixel estranei (disegno/cornice) nel cutout | ≤ 2 % dell'area mediana |
| Q6 | Ordine di lettura corretto (pagine del set) | ≥ 95 % delle coppie consecutive |

### 2.3 Non funzionali

- **N1 — Latenza:** cutout visibile ≤ 300 ms dopo il tocco sul Fold8 (con prefetch quasi sempre istantaneo); detection pagina ≤ 500 ms in background.
- **N2 — Stili:** comic occidentali (anche painted/colorati), manga B/N, webtoon, fumetto europeo (es. PK).
- **N3 — Licenze:** tutto Apache-2.0/MIT/BSD, **nessun pacchetto o peso Ultralytics** (AGPL), nessun peso addestrato su dati solo-accademici (Manga109) se si punta all'upstream.
- **N4 — Peso:** obiettivo < 40 MB di modelli (oggi ~69 MB).
- **N5 — Verificabilità:** ogni modifica alla pipeline si valuta con lo script di metriche sul set di test, non a occhio.

---

## 3. Esperimenti (16 ott, RTX 4090 + CPU, pagine del calib set)

### 3.1 Detection — `scripts/compare_det.py`, `scripts/v4s.py`

| Pagina | YOLOv8m ogkalu (v1) | RT-DETR-v2 ogkalu (3 classi) | "v4-s" int8 ogkalu (11 MB) |
|---|---|---|---|
| Absolute Superman p.4 | 12 | 11 bubble + 11 testo | 11 + 11, 51 ms CPU 4 thread |
| Absolute Superman p.8 | 13 | 13 + 13 | 13 + 13 |
| Saga p.3 | 11 | 11 + 11 | 11 + 11 |
| One Piece p.4 | 12 | 12 + 12 | 12 + 12 |
| Jujutsu Kaisen p.3 | 13 | 11 + 11 (+7 text_free) | 11 + 11 (+4) |
| Monstress p.4 | 10 | 11 + 11 | 10 + 10 |

- Tutti e tre separano le nuvolette adiacenti → **S4 non è un problema di detection**.
- I modelli ogkalu nuovi danno **anche il blocco di testo (`text_bubble`) di ogni nuvoletta** → invariante "1 nuvoletta = 1 blocco di testo" e seed precisi per la segmentazione.
- `detector-v4-s_int8.onnx` (11 MB, Apache-2.0, formato DETR-like `images + orig_target_sizes → labels/boxes/scores`, non Ultralytics): stessa resa del modello grande a ~1/15 del peso. **Candidato detector v2.**

### 3.2 Maschera — `scripts/compare_mask.py` (griglie in `out/mask*/grid_*.png`)

| Metodo | Esito |
|---|---|
| **A** EdgeSAM su pagina intera (v1) | bordo perso, contorno grumoso, coda spesso assente, lati tagliati dritti |
| **B** EdgeSAM sul **crop** della nuvoletta (box +35 %) | ~6× più risoluzione (≈ 2 px/cella), contorno liscio; include a volte disegno adiacente; coda parziale |
| **C** Classico a risoluzione nativa: regione "carta" del colore di riempimento → **watershed geodetico tra i blocchi di testo** (separa nuvolette che si toccano) → riempimento buchi → anello d'inchiostro | bordo nitido, coda inclusa, nuvolette adiacenti separate; ma l'anello "cresce" lungo inchiostro connesso (cornici, tratteggi manga) e risulta tratteggiato dove il bordo è grigio |
| **D** C limitato da B dilatato | il migliore: bordo e coda nitidi, niente vicine né cornici; restano difetti dell'anello (prototipo grezzo) |

Conclusione: **il salto di qualità viene da (1) segmentare sul crop e non sulla pagina, (2) costruire il bordo a risoluzione nativa dall'immagine, non da una maschera ingrandita, (3) usare i blocchi di testo come seed e separatori.** Nessun modello "pronto" risolve da solo F1+Q3 su tutti gli stili.

---

## 4. Panorama modelli (aggiornato ott 2026)

| Modello | Tipo | Stili | Licenza | Note per noi |
|---|---|---|---|---|
| ogkalu `comic-speech-bubble-detector-yolov8m` (v1) | detect 1 classe | tutti | Apache sul card, **ma Ultralytics/AGPL** | box stretti, 26 MB |
| **ogkalu `comic-text-and-bubble-detector`** | RT-DETR-v2 r50 (43 M) + `v4-s` int8 11 MB | manga, webtoon, manhua, comic (11k img) | Apache-2.0, non Ultralytics | bubble + text_bubble + text_free → **adottare** |
| ShadowB `Manga109-panel-balloon-text-yolov26-segmentation` | YOLO26s-seg, frame/text/balloon | solo manga | MIT, ma Ultralytics + Manga109 | maschere pronte, utile come riferimento/eval |
| mayocream `manga109-segmentation-bubble` | YOLO11n-seg @1600 | manga | Apache sul card, Ultralytics + Manga109 | idem |
| Koharu `koharu-layout-rfdetr-seg-2xl-1152` | RF-DETR-Seg 2XL, 4 classi | manga | "other" (Manga109) | mask mAP 0.57: bordi non migliori dei nostri |
| kitsumed `yolov8m_seg-speech-bubble` | YOLOv8m-seg | misto | GPL-3.0 | già scartato |
| Magi v2/v3 (Oxford) | Florence-2, testo/vignette/code | manga | ricerca | troppo grande; utile per idee (code, ordine) |
| EdgeSAM (v1) | SAM distillato, box prompt | generico | Apache-2.0 | ok **se usato sul crop** |
| Light HQ-SAM | SAM con token alta qualità | generico | Apache-2.0 | bordi migliori di SAM; da valutare solo se B+C non bastano |
| SAM 3 / EfficientSAM3 | segmentazione per concetto testuale ("speech bubble") | generico | SAM License (gated) | **offline** come "insegnante" per pseudo-etichette; non on-device |
| RF-DETR-Seg N/S (Roboflow) | DETR + maschere, export TFLite | — | Apache-2.0 | base per un **modello nostro** addestrato su corpus misto |

Dataset: MangaSeg (CVPR 2025, maschere balloon su Manga109; immagini solo accademiche), eBDtheque (100 pagine comic, contorni con coda), il nostro calib set (340 pagine miste) + PK.

---

## 5. Architettura proposta (v2)

```
pagina ──► Detector v2 (ogkalu v4-s, 640) ──► bubble[] + text[] + free_text[]
                                                │
                     associazione testo↔nuvoletta (1:1; scarto/split se 0 o ≥2)
                                                │
                     pannelli (classico: gutter/cornici) ──► ordine di lettura
                                                │  (cache per pagina)
tocco ─► hit-test su box + blocco testo ─► crop nativo (box +35 %)
                                                │
         ┌──────────────────────────────────────┴────────────────────┐
         │ Segmentazione per-nuvoletta, a risoluzione nativa          │
         │ 1. regione "carta" (colore di riempimento stimato dal     │
         │    blocco di testo)                                        │
         │ 2. watershed geodetico tra TUTTI i blocchi di testo nel    │
         │    crop → la nostra regione non può inglobare le vicine    │
         │ 3. vincolo forma: EdgeSAM sul crop (B) dilatato            │
         │ 4. bordo = fascia esterna di spessore misurato             │
         │    (non crescita su pixel scuri) + AA 1 px                 │
         │ 5. gate di confidenza (area, solidità, testo contenuto,    │
         │    contatto bordo crop) ─► altrimenti F5 (rettangolo)      │
         └────────────────────────────────────────────────────────────┘
                                                │
         rendering: bitmap nativo + alpha, **niente outline sintetico**;
         opzione "contesto attenuato" (fuori maschera al 20-30 %)
```

Cosa sparisce dalla v1: `mergeLinked`/`groupLinked`/`corridorIsFill`, union-glue SAM, `bridgeZones`, `clipToLobeRegion`, `snapToEdges`, ritraccia+Chaikin dell'outline, encode SAM di pagina in prefetch. `BubbleFloodExtractor` viene sostituito dai passi 1-2.

Mitigazione del rischio "rendering": con il **contesto attenuato** un errore di maschera di pochi px diventa invisibile, mentre con il cutout puro (v1) diventa un bordo sbagliato.

**Medio termine (opzionale):** addestrare un modello di segmentazione nostro (RF-DETR-Seg-N/S, Apache) sul corpus misto, con pseudo-etichette dalla pipeline D + SAM 3 offline sulla 4090, corrette a mano → sostituisce i passi 1-4 con una sola inferenza e restituisce anche le code.

---

## 6. Piano a fasi (ogni fase chiude con le metriche di §2.2)

| Fase | Contenuto | Dove | Gate |
|---|---|---|---|
| **V0** | Set di test: ~60 pagine miste (comic, painted, manga, webtoon, PK) con maschere ground-truth semi-automatiche (D + correzione manuale) + `eval.py` (Q1-Q6) | playground Python | set + baseline v1 misurata |
| **V1** | Detector v2 (v4-s) → TFLite (onnx2tf; in alternativa ONNX Runtime Mobile) + associazione testo; rimozione `mergeLinked` | Antigravity (conversione) + Claude | Q1, Q2 ≥ v1 |
| **V2** | Segmentazione v2 (§5) raffinata **prima in Python** fino alle soglie, poi port Kotlin; EdgeSAM spostato sul crop | Claude | Q3-Q5 |
| **V3** | Rendering: niente outline sintetico, AA nativo, fallback F5, opzione contesto attenuato | Claude | verifica visiva + N1 |
| **V4** | Pannelli + ordine di lettura per vignetta | Claude | Q6 |
| **V5** (opz.) | Modello di segmentazione dedicato (RF-DETR-Seg) | 4090 | batte V2 sul set |

---

## 7. Decisioni aperte (utente)

1. **Nuvolette collegate** (F2): separate in sequenza (proposta), gruppo unico, o lobo + contesto?
2. **Rendering:** cutout puro come oggi, oppure ritaglio con contesto attenuato (più robusto)?
3. **SAM 3 offline** per le pseudo-etichette: serve rinnovare il token HF (`hf auth login`) e accettare la licenza su `facebook/sam3`.
4. **Fase V5** (addestrare un modello nostro): sì / più avanti / no.
5. Le modifiche non committate (`CROP_MARGIN` 0.10, `BOUND_GROW` 0.4) vanno **scartate**: la v2 rimuove quei meccanismi.
