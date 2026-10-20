"""Rebuild graphics-path 1.1.0 JNI with NDK r30, retaining its upstream Java/resources.

Use the pinned source archive and upstream Maven AAR described in NATIVE_16KB_BUILD.md.
Symbols are written by filename, never appended to an existing symbols ZIP.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

COMMIT = "1c44f0be720ff16cd30357ad2375239bf97a1da8"
TARGETS = {"arm64-v8a": "aarch64-linux-android26",
           "armeabi-v7a": "armv7a-linux-androideabi26",
           "x86_64": "x86_64-linux-android26", "x86": "i686-linux-android26"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="Extracted upstream cpp directory")
    parser.add_argument("--upstream-aar", type=Path, required=True)
    parser.add_argument("--llvm-bin", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--symbols-dir", type=Path, required=True)
    args = parser.parse_args()
    if args.upstream_aar.resolve() == args.output.resolve():
        parser.error("Keep the upstream AAR separate from the output")
    sources = sorted(args.source.glob("*.cpp"))
    version_script = args.source / "libandroidx.graphics.path.map"
    if not sources or not version_script.is_file():
        parser.error("The source directory must contain upstream cpp files and its version script")
    suffix = ".exe" if (args.llvm_bin / "clang++.exe").exists() else ""
    args.output.parent.mkdir(parents=True, exist_ok=True)
    built = set()
    with tempfile.TemporaryDirectory() as temporary:
        output = Path(temporary) / "graphics.so"
        with zipfile.ZipFile(args.upstream_aar) as original, zipfile.ZipFile(
            args.output, "w", zipfile.ZIP_DEFLATED
        ) as result:
            for member in original.infolist():
                data = original.read(member)
                if member.filename.endswith(".so"):
                    parts = Path(member.filename).parts
                    if len(parts) != 3 or parts[0] != "jni" or parts[2] != "libandroidx.graphics.path.so":
                        raise ValueError(f"Unexpected native entry: {member.filename}")
                    abi = parts[1]
                    command = [str(args.llvm_bin / ("clang++" + suffix)), "--target=" + TARGETS[abi],
                               "-shared", "-fPIC", "-O2", "-std=c++17", "-fvisibility=hidden",
                               "-fno-exceptions", "-fno-rtti", "-static-libstdc++",
                               "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
                               "-Wl,--build-id=sha1", "-Wl,--version-script=" + str(version_script.resolve()),
                               "-o", str(output)]
                    subprocess.run(command + [str(p.resolve()) for p in sources] +
                                   ["-landroid", "-llog", "-ldl"], check=True)
                    symbols = args.symbols_dir / abi / (parts[2] + ".sym")
                    symbols.parent.mkdir(parents=True, exist_ok=True)
                    subprocess.run([str(args.llvm_bin / ("llvm-objcopy" + suffix)),
                                    "--only-keep-debug", str(output), str(symbols)], check=True)
                    subprocess.run([str(args.llvm_bin / ("llvm-strip" + suffix)),
                                    "--strip-unneeded", str(output)], check=True)
                    data = output.read_bytes()
                    built.add(abi)
                result.writestr(member, data)
    if built != set(TARGETS):
        raise ValueError("Upstream AAR did not contain all four expected ABIs")
    print(json.dumps({"output": str(args.output), "source_commit": COMMIT,
                      "bytes": args.output.stat().st_size,
                      "sha256": hashlib.sha256(args.output.read_bytes()).hexdigest()}, indent=2))


if __name__ == "__main__":
    main()
