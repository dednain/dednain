# GT Real Graphics Engine — Android alpha

A standalone Android 14+ GPU post-processing overlay prototype targeting the Infinix GT 20 Pro.

## Implemented in this first build
- Android MediaProjection user-choice capture. In the system picker, choose Standoff 2 as a **single app window**, not the entire display.
- OpenGL ES 3.0 GPU fragment-shader pipeline.
- RGBA frame upload to a persistent GPU texture.
- Live controls: saturation, contrast, exposure, HDR-look tone mapping, bloom-like glow, edge sharpening and shadow-detail lift.
- A separate, non-touchable overlay window with a foreground notification and Stop action.
- Capture-size change handling and a small reusable frame-buffer pool.
- No INTERNET permission. No Standoff 2 APK, source, assets, process injection or game-file changes.

## What this is not
This is not a graphics driver, DLSS, a replacement game renderer or a true HDR pipeline. MediaProjection gives the app rendered pixels only; it does not expose the game's depth buffer, normals, geometry, materials, motion vectors or light sources. Therefore the current shader cannot create physically correct new dynamic shadows, true geometric reflections, new player/weapon models or missing HDR highlight information. The controls are image-space approximations.

The screen capture path includes Android capture, frame copying and a separate overlay. It is not guaranteed to sustain 144 processed FPS. A 144 Hz display and 144 newly captured/processed frames per second are different. FPS is measured in the service notification; actual performance must be tested on the phone. Select only Standoff 2 in Android's single-app capture picker. Selecting the entire display can capture the overlay again.

The app requests the standard Android screen-capture consent and draw-over-other-apps permission. It does not suppress system prompts or bypass DRM/secure surfaces.

## Build
GitHub Actions workflow "Build GT Real Graphics Engine APK" compiles the debug APK and uploads the artifact "GT-Real-Graphics-Engine-APK".

## Target
- Android 14+ (API 34)
- OpenGL ES 3.0
- Infinix GT 20 Pro is the intended test device; no on-device validation has yet been performed.
