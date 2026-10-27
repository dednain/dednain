# 👋 Hi, I'm dednain

I build small, dependency-free AI systems that run anywhere — no GPU required.

## 🔥 Featured project

### [Gestalt-Net](https://github.com/dednain/gestalt-net)
> **Long memory for AI without the memory.**

A pure-Python retrospective memory architecture. It reads text in
**BEFORE → CURRENT → AFTER** triads, deletes closed scenes, keeps an eternal
**Dossier** of short tags, and writes ~22-byte **Sleep-Triggers** that restore
forgotten objects 500 pages later.

| | |
|---|---|
| 📉 Retained context | **−86.5%** (478,193 → 64,716 bytes) |
| 🗜️ Compression | **7.4×** |
| 🎯 Answer accuracy | **100%** on 200 unseen books |
| 🧠 Learned weights | **198**, ~1 s training on CPU |
| ✅ Verification | **27/27** independent checks |
| 📦 Dependencies | **0** — Python 3.8+ stdlib only |

🌐 [Landing page](https://dednain.github.io/gestalt-net/) · 📖 [README](https://github.com/dednain/gestalt-net#readme) · 🚀 [Release v1.0.0](https://github.com/dednain/gestalt-net/releases/tag/v1.0.0)

```bash
git clone https://github.com/dednain/gestalt-net.git
cd gestalt-net && python3 gestalt/verify.py
```

*Honest note: this is not a transformer, and we measured retained text bytes —
not VRAM. The corpus is synthetic. Full limitations in the README.*

---
MIT · Built with the Python standard library.
