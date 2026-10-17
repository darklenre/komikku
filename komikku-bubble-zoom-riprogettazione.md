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

### 3.3 Fase V0 — set di test e baseline misurata (17 ott)

**Set** (`playground/eval/testset/`, fuori dal repo: pagine coperte da copyright): 56 pagine, 7 serie
(Absolute Superman, Jujutsu Kaisen, Monstress, Omniscient Reader [webtoon], One Piece, PK [scansione
italiana], Saga) → **430 nuvolette/didascalie**, 425 valutate (5 ambigue `ignore`).

**Ground truth**: istanze = proposte di RT-DETR-v2 large riviste a mano pagina per pagina
(`corrections.json`: 4 falsi positivi tolti, 5 ambigue); maschere = **SAM 2.1 hiera-large** (Apache-2.0,
non usato da nessuna pipeline) sul crop, prompt box + punto sul proprio testo + punti negativi sul testo
delle vicine, buchi riempiti; tutte le 430 riviste a vista, **27 marcate `mask_bad`** (chat/UI webtoon,
fondi neri) ed escluse dalle metriche di bordo. Limite: precisione del GT ≈ 1-3 px, e il teacher è della
famiglia SAM (possibile bias a favore di B).

**Metodo** (`scripts/eval.py`): per ogni nuvoletta si simula un **tocco al centro del suo testo**, risolto
con lo stesso hit-test dell'app (`BubbleHit.pickIndex`), e si confronta il ritaglio ottenuto con il GT.
La v1 è misurata **eseguendo il codice Kotlin vero** sull'emulatore (`BubbleEvalActivity`, solo debug:
decodifica come il pager, tiling come il webtoon, `BubbleDetection.detect` + `BubbleExtractor.extractBubble`).

| Metodo | ok | mancate | unione | unione *non adiacente* | IoU | BF | BF≥0,85 | pixel estranei | pixel persi |
|---|---|---|---|---|---|---|---|---|---|
| **v1 (app, emulatore)** | 62,1 % | 1,2 % | 36,0 % | **19,8 %** | 0,68 | 0,59 | 27 % | 23,2 % | 3,9 % |
| v4-s, nessuna fusione, rettangolo arrotondato | 95,5 % | 1,2 % | 2,1 % | 0 % | 0,64 | 0,04 | 0 % | 30,1 % | 0,4 % |
| **B** v4-s + EdgeSAM sul crop | 94,6 % | 1,2 % | 1,9 % | 0 % | **0,90** | **0,85** | 68 % | **0,5 %** | 3,6 % |
| B + riempimento buchi | 94,8 % | 1,2 % | 1,6 % | 0 % | 0,90 | 0,86 | 69 % | 0,7 % | 3,7 % |
| D classico ∩ B | 90,6 % | 1,2 % | 1,9 % | 0 % | 0,84 | 0,70 | 43 % | 0,6 % | 7,2 % |

*ok* = tocco sulla nuvoletta giusta, senza unione, IoU ≥ 0,5 · *unione* = il ritaglio contiene ≥ 50 % del
testo di un'altra nuvoletta · *non adiacente* = quella nuvoletta non tocca nemmeno la nostra · *BF* =
F-score del contorno con tolleranza max(2 px, 1 % della diagonale) · *estranei* = ritaglio fuori dal GT ·
*persi* = GT fuori dal ritaglio (nuvoletta morsicata).

Lettura:
- **S4 confermato e quantificato**: la v1 unisce nuvolette in un tocco su tre, e in uno su cinque unisce
  nuvolette che non si toccano (One Piece: 50 %). Togliendo `mergeLinked` e usando v4-s le unioni non
  adiacenti vanno a **0**; il 1,6-2,1 % residuo sono nuvolette *fisicamente collegate* (decisione F2) e
  riquadri chat del webtoon con GT `mask_bad`.
- **S5 confermato**: la v1 porta in media il 23 % di pixel estranei (fondo, vicine, cornici); B ne porta lo 0,5 %.
- **B batte D** sulle metriche: D è più netto sull'inchiostro (visibile a occhio su PK/manga) ma fallisce
  su didascalie scure e riempimenti non uniformi (Absolute, webtoon). → V2 parte da **B** e usa il
  classico solo come *raffinamento del bordo* con gate di confidenza, non come maschera principale.
- Margine residuo per V2: BF ≥ 0,85 solo sul 69 % delle nuvolette (Q3 chiede ≥ 0,85 in media → già
  0,86, ma One Piece è a 0,73); pixel persi 3,7 % (code e scallop tagliati, da verificare).
- Costi v1 sull'emulatore (x86, CPU): detection mediana 916 ms/pagina, ritaglio mediano 104 ms,
  p90 847 ms (quando parte SAM su pagina). Da rimisurare su telefono reale.

---

### 3.4 V1-V3 in Kotlin — misurato sull'emulatore (17 ott)

Stesso set e stesso `eval.py`; build debug eseguita da `BubbleEvalActivity` (codice dell'app, non prototipo).

| Metodo | ok | mancate | unione | unione non adiacente | IoU | BF | BF≥0,85 | estranei | persi |
|---|---|---|---|---|---|---|---|---|---|
| v1 (app, baseline §3.3) | 62,1 % | 1,2 % | 36,0 % | 19,8 % | 0,68 | 0,59 | 27 % | 23,2 % | 3,9 % |
| **v2 app** (v4-s TFLite + SAM sul crop + levigatura) | **95,8 %** | 1,2 % | 1,6 % | **0 %** | **0,90** | **0,86** | 68 % | **0,6 %** | 3,2 % |
| prototipo B Python (riferimento) | 94,8 % | 1,2 % | 1,6 % | 0 % | 0,90 | 0,86 | 69 % | 0,7 % | 3,7 % |

- Il port Kotlin riproduce il prototipo (maschere IoU mediana 0,98 con quelle Python).
- **Trovato e corretto**: con `org.tensorflow:tensorflow-lite:2.16.1` il detector RT-DETR dava box sbagliati
  (IoU 0,87 vs ONNX: 93,9 % ok, BF 0,78); con **LiteRT 1.4.2** (`com.google.ai.edge.litert`, successore di TFLite,
  stessa API Java) IoU 0,993. Dipendenze migrate a LiteRT.
- Provati e **scartati** (nessun guadagno o peggioramento): divisione geodetica della maschera tra i testi
  (rovina didascalie e nuvolette con testo vicino), ritaglio con i box delle vicine (cambia 6 maschere su 418).
- Residui: One Piece BF 0,72 (bordi a tratteggio manga), Omniscient chat/UI (GT `mask_bad`), code e scallop tagliati (persi 3,2 %).
- Latenze emulatore x86 **CPU** (nessuna GPU utile): detection 520 ms/pagina, ritaglio 450 ms mediana (un encode EdgeSAM per nuvoletta).

### 3.5 Accelerazione hardware (Fold8, findings di agosto + ora)

Dai test di agosto sul Fold8 (Snapdragon, Adreno):
- **EdgeSAM encoder su GPU delegate ~150-180 ms** (vs ~800 ms CPU, ~2 s MobileSAM), ma solo **forzando** il delegate:
  `CompatibilityList` dà un falso negativo sugli Adreno recenti. Decoder su CPU (la GPU rifiuta un `CONCAT` 3D), costo trascurabile.
- **NNAPI scartato**: rifiutava/partizionava i grafi ed era più lento di XNNPACK (ed è deprecato da Android 15).

Conseguenze per la v2:
- SAM ora gira **una volta per nuvoletta** sul crop (non una per pagina): sul Fold8 ≈ 150-180 ms a nuvoletta su GPU.
  Quindi il **prewarm in background** delle maschere di tutta la pagina parte **solo se l'encoder è su GPU**
  (`SamRefiner.isFast`); su CPU il ritaglio si calcola al tocco (~0,5-0,8 s).
- **Detector v4-s**: il delegate GPU viene tentato (forzato come SAM). L'analizzatore TFLite indica che backbone
  ed encoder (conv, la gran parte del calcolo) sono compatibili, la testa DETR (GATHER_ND, TOPK, cast int64) resta su CPU →
  esecuzione partizionata. Sull'emulatore il delegate viene rifiutato (`ADD` v4) → CPU. **Da misurare sul Fold8**
  (log `TfliteBubbleDetector: ready (backend=…)` e `inference N ms`).
- **NPU (Hexagon)**: non raggiungibile con l'API Interpreter/NNAPI; la via moderna è LiteRT 2.x `CompiledModel` con
  acceleratore NPU Qualcomm (compilazione AOT/JIT del modello). Candidato per una fase successiva se la GPU non basta.

### 3.6 Misurato sul Fold8 (SM-F971B, SM8850, 17 ott)

Stesso harness (`BubbleEvalActivity`, build debug arm64, schermo tenuto acceso durante il run):

| Configurazione | ok | IoU | BF | estranei | ritaglio mediana / p90 | detector (solo inferenza) |
|---|---|---|---|---|---|---|
| encoder + decoder SAM su GPU (default fp16) | 96 % | 0,64 | **0,04** | 29 % | — | GPU |
| encoder GPU fp16, decoder CPU | 95,8 % | 0,88 | 0,78 | 2,0 % | 153 / 311 ms | GPU |
| **encoder GPU fp32, decoder CPU** (scelto) | **95,8 %** | **0,90** | **0,86** | **0,6 %** | **204 / 352 ms** | GPU 578 ms |
| detector solo CPU (XNNPACK 4 thread) | — | — | — | — | — | CPU 565 ms |

- **Decoder SAM sulla GPU = maschere inutilizzabili** (con LiteRT 1.4.2 il delegate lo accetta ma restituisce logit errati;
  il gate di plausibilità le scartava → tutto rettangolo arrotondato, senza errori nei log). Ora il decoder è forzato su CPU
  (costa pochi ms). Senza il set di test sarebbe passato inosservato.
- **Encoder SAM in fp16** sposta i bordi di qualche pixel → `setPrecisionLossAllowed(false)`: +50 ms, qualità pari alla CPU.
- **Detector**: GPU partizionata (backbone su GPU, testa DETR su CPU). A dispositivo freddo 240-270 ms, dopo ~15 min di
  test consecutivi ~580 ms, come la CPU (throttling termico). Resta su GPU (libera la CPU), gira comunque in background.
- Lo schermo che si spegne/blocca falsava i tempi e congelava il processo: l'harness ora tiene lo schermo acceso.

### 3.7 Bordi: prompt a punti e aggancio all'inchiostro (17 ott)

Obiettivo utente: ritaglio pulito con **bordi perfettamente definiti** (contesto attenuato solo come opzione).

- **Prompt a punti** (decoder ufficiale `edge_sam_3x_decoder.onnx`, l'encoder dell'app è `edge_sam_3x`, corr. 0,999):
  box + punto positivo sul testo (+ negativi sulle vicine), maschera col punteggio migliore. Guadagno marginale
  (ok +1 pt, persi 3,0 → 2,4 %; su 58 casi problematici ne sistema 14 e ne guasta 8) → **non adottato** (richiederebbe
  un secondo decoder TFLite).
- **Limite del GT sui bordi**: le maschere SAM 2.1 mettono il bordo a volte dentro, a volte fuori dal tratto nero, e
  sono frastagliate di 1-2 px → BF non distingue più "bordo perfetto". Aggiunta una misura **senza GT**
  (`scripts/outline_metric.py`): quota del bordo del ritaglio che include il contorno nero della nuvoletta.
- **Aggancio all'inchiostro** (`inkSnap`, `scripts/inksnap.py`): la maschera cresce sul tratto scuro adiacente solo se è un
  tratto sottile tra riempimento e sfondo chiaro (mai capelli/zone scure), + un anello di pixel semiscuri per il bordo
  morbido; tagli tra nuvolette collegate nel riempimento uniforme levigati di più. Solo nuvolette a fondo chiaro.

| | contorno nero incluso | contorno tagliato | BF (vs GT) | estranei | ritaglio Fold8 |
|---|---|---|---|---|---|
| GT (riferimento) | 68,2 % | 21,1 % | — | — | — |
| app prima | 59,7 % | 31,7 % | 0,86 | 0,6 % | 204 ms |
| **app + inkSnap (Kotlin, Fold8)** | **70,3 %** | **21,4 %** | 0,84 | 0,7 % | 207 ms |

BF scende di 0,02 per la convenzione del GT (verificato a vista: i casi "peggiorati" hanno il contorno ora completo).
Residuo visibile: taglio frastagliato dove due nuvolette collegate sono separate da una linea d'inchiostro ("I AM JOÃO!").

**Completamento della forma delle nuvolette collegate (adottato, 17 ott)** — `BubbleCompletion` (prototipo `scripts/complete.py`).
Dove la maschera taglia il riempimento condiviso con una nuvoletta vicina (estremi del taglio sull'inchiostro, oltre il taglio
un'altra nuvoletta rilevata), il tratto mancante del contorno viene ricostruito come curva di Hermite che prosegue le tangenti
del contorno della nuvoletta ai due estremi; il rigonfiamento è dipinto col colore di riempimento, il nuovo bordo (e le due
giunzioni) col colore dell'inchiostro dello stesso spessore, con copertura dalla distanza dal bordo → si raccorda al tratto
originale. Fino a due tagli per nuvoletta (catene). Sicurezze: niente curva verso l'interno, area finale 80-135 % dell'originale.
Sul set: 84 nuvolette completate su 425 (Kotlin = prototipo), metriche invariate (il GT non contiene la forma ricostruita),
ritaglio +~15 ms. Verificato a vista: "I AM JOÃO!" diventa un ovale chiuso col suo bordo. Limiti: nuvolette tagliate dal bordo
del crop o da una cornice (non sono tagli tra nuvolette) restano come sono.

**Completamento al collo (adottato, 17 ott, primo tentativo; il metodo sopra resta come ripiego)** —
`BubbleCompletion.completeNeck` (prototipo `scripts/neck.py`). Caso utente: Absolute Superman #1 p.6, "You're a lifesaver…" /
"How about a basket…": SAM taglia lungo l'ultima riga di testo, il taglio è spezzato dalle lettere e il metodo sopra non lo
riconosce → bordo che "segue le lettere". Ora: F = riempimento condiviso (pixel chiari + box di testo) connesso al nostro
testo; se contiene il testo di una vicina, il **collo** = sui due lati dell'asse tra i due testi, i punti del bordo di F più
vicini all'asse (bordo del crop e spigoli dei box di testo esclusi); corda A-B, tangenti del contorno → Hermite (tangenti
0,7·AB, rigonfiamento ritagliato entro la corda). Cambia solo la zona del collo; altrove resta il bordo di SAM/inkSnap.
Sicurezze, tutte necessarie sul set: AB < 80 % dell'estensione della nuvoletta, inchiostro ai due estremi, SAM deve aver
tagliato il riempimento vicino al collo (≥ 0,25·AB px di bordo chiaro con chiaro oltre) e la corda deve stare vicino alla
maschera (mediana distanza ≤ 0,22·AB: esclude nuvolette senza contorno che toccano il margine bianco, Omniscient), crescita
sull'inchiostro solo entro la corda (oltre A/B il contorno è della vicina), apertura r = max(⌈t⌉+1, 0,08·AB) per i moncherini,
mai perdere il nostro testo (né ridipingerlo). Dopo un collo riuscito niente ripiego al 2° passaggio (taglio spurio).
Risultato sul set (56 pp. + la pagina utente): 76 nuvolette completate al collo, ~20 miglioramenti netti (testo tagliato
recuperato, morsi chiusi), 3-4 difetti minori residui (cuneo "We've got magic", puntino "D-meter's", gobba "HA.");
rispetto al metodo sopra pari o meglio sulle 58 in comune. Kotlin = prototipo (IoU mediana 1,0, min 0,99; serve la distanza
euclidea esatta — la chamfer cambiava l'esito dell'apertura — e i centri pixel interi nel riempimento del poligono).
JVM desktop: mediana 0 ms (uscita anticipata), p90 6 ms, max ~150 ms. Fold8 (harness, a caldo): il collo aggiunge ~0–20 ms
per ritaglio sulle pagine normali, ~60–95 ms sulle nuvolette collegate grandi (ritaglio intero 150–320 ms).
Rifinitura agli intagli (stesso giorno): il cuneo e il puntino erano l'angolo a V dove i due contorni si incontravano,
rimasto dentro la forma. Ora, entro R da A e da B: chiusura di raggio max(r, 0,25·AB) (su griglia imbottita di vuoto: il
bordo del crop non conta come nuvoletta) e ridipintura col colore del riempimento delle macchie scure **attaccate al
contorno** (le lettere non lo toccano; i box di testo invece sì, quindi escluderli non bastava). Spariti cuneo e puntino,
gobba "HA." quasi spianata, ripuliti anche resti di retino/arte agli intagli su ~15 nuvolette; nessuna lettera toccata.

**Tagli tra nuvolette collegate — raddrizzamento (provato, non adottato)** — `scripts/neckcut.py`: sostituire il tratto di bordo che attraversa il
riempimento con la corda tra i due estremi. Anche con salvaguardie (oltre il taglio deve esserci un'altra nuvoletta rilevata,
mai tagliare il testo, area modificata ≤ 12 %) il risultato è misto: sistema "I AM JOÃO!" e 2 casi simili, rovina nuvolette dipinte
senza contorno ("HA.") o cambia l'inclinazione di tagli già dritti. Metriche invariate. Resta un difetto minore aperto.

### 3.8 Ordine di lettura per vignette — V4 (17 ott)

**GT**: ordine di lettura annotato a mano su 40 pagine (`testset/order_gt.json`, direzione LTR/RTL; webtoon esclusi).
**Misura** (`scripts/eval_order.py`): ogni nuvoletta GT → la nuvoletta dell'app scelta da un tocco sul suo testo;
*esatte* = pagine con ordine tutto giusto, *coppie* = accordo su tutte le coppie, *successiva* = lo swipe porta alla
nuvoletta giusta.

| | pagine esatte | coppie | successiva corretta |
|---|---|---|---|
| v1 `BubbleReadingOrder` (fasce → colonne) | 35 % | 94,8 % | 69,4 % |
| v2 vignette (XY-cut) + v1 dentro la vignetta (Kotlin, Fold8) | 68 % | 97,1 % | 86,9 % |
| **v3 vignette come regioni tra canaline + band 0,6** (Kotlin, Fold8) | **72-75 %** | **97,5 %** | **88,0 %** |

**Vignette** (`BubblePanels`): XY-cut ricorsivo sulle canaline — righe/colonne dritte del colore della canalina per tutta
la regione (≥ 98,5 % dei pixel non coperti da nuvolette: le nuvolette che scavalcano una canalina non la nascondono).
Colore canalina sconosciuto → candidati (bordo pagina, bianco, nero), vince quello che trova più vignette. Albero
indipendente dalla direzione: RTL inverte solo le vignette affiancate. Calcolato sulla bitmap di detection (~10 ms).
Residui: vignette inclinate/irregolari (Absolute, Monstress art nouveau) e ordine dentro la vignetta (scambi di 1-2
nuvolette spesso ambigui). Varianti dell'ordine interno provate: nessun guadagno oltre il rumore.

**v3 (17 ott, commit `60b96a831a`)** — prima le vignette come **regioni connesse di non-canalina** (le nuvolette contano
come canalina; apertura 5×5 a 1200 px; regioni ≥ 1,5 % della pagina e ≥ 6 % per lato; scartata la cornice ≈ pagina
poco piena; un box che contiene ≥ 2 altri è sfondo → scartato, un box dentro un solo box più grande è un pezzo di
disegno → scartato), per il colore che dà più regioni con copertura ≥ 40 %; ordine con tagli ricorsivi righe→colonne
sui box, sovrapposizione tollerata 15 %, altrimenti "prima in alto". Candidati colore: + colori delle righe/colonne
uniformi (canaline grigie o sfumate, Absolute_S_04). Se nessun colore copre il 40 %: XY-cut come prima. Ricerca a lato
lungo fisso 1200 px (la bitmap di detection varia 1024–2047). Fasce dentro la vignetta 0,6 (da 0,5). Risolte
Absolute_S_03/04, Monstress_06, Saga_03, PK_026 (in parte). Provati e scartati: rete di canaline connessa al bordo (86 %),
cornice pagina come canalina, solo righe uniformi come canalina. Costo: detection mediana +70 ms sul Fold.
Limite delle euristiche ~88-89 %: per il 95 % servirebbe un modello di vignette (da misurare prima con Magi sul PC).

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
| **V0** ✅ 17 ott | Set di test: 56 pagine / 430 nuvolette con GT (SAM 2.1-L + revisione) + `eval.py` (Q1-Q3, Q5) + harness Kotlin `BubbleEvalActivity` (debug) | playground Python | fatto, §3.3 (Q4 coda e Q6 ordine: annotazioni ancora da fare, in V2/V4) |
| **V1** ✅ 17 ott | Detector v2 (v4-s) → TFLite (§8) + associazione testo; rimozione `mergeLinked` | Claude | Q1, Q2 ≥ v1 (§3.4) |
| **V2** ✅ base 17 ott | Segmentazione: **B portato in Kotlin** (§8, §3.4); restano bordo manga e code | Claude | Q3-Q5 |
| **V3** ✅ 17 ott | Rendering: niente outline sintetico, AA nativo, fallback F5, contesto attenuato | Claude | verifica visiva + N1 |
| **V4** ✅ 17 ott | Pannelli (XY-cut) + ordine di lettura per vignetta (§3.8) | Claude | Q6: 87 % successiva corretta (soglia 95 % non ancora) |
| **V5** (opz.) | Modello di segmentazione dedicato (RF-DETR-Seg) | 4090 | batte V2 sul set |

---

## 7. Decisioni (utente, 17 ott)

1. **Nuvolette collegate** (F2): **separate in sequenza** — ogni nuvoletta è un target, la navigazione le scorre in ordine.
2. **Rendering:** **ritaglio pulito della sola nuvoletta, bordi netti** (predefinito); il contesto attenuato dietro è un'**opzione** (`bubble_zoom_cutout_context`, off). Niente contorno sintetico.
3. **SAM 3 offline**: non necessario per ora (GT fatto con SAM 2.1-L).
4. **Fase V5**: **più avanti**.
5. Modifiche non committate a `CROP_MARGIN` / `BOUND_GROW`: messe in `git stash` (non cancellate); superate dalla v2.

---

## 8. Note di implementazione V1-V3 (17 ott)

**Detector** (`TfliteBubbleDetector`, asset `bubble_detector_v4s.tflite`, 23,7 MB, sostituisce YOLOv8m 26,5 MB):
1. upstream fornisce solo `detector-v4-s_int8.onnx` con quantizzazione *dinamica* (`ConvInteger`/`MatMulInteger`,
   non convertibili): `convert/dequant.py` ricostruisce il grafo fp32 de-quantizzando i pesi
   (parità con l'int8: 922 vs 926 box, IoU medio 0,99);
2. `fix_inputs.py`: batch 1, `orig_target_sizes` costante [1,1] → box normalizzati 0..1; onnxsim;
3. `strip_floordiv.py`: rimossa la correzione di segno della floor-division (`Sign`, senza builtin TFLite) — gli indici TopK sono ≥ 0;
4. onnx2tf → SavedModel (`-rtpo Erf`: `Erf` della GELU sostituito con approssimazione builtin); TFLite fp32 identico all'ONNX (922/922, IoU 1,000);
5. conversione finale con **TF 2.16** (`reconvert.py`, op version compatibili) e runtime dell'app migrato a **LiteRT 1.4.2**
   (con `tensorflow-lite` 2.16.1 i box erano sbagliati, §3.4). Pesi int8 dynamic-range. fp16 scartato (degrada le etichette).
- Input: pagina **stirata** a 640×640 (non letterbox, come in training), RGB 0..1 NHWC. Nessuna NMS (DETR), solo
  dedup stessa classe IoU > 0,7. Ogni blocco di testo è assegnato a una sola nuvoletta (`textOwners`).
- Preferenza confidenza: nuova chiave `bubble_zoom_confidence_detr`, default 40 % (i valori tarati per YOLO non valgono).
- Rimossi: `mergeLinked`/`groupLinked`/`corridorIsFill`, `Bubble.lobes`, `BubblePostprocess` (letterbox/decode YOLO), relativi test.

**Segmentazione** (`SamRefiner.cropMask`, `BubbleExtractor`, `BubbleMask`): metodo **B** di §3.3 —
crop = box + 35 % per lato a risoluzione nativa (max 1600 px), EdgeSAM sul crop con prompt box, logit
bilineari alla risoluzione del crop, buchi riempiti, solo il blob più grande, bordo AA 1 px; maschera
implausibile (< 2 % o > 98 % del crop) → rettangolo arrotondato (F5). Maschere in cache per nuvoletta;
il prewarm per pagina le calcola in background **solo se l'encoder è su GPU** (su CPU costerebbe secondi per pagina).
Rimossi: `BubbleFloodExtractor`, `snapToEdges`, `bridgeZones`, `clipToLobeRegion`, contorno sintetico + tracciamento, prewarm a livello pagina.

**Rendering** (`BubbleZoomOverlayView`): di default solo la nuvoletta ritagliata; con l'opzione "contesto" il crop opaco (`BubbleCutout.context`) viene disegnato scurito dietro; inquadratura sul corpo della nuvoletta (i suoi blocchi di testo). Rimosse le preferenze contorno/spessore.
