# GT SuperRes (Android)

This branch contains a standalone Android screen-filter application targeting Infinix GT 20 Pro and Android 10+.

## Build

GitHub Actions workflow **Build GT SuperRes APK** creates a debug-signed `app-debug.apk` and uploads it as the `GT-SuperRes-APK` artifact. Debug APK can be installed on Android after approving installation from the file manager.

## How it works

After Android permission prompts, the foreground service uses MediaProjection to capture the display, OpenGL ES 2.0 to apply edge sharpening, saturation and contrast, then renders an overlay over the screen. Screen-protected content may not be capturable. Android can include the overlay in its capture and generate recursive visual feedback. Stop via the persistent notification if this happens.

**This is not NVIDIA DLSS 5, machine learning super-resolution, frame generation, or a system-wide GPU driver hook. It does not increase in-game FPS or replace game rendering.** Screen filtering may reduce FPS or increase latency, and results on Infinix GT 20 Pro have not been tested on device.

No internet permission and no upload of screen frames. Root not required. Android requires capture consent on every session.
