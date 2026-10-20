# Android 1.1.0 native build and verification

The shipped native AAR hashes, toolchain pins and exact submodule commits are in
`app/libs/libbox-provenance.json` and `app/libs/graphics-path-provenance.json`.
Do not substitute a newer upstream binary based only on its version: both the
tested newer libbox binary and upstream graphics-path 1.1.0 still had a GNU_RELRO
end boundary unsuitable for 16KB pages.

## Core

- Source: [Leadaxe/sing-box-lx v1.14.2-lx.11](https://github.com/Leadaxe/sing-box-lx/tree/v1.14.2-lx.11),
  including the four submodules pinned in the provenance JSON. A GitHub source
  archive alone does not include those submodule contents; use a recursive Git
  checkout or fetch the exact commits into their respective submodule folders.
- Go 1.26.8; `github.com/sagernet/gomobile/cmd/gomobile@v0.1.13` and
  `github.com/sagernet/gomobile/cmd/gobind@v0.1.13`.
- Official Android NDK r30, revision 30.0.16248370; Android API 26, four ABIs.
- Windows build script: `scripts/build-libbox-16kb.ps1`. It sets all required Go
  tags and linker flags, including `-R 16384`, `max-page-size=16384` and
  `common-page-size=16384`. It does not edit ELF headers after linking.
- Build tags retain the app's V2Ray, XHTTP, QUIC, uTLS, gVisor and OpenConnect
  support. Unused Naive/Tailscale/WireGuard/OpenVPN build tags are omitted.

Set task-specific paths for a tools directory containing `go/bin/`,
`android-ndk-r30/`, `gopath/`, `gomodcache/` and `gocache/`. Install gomobile and
gobind into that GOPATH with `GOTOOLCHAIN=go1.26.8`. Then run:

```powershell
.\scripts\build-libbox-16kb.ps1 -CoreSource $coreSource -NativeTools $nativeTools -AndroidSdk $androidSdk -JavaHome $javaPath
$llvmBin = Join-Path $nativeTools 'android-ndk-r30/toolchains/llvm/prebuilt/windows-x86_64/bin'
python .\scripts\package-native-aar.py --input "$coreSource/libbox-securevpn.aar" --output .\app\libs\libbox.aar --llvm-bin "$llvmBin" --symbols-dir "$symbolsDir"
```

CGO parses CC paths itself. On Windows a path containing spaces needs the short
path workaround already in the PowerShell script, or a tools directory without
spaces. Keep the full toolchain outside version control. The current local cache
is the root project's ignored `scratch/native-audit/toolchain/` directory.

## AndroidX graphics-path

Keep the Java/resources from the official Maven
`androidx.graphics:graphics-path:1.1.0` AAR. Extract the unchanged native source
from [AndroidX commit 1c44f0be720ff16cd30357ad2375239bf97a1da8](https://android.googlesource.com/platform/frameworks/support/+/1c44f0be720ff16cd30357ad2375239bf97a1da8/graphics/graphics-path/src/main/cpp).
The Gitiles archive URL is that same path under `+archive/`, ending in `.tar.gz`.
The archive used for this release had SHA256
`942b3465c483221bed57e41a1c772b64d6bd7be82b8a7991c396c3dc90f7fe5d`.

```powershell
python .\scripts\build-graphics-16kb.py --source "$graphicsSource" --upstream-aar "$upstreamGraphicsAar" --llvm-bin "$llvmBin" --output .\app\libs\graphics-path-1.1.0-securevpn.aar --symbols-dir "$symbolsDir"
```

The script rebuilds only JNI with NDK r30, static C++ runtime, the upstream
version script and both 16KB page linker flags. Gradle excludes the upstream
graphics-path module to prevent packaging duplicate JNI files. It uses the local
AAR instead. Update the provenance hash only after verifying a rebuilt artifact.

## Symbols and release checks

The two scripts write symbols into `ABI/library.so.sym` under the same symbols
directory. Create a **new** ZIP from its contents, with ABI directories at the ZIP
root, for the exact build being uploaded to Play. Do not append entries to an
old symbols ZIP. These are the available native symbol tables; this build omits
full DWARF line information and Conscrypt's upstream binary does not supply its
own full symbols. Keep symbols and R8 mapping private and tied to the release.

```powershell
python .\scripts\audit-bundle-native.py .\app\build\outputs\bundle\release\app-release.aab
python .\scripts\audit-bundle-native.py .\app\build\outputs\apk\release\app-release.apk
& "$androidSdk/build-tools/36.0.0/zipalign.exe" -c -P 16 4 .\app\build\outputs\apk\release\app-release.apk
& "$androidSdk/build-tools/36.0.0/apksigner.bat" verify .\app\build\outputs\apk\release\app-release.apk
```

The audit checks all packaged ELF PT_LOAD alignments and GNU_RELRO end addresses.
The 64-bit result must be true. `useLegacyPackaging=true` intentionally retains
compressed JNI libraries and installation-time extraction; switching to
uncompressed universal packaging made the APK much larger. ELF alignment is
still required for the extracted libraries.

Follow [Android's 16KB documentation](https://developer.android.com/guide/practices/page-sizes),
including a real `getconf PAGE_SIZE` = 16384 runtime test. This release passed
native runtime tests on an x86_64 16KB emulator, plus static inspection of all four
ABIs. ARM64 physical-device behavior and Play's final uploaded-bundle analysis
remain separate checks.

The legacy `libopenconnect.so` and `libstoken.so` are intentionally removed.
OpenConnect's native mode uses the rebuilt core endpoint; Java fallback remains.
Do not put those obsolete 4KB libraries back into jniLibs.
