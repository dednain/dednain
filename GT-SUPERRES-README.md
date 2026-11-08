# GT SuperRes AI 3.0 — Screenshot Edition

This version fixes the problems reported when using v2 on Infinix GT 20 Pro.

## Changes in v3
- Removed the **live full-screen overlay**, the self-capture feedback loop, and the 5 FPS stale-frame display.
- Removed `FLAG_SECURE` and all MediaProjection, overlay, foreground-service and notification permissions. The app should no longer prevent normal screenshots or force the Android system's screen-share dialog.
- Added the Android system image picker (no photos permission necessary), and handling incoming images via Share.
- Added Save PNG and Share PNG through MediaStore.
- Added a slider to adjust how much of the trained ESRGAN neural output is blended with original image pixels, to preserve text and small details.
- Limits still-image output resolution and processes larger inputs in tiles; leaves original pixels present instead of stretched 720p tiles.

## What v3 can and cannot do
It can enhance **selected static screenshots and photos** on-device with a small pre-trained TensorFlow Lite ESRGAN model. It cannot replace in-game frames, create real HDR lighting, or enhance every Android app in real time. The previous live filter was removed specifically because it was slow, low-quality, blocked screenshots and did not work as a universal real-time AI renderer.

No root. No network or screenshot permissions. All pictures stay on the phone. Source and binary compile via GitHub Actions.

## Installation
Because GitHub Actions builds are debug-signed, Android might reject installing this version over v2 if the signing key differs. In that case stop v2, **uninstall the old GT SuperRes AI**, then install the APK extracted from GitHub artifact (uninstall removes app-specific data; previously saved pictures in the gallery normally remain).

1. Use the phone's screenshot gesture/buttons.
2. Open GT SuperRes AI → choose screenshot, or use Android Share → GT SuperRes AI.
3. Start with AI strength around 35%; increase only if the result genuinely improves details.
4. Save the improved picture in Pictures/GT-SuperRes, or tap Share.

**No test on an actual Infinix GT 20 Pro has been performed. Compile success does not guarantee image quality.**
