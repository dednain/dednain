# GT Real Graphics Engine — development status

Project branch: gt-real-graphics-engine

Current alpha contains:
- Android 14+ single-app MediaProjection consent flow.
- A non-touchable overlay window.
- OpenGL ES 3.0 shader-based GPU image processing.
- RGBA frame acquisition, buffer reuse, and persistent texture updates.
- Adjustable saturation, contrast, exposure, bloom-like glow, sharpening and shadow-detail lift.
- Stop notification and on-screen controls.

Not implemented: Vulkan backend, neural depth estimation, physically correct shadows/reflections, geometry/material reconstruction, true HDR10, frame generation or a 144 FPS guarantee.

The first priority is a clean CI build and a real device test of capture stability and measured frame rate. Later performance work should replace the current Java frame copy with a GPU-native HardwareBuffer path, then profile GPU time before adding heavier effects.
