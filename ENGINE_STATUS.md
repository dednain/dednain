# GT Real Graphics Engine — current status

Development branch: `gt-real-graphics-engine`

Current version: `0.3.7-alpha`

## Implemented
- CI build 0.2.0-alpha succeeds; APK zip alignment and v2 signature verification pass.
- Android 14 MediaProjection single-app capture consent.
- Trusted non-touchable accessibility overlay with full cutout coverage and foreground-package alpha switching (surface stays alive when transparent).
- Direct VirtualDisplay -> SurfaceTexture -> GLES external texture input (GPU-native frame path).
- Five-pass GLES renderer: linear-light scene grade, bright extraction, horizontal Gaussian blur, vertical Gaussian blur, final composite.
- sRGB decode/encode, linear-light grading, RGBA16F intermediate buffers when half-float color-buffer support exists (RGBA8 fallback), final ACES-style tone mapping, exposure, contrast, saturation, temperature, shadow lift, local contrast, bloom, sharpening and vignette.
- 50–100% render-scale control and Natural/Cinematic/Competitive profiles.
- Foreground notification with GPU renderer / RGBA16F capability, separate input capture FPS and rendered-frame submission FPS telemetry, plus Stop action.
- Saved settings and capture-resize handling.

## Not implemented / not claimed
- Access to the game's depth buffer, normals, geometry, materials or light sources.
- Physically correct dynamic 3D shadows, ray-traced reflections, ray tracing, path tracing, true HDR10 output, AI super-resolution, frame generation or 144 FPS guarantee.
- Physical Infinix GT 20 Pro runtime validation, including confirmation that Standoff 2 touch controls pass through and no capture feedback/ghosting remains.

## Next validation gates
1. Build a fresh APK in CI.
2. Install on the GT 20 Pro and verify the ESSL3 external texture shader compiles on its GPU.
3. Verify app-window capture does not recurse into the overlay.
4. Measure capture input FPS, GPU render time, thermals and frame pacing.
5. Tune render scale and shader cost from actual device measurements before adding further effects.


## 0.3.2-alpha
- Opaque, non-blended full-frame output with explicit clearing of all GPU render targets.
- SurfaceTexture coordinates use the Android-provided transform matrix without an extra manual vertical flip.
- Conservative default post-processing and one-time settings migration to remove old high-bloom/high-sharpen settings.
- Capture/output dimension telemetry and reduced crop when aspect ratios diverge.
- Pending physical validation on Infinix GT 20 Pro.


## 0.3.3-alpha
- Accessibility foreground tracking ignores transient Android system windows instead of hiding the filter.
- MediaProjection callback is registered before the GPU surface can create the VirtualDisplay.
- Added edge-aware image-space detail enhancement and bounded sharpening.
- Added a persistent micro-detail setting.
- Requires physical validation on Infinix GT 20 Pro; no claim of adding new game geometry or source textures.


## 0.3.4-alpha
- WindowManager-level alpha controls visibility.
- Non-target app/system window events hide the layer; target game events show it.
- Own overlay events are ignored; opening the engine UI hides the layer.
- Requires real-device validation for touch pass-through and PiP/system UI behavior.


## 0.3.5-alpha
- Reset the target-game foreground state when the engine activity resumes, preventing a stale foreground flag from showing the overlay over the engine UI on a subsequent capture start.


## 0.3.6-alpha
- Prevent transient window events from hiding the filter while the target game remains foreground.
- Ignore Android system packages and unknown package events.
- Only real activity state changes control the overlay.
- Requires device validation on Infinix GT 20 Pro.


## 0.3.7-alpha
- Safer SurfaceView visibility and stale-frame fail-safe.
- Overlay hidden on every non-game foreground activity.
- No device-level validation yet.
