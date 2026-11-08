# GT SuperRes AI v2

**Not NVIDIA DLSS 5. Not system-wide frame replacement. No root required.**

This branch builds an experimental Android application for Infinix GT 20 Pro with **real, pretrained Compressed ESRGAN neural inference** via TensorFlow Lite, using a public compressed model from [captain-pool/GSOC E3 distilled ESRGAN](https://github.com/captain-pool/GSOC/blob/master/E3_Distill_ESRGAN/README.md).

- Model: 4x enhancement, float32 input 180 x 320 x 3, output 720 x 1280 x 3; 33,768 bytes downloaded at build time and embedded into the APK.
- Photo mode: choose a local screenshot or picture, run the model on-device, save the output to Pictures/GT-SuperRes.
- Experimental screen mode: MediaProjection capture; perform actual inference then draw results as a secure, touch-pass-through overlay; permissions explicitly requested. Not smooth gameplay, not guaranteed in all games. Display capture does not grant access to game engine depth buffers, temporal motion vectors or real HDR10 output.
- FLAG_SECURE on our overlay is designed to prevent MediaProjection from copying it back into the next captured frame. Actual phone/OEM behavior must be tested.
- Android 14+: new user capture consent each session; protected/DRM content is never bypassed.
- No Internet permission; images stay local.
- Old recursive OpenGL filter was removed.

### APK build
GitHub Actions workflow Build GT SuperRes AI APK compiles app:assembleDebug and uploads artifact GT-SuperRes-AI-APK. After downloading the artifact zip, extract app-debug.apk and install it on your Android device. A debug build is not Play Store signed.

### Limitations
- Original display resolution might be higher than the model's native output: this version can look worse than the original at 1080p+.
- CPU inference on a 33 KB compressed model is not free; expect substantial latency, not 60/120/144 FPS.
- Secure overlay and screen sharing interactions vary by Android implementation; black frames, delayed visual updates or unavailable video/DRM capture are possible.
- Device testing required before relying on the live mode.
- Model may invent details; this is learned super-resolution, not true new 3D geometry or lighting.
