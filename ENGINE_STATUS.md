# GT Real Graphics Engine — current status

Development branch: `gt-real-graphics-engine`

## Implemented
- Android 14 MediaProjection single-app capture consent.
- Direct VirtualDisplay -> SurfaceTexture -> GLES external texture input (GPU-native frame path).
- Five-pass GLES renderer: linear-light scene grade, bright extraction, horizontal Gaussian blur, vertical Gaussian blur, final composite.
- sRGB decode/encode, filmic highlight roll-off, exposure, contrast, saturation, temperature, shadow lift, local contrast, bloom, sharpening and vignette.
- 50–100% render-scale control and Natural/Cinematic/Competitive profiles.
- Foreground notification with separate input capture FPS and rendered-frame submission FPS telemetry, plus Stop action.
- Saved settings and capture-resize handling.

## Not implemented / not claimed
- Access to the game's depth buffer, normals, geometry, materials or light sources.
- Physically correct dynamic 3D shadows, ray-traced reflections, ray tracing, path tracing, true HDR10 output, AI super-resolution, frame generation or 144 FPS guarantee.
- Physical Infinix GT 20 Pro runtime validation.

## Next validation gates
1. Build a fresh APK in CI.
2. Install on the GT 20 Pro and verify the ESSL3 external texture shader compiles on its GPU.
3. Verify app-window capture does not recurse into the overlay.
4. Measure capture input FPS, GPU render time, thermals and frame pacing.
5. Tune render scale and shader cost from actual device measurements before adding further effects.
