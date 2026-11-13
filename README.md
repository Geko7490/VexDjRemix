# VEX Mashup Studio

Android mashup editor that analyzes two local songs and uses HTDemucs to isolate vocals, drums, bass and other instruments. The APK contains the ONNX runtime. The model is downloaded separately from the Model screen and verified before use.

## Build

Use JDK 17 and Android SDK 35. Run `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`. The GitHub Actions workflow builds an installable debug APK and publishes it as a run artifact.

## Use

1. Open **Model** and download HTDemucs (166 MB).
2. Import two songs under **Studio**, select a mode, and create the mashup.
3. Select an arrangement, render it to preview, or tap a track lane to edit its mix and timing. Projects and edits save automatically.
4. Export or share a 16-bit or 24-bit stereo WAV. Android supports the input codecs installed on the device. The app uses Android’s file picker and processes audio locally.

## Engine and verification

- Fixed-length 44.1 kHz stereo HTDemucs inference with 25% overlap and file-backed stems. The pinned MIT-licensed [StemSplit conversion](https://huggingface.co/StemSplitio/htdemucs-onnx) stores the weights in FP16 and takes float32 PCM. The selected runtime uses the CPU, with selectable thread counts.
- ONNX Runtime directly loads the published waveform-to-stems graph. MDX-Net and BS-RoFormer exports vary in preprocessing, supported stems and weight licensing. LiteRT would require a separate validated conversion. NNAPI and GPU provider performance have not been validated.
- Spectral flux, tempo periodicity, downbeat accents, chroma and stem energy drive a constant-tempo, 4/4 phrase planner. Structural section labels are estimates. A weak beat or incompatible pairing is reported instead of generating a low-confidence arrangement.
- Stereo-linked WSOLA and sinc resampling provide separate tempo and pitch controls. The mixer applies track filtering, gain, stereo balance, dynamic masking reduction, mode effects, RMS normalization and a linked peak limiter.
- Timeline preview is rendered to disk. Track edits reuse stems and cached time-warped clips. The analysis and output level values use dBFS, and the meter counts premaster overloads.
- Instrumentation tests cover the Android decoder and real HTDemucs inference when the model is installed. Run `./gradlew connectedDebugAndroidTest` with a device connected. A physical POCO X6 Pro test, GPU/NNAPI, MP3/FLAC export, LUFS measurement, tempo mapping for variable meter, and formant-preserving pitch shift are not included in the verified scope.
