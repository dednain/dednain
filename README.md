# GT Real Graphics Engine — GPU-native alpha

Standalone Android 14+ screen post-processor targeting the Infinix GT 20 Pro.

## Current implementation
- Android 14 MediaProjection app-window selection flow. Choose Standoff 2 as a single app window.
- GPU-native capture: MediaProjection VirtualDisplay writes directly to a Surface backed by SurfaceTexture / OpenGL ES external texture. The current path avoids ImageReader-to-Java-RGBA copies and per-frame CPU texture uploads.
- GLES 3.0 multi-pass renderer:
  1. Scene grade in linear light with sRGB decode, exposure, contrast, saturation, temperature, shadow lift and filmic/ACES-style highlight roll-off.
  2. Quarter-resolution bright-pass extraction with adjustable threshold.
  3. Horizontal and vertical Gaussian bloom blur.
  4. Final composite with bloom, edge sharpening, vignette and sRGB output transfer.
- Adjustable 50–100% GPU render resolution for performance/quality tuning.
- Natural, Cinematic and Competitive starting profiles.
- Foreground notification with capture input FPS telemetry and Stop action.
- App-window resize handling and settings persistence.
- No INTERNET permission, game APK modification, game asset replacement, process injection or anti-cheat bypass.

## Important limitations
This is a screen-space image processor, not a replacement game renderer, graphics driver, DLSS implementation or true HDR output pipeline. MediaProjection exposes rendered pixels, not the game's depth buffer, normals, geometry, materials, motion vectors or light sources. The current build cannot create physically correct new dynamic 3D shadows, true ray-traced reflections, new geometry or missing highlight data. Shadow lift, local contrast, bloom and filmic tone mapping are post-processing approximations. Bloom is a real multi-pass Gaussian image-space effect; it is not a new light source.

The 144 Hz display refresh rate does not guarantee 144 captured or processed frames per second. The notification reports incoming capture-frame rate, not a certified GPU render FPS. The actual performance, thermals, visual quality, external-texture extension support and overlay compatibility must be tested on the physical device. No physical-device test has yet been performed.

Select only the Standoff 2 app window in Android's system capture picker. Full-display capture may include the overlay and cause recursion. Android's standard screen-capture consent and draw-over-other-apps permission remain required.

## Build
GitHub Actions workflow "Build GT Real Graphics Engine APK" compiles a debug APK and uploads the artifact "GT-Real-Graphics-Engine-APK".

## Target
- Android 14+ (API 34)
- OpenGL ES 3.0 and external-image ESSL3 extension
- Intended device: Infinix GT 20 Pro
