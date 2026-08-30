# Antigravity ⟷ Claude Code: Allineamento e Handover Lavori

*File di coordinamento per la collaborazione sullo sviluppo della feature Bubble Zoom in Komikku.*
*Ultimo aggiornamento: 30 Agosto 2026 — Antigravity (Tutti i Modelli TFLite Pronti & Esportati)*

---

## 1. Stato Modelli TFLite (Work Order Completato al 100%)

Tutti i modelli `.tflite` sono stati generati, calibrati e posizionati in `app/src/main/assets/models/`:

### 1. `bubble_detector_ogkalu.tflite` (Rilevamento Rapido)
* **File**: `app/src/main/assets/models/bubble_detector_ogkalu.tflite`
* **Size**: 26,516,834 byte (25.29 MB)
* **SHA-256**: `98e3938c48ff0986429fad638aefa3a1f3ac6b506863ded78d10e6d10d2be282`
* **Quantizzazione**: INT8 weights + activations (calibrazione 340 img `data.yaml`)
* **Latenza Fold8**: ~123 ms (GPU Delegate) / ~200 ms (XNNPACK CPU)
* **Tensori I/O**:
  * **Input**: `serving_default_args_0` `[1, 3, 640, 640]` (`float32`, quant: `(0.0, 0)`)
  * **Output**: `serving_default_output_0_output_dequant` `[1, 6, 8400]` (`float32`, quant: `(0.0, 0)`)

---

### 2. `bubble_detector_seg.tflite` (Segmentazione Neurale @ 1024px)
* **File**: `app/src/main/assets/models/bubble_detector_seg.tflite`
* **Sorgente**: `kitsumed/yolov8m_seg-speech-bubble`
* **Size**: 27,991,859 byte (26.70 MB, ridotto da 46.88 MB)
* **SHA-256**: `4b2d17fb6a6b059c6228ca8699a387576a4c58174fce9a2b28da310c291d1a15`
* **Quantizzazione**: INT8 static quantization LiteRT
* **Tensori I/O**:
  * **Input**: `serving_default_args_0` `[1, 3, 1024, 1024]` (`float32`, NCHW, quant: `(0.0, 0)`)
  * **Output 0 (Detect Head)**: `serving_default_output_0_output_dequant` `[1, 37, 21504]` (`float32`: 4 box + 1 score + 32 mask coeffs)
  * **Output 1 (Proto Masks)**: `serving_default_output_1_output_dequant` `[1, 32, 256, 256]` (`float32`: 32 proto a $256\times 256$)

---

### 3. `sam_encoder.tflite` (MobileSAM Vision Transformer Image Encoder)
* **File**: `app/src/main/assets/models/sam_encoder.tflite`
* **Size**: 28,170,064 byte (26.87 MB)
* **SHA-256**: `b3b734716433bbd14d5b139727a5988d775e22e7cad5632fe007c1c9f3f5fcef`
* **Formato**: FP32 / LiteRT Torch export (nessun degrado int8 sui ViT)
* **Tensori I/O**:
  * **Input**: `serving_default_args_0` `[1, 3, 1024, 1024]` (`float32`, NCHW)
  * **Output**: `serving_default_output_0_output` `[1, 256, 64, 64]` (`float32` image embedding)

---

### 4. `sam_decoder.tflite` (MobileSAM Prompt + Mask Decoder)
* **File**: `app/src/main/assets/models/sam_decoder.tflite`
* **Size**: 20,640,744 byte (19.68 MB)
* **SHA-256**: `c12448a26bbb35adc3d2f246d8c418cd82a8af3044f71a300acae60c81692d0b`
* **Formato**: FP32 / LiteRT Torch export
* **Tensori I/O**:
  * **Input 0 (Image Embedding)**: `serving_default_args_0` `[1, 256, 64, 64]` (`float32`)
  * **Input 1 (Bounding Boxes)**: `serving_default_args_1` `[1, 4]` (`float32` in coordinate px `[x1, y1, x2, y2]`)
  * **Output 0 (Mask)**: `serving_default_output_0_output` `[1, 1, 256, 256]` (`float32` logit mask)
  * **Output 1 (IoU Score)**: `serving_default_output_1_output` `[1, 1]` (`float32` confidenza maschera)

---

## 2. Sintesi Architettura & Prossimi Passi (per Claude)
* Tutti i file TFLite sono pronti nella cartella `assets/models/`.
* Con questi modelli, la dipendenza `ai.onnxruntime` può essere completamente rimossa dall'app.
* Claude può procedere con l'implementazione del refiner SAM in Kotlin e l'aggiornamento dell'interprete per il modello di segmentazione @ 1024px.
