# KONTUR ENGINE — A-03 Training Arena

This isolated branch contains the KONTUR Android FPS prototype source bundle.

- **Engine:** C++17 + OpenGL ES 3.0
- **Platform:** Android (landscape)
- **Map:** A-03 Training Arena, procedural 3D geometry based on the original concept art
- **Prototype controls:** left-side movement stick, right-side touch-look, fire control
- **Build:** GitHub Actions creates a debug APK artifact

## Get the source

Download [KONTUR_ENGINE_SOURCE.zip](./KONTUR_ENGINE_SOURCE.zip) from this branch and extract it. Open the `KONTUR_ENGINE_0_1` folder in Android Studio.

## Get the APK

Open the **Actions** tab and select **Build KONTUR Android APK**. It also runs automatically when this branch receives a push. After a successful run, download the artifact named `KONTUR-ENGINE-0.2-debug-apk`.

## Current scope

This is an early playable prototype, not a finished online 5v5 game. The current map is a procedural blockout with the main landmarks and color layout. Detailed PBR materials, full character/weapon models, animation, sound, robust collision, bots and multiplayer are future work.

The work is isolated on the `kontur-engine` branch; the default profile README branch is unchanged.
