🎉 **Release: Vector 2.0** 🎉

Welcome to Vector 2.0! As part of our ongoing transition, the project has officially been renamed from `LSPosed` to `Vector`. While our major internal refactoring is still underway, we are releasing 2.0 now to provide a stable, feature-complete environment for those relying on legacy libxposed APIs.

### 📚 libxposed API 100 & 101
With the recent publication of libxposed API 101, the ecosystem is moving toward a new standard with significant breaking changes. Because API 100 was never officially published, **Vector 2.0 serves as the definitive implementation of the API 100 era**, built from the exact commit prior to the API 101 jump.

### 🏗️ Architecture & API Updates
*   **Vector & Zygisk Overhaul:** Officially renamed and modularized the project, featuring a completely rewritten, modern Zygisk architecture.
*   **API 100 Finalization:** Completed all remaining libxposed API 100 features, including comprehensive support for static initializers, constructor hooking, and centralized logging.


### ⚙️ Core Engine & System Enhancements
*   🔓 **Bypassed Bionic `LD_PRELOAD` Restrictions:** Resolved fatal namespace errors on Android 10 by loading the `dex2oat` hook library via a `memfd_create` tmpfs-backed file descriptor, bypassing the linker's namespace checks.
*   🛡️ **Reflection Parity Overhaul:** Completely rebuilt the `invokeSpecialMethod` backend to improve performance, enhance robustness, and mirror standard Java reflection behavior.
*   ⏱️ **Late Injection Standalone Launch:** Added native support for manual late injection (triggered by NeoZygisk), without relying on Magisk's early-init phase—highly useful for AOSP debug builds.
