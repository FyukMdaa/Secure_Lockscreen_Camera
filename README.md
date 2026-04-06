# Secure Lockscreen Camera

Xposed Framework module to skip authentication on "Quickly open camera" gesture.

> **Fork of [droserasprout/lockscreencamera](https://github.com/droserasprout/lockscreencamera)**  
> Updated for modern Xposed API and MIUI Camera compatibility.

## Changes from original

* Migrated to [libxposed API 101](https://github.com/libxposed/api)
* Added support for MIUI Camera (`com.android.camera`) on custom ROMs
* Fixed `ActivityBase.checkKeyguard` being called after lockscreen hooks, which caused the camera to dismiss immediately
* Built with AI assistance (Claude and z.ai)

## Requirements

* Android 11+
* root
* LSPosed (Vector and other forks should work)
* MIUI Camera or GCam port

## Installation

1. Install the APK
2. Enable the module in LSPosed with scope: `com.android.camera` and `system`
3. Reboot

## How does it work

* Hooks `com.android.camera.Camera` (via parent class `ActivityBase`) to draw the activity over the lockscreen
* Suppresses `ActivityBase.checkKeyguard` and `checkKeyguardFlag` which MIUI uses to override lockscreen display flags

## Further reading

*(original links preserved from upstream)*

### General
* [Android API reference](https://developer.android.com/reference/)
* [libxposed API](https://github.com/libxposed/api)

### Projects
* [droserasprout/lockscreencamera](https://github.com/droserasprout/lockscreencamera) - original module
* [Xposed-Modules-Repo/nil.nadph.qnotified](https://github.com/Xposed-Modules-Repo/nil.nadph.qnoticed)
