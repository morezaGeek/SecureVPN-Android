"""Strip an Android native AAR, preserving matching symbols outside the shipped AAR."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--llvm-bin", type=Path, required=True)
    parser.add_argument("--symbols-dir", type=Path, required=True)
    args = parser.parse_args()
    if args.input.resolve() == args.output.resolve():
        parser.error("Input and output must be different files")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    suffix = ".exe" if (args.llvm_bin / "llvm-strip.exe").exists() else ""
    libraries = []
    with tempfile.TemporaryDirectory() as temporary:
        work = Path(temporary)
        with zipfile.ZipFile(args.input) as source, zipfile.ZipFile(
            args.output, "w", zipfile.ZIP_DEFLATED
        ) as target:
            for member in source.infolist():
                data = source.read(member)
                if member.filename.endswith(".so"):
                    parts = Path(member.filename).parts
                    if len(parts) != 3 or parts[0] != "jni":
                        raise ValueError(f"Unexpected native entry: {member.filename}")
                    abi, name = parts[1:]
                    symbols = args.symbols_dir / abi / (name + ".sym")
                    symbols.parent.mkdir(parents=True, exist_ok=True)
                    raw, stripped = work / "raw.so", work / "stripped.so"
                    raw.write_bytes(data)
                    subprocess.run([str(args.llvm_bin / ("llvm-objcopy" + suffix)),
                                    "--only-keep-debug", str(raw), str(symbols)], check=True)
                    subprocess.run([str(args.llvm_bin / ("llvm-strip" + suffix)),
                                    "--strip-unneeded", "-o", str(stripped), str(raw)], check=True)
                    data = stripped.read_bytes()
                    libraries.append(member.filename)
                target.writestr(member, data)
    print(json.dumps({"output": str(args.output), "libraries": libraries,
                      "bytes": args.output.stat().st_size,
                      "sha256": hashlib.sha256(args.output.read_bytes()).hexdigest()}, indent=2))


if __name__ == "__main__":
    main()
