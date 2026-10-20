"""Inspect native ELF load alignment without extracting or modifying the bundle."""
import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path

bundle = Path(sys.argv[1])
rows = []
with zipfile.ZipFile(bundle) as archive:
    for name in archive.namelist():
        if not name.endswith('.so'):
            continue
        data = archive.read(name)
        if data[:4] != b'\x7fELF' or data[5] != 1:
            raise ValueError(f'Unsupported ELF: {name}')
        wide = data[4] == 2
        offset = struct.unpack_from('<Q' if wide else '<I', data, 32 if wide else 28)[0]
        size, count = struct.unpack_from('<HH', data, 54 if wide else 42)
        alignment = []
        relro = []
        for index in range(count):
            header = offset + index * size
            if struct.unpack_from('<I', data, header)[0] == 1:
                alignment.append(struct.unpack_from('<Q' if wide else '<I', data, header + (48 if wide else 28))[0])
            if struct.unpack_from('<I', data, header)[0] == 0x6474e552:
                address = struct.unpack_from('<Q' if wide else '<I', data, header + (16 if wide else 8))[0]
                memory = struct.unpack_from('<Q' if wide else '<I', data, header + (40 if wide else 20))[0]
                relro.append({'address': address, 'memory_size': memory, 'end_aligned_16kb': (address + memory) % 16384 == 0})
        rows.append({'library': name, 'load_alignment': alignment,
                     'elf_class': 64 if wide else 32, 'relro': relro,
                     'elf_load_alignment_16kb': bool(alignment) and all(value >= 16384 for value in alignment)})
report = {'bundle': bundle.name, 'bytes': bundle.stat().st_size,
          'sha256': hashlib.sha256(bundle.read_bytes()).hexdigest(), 'libraries': rows,
          'all_64bit_load_and_relro_aligned_16kb': all(row['elf_load_alignment_16kb'] and all(part['end_aligned_16kb'] for part in row['relro']) for row in rows if row['elf_class'] == 64),
          'note': 'ELF PT_LOAD and GNU_RELRO boundary inspection. Runtime tests are required separately. Android 16KB requirements apply to 64-bit native libraries.'}
print(json.dumps(report, indent=2))
