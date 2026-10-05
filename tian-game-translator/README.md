# Tian Game Translator v0.2

Android 15 game translator for English -> Indonesian, designed for low-end phones such as Samsung A06.

## What changed from v0.1
- OCR region presets: whole screen, bottom 45%, center 65%, top 55%.
- Translation overlays are anchored to OCR bounding boxes instead of using one generic translation panel.
- Slot-based replacement: when the game changes a dialog in the same area, the old translation is replaced only after the new translation succeeds.
- Last good translation is kept for about 9 seconds when OCR or translation temporarily misses a frame.
- Caps OCR work to the first 6 detected text blocks per cycle.
- Uses on-device ML Kit English -> Indonesian translation after the model is downloaded.
- Constrained overlay bounds and adaptive text size reduce messy placement.

## Build
The repository root contains a GitHub Actions workflow that builds this folder with Gradle and uploads the APK as an artifact.

## Use
1. Install APK.
2. Open app.
3. Download English -> Indonesian model.
4. Grant overlay permission.
5. Choose the OCR area (start with BOTTOM 45% for dialogue boxes).
6. Start translator and grant screen-capture permission.
7. Open the game.

## Limitations
- OCR quality depends on the game font and contrast.
- Some games do not expose their rendered text cleanly to screen capture.
- The overlay covers the original text area; it is not injected into the game's own UI.
