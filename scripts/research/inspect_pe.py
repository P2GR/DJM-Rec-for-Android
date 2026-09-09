"""Read-only PE inventory and targeted disassembly; never loads or executes the driver.

Install analysis-only dependencies: pip install pefile capstone
Usage: python inspect_pe.py driver.sys --output report.json
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

import pefile
from capstone import Cs, CS_ARCH_X86, CS_MODE_64, CS_MODE_32
from capstone.x86 import X86_OP_MEM, X86_OP_IMM, X86_REG_RIP


def inspect(path, rvas=()):
    data = path.read_bytes()
    pe = pefile.PE(data=data)
    base = pe.OPTIONAL_HEADER.ImageBase
    imports = {entry.address: f"{library.dll.decode()}.{entry.name.decode() if entry.name else entry.ordinal}"
               for library in getattr(pe, "DIRECTORY_ENTRY_IMPORT", []) for entry in library.imports}
    pattern = re.compile(r"track|title|artist|metadata|midi|vendor|control|urb|ioctl|pro.?dj|link|fader|recout|\.pdb", re.I)
    strings = []
    for encoding, regex in [("ascii", rb"[\x20-\x7e]{5,}"), ("utf-16le", rb"(?:[\x20-\x7e]\x00){5,}")]:
        for match in re.finditer(regex, data):
            value = match.group().decode(encoding)
            if pattern.search(value):
                try:
                    rva = pe.get_rva_from_offset(match.start())
                except pefile.PEFormatError:
                    rva = None
                strings.append({"offset": hex(match.start()), "va": hex(base + rva) if rva is not None else None,
                                "encoding": encoding, "text": value[:400]})
    wanted = {int(s["va"], 16): s["text"] for s in strings if s["va"]}
    disassembler = Cs(CS_ARCH_X86, CS_MODE_64 if pe.FILE_HEADER.Machine == 0x8664 else CS_MODE_32)
    disassembler.detail = True
    disassembler.skipdata = True
    references = []
    instructions = []
    for section in pe.sections:
        if not section.Characteristics & 0x20000000:
            continue
        for instruction in disassembler.disasm(section.get_data(), base + section.VirtualAddress):
            instructions.append(instruction)
            if instruction.id == 0:
                continue
            for operand in instruction.operands:
                if operand.type == X86_OP_IMM or (operand.type == X86_OP_MEM and operand.mem.base in (0, X86_REG_RIP)):
                    target = (operand.imm if operand.type == X86_OP_IMM else
                              instruction.address + instruction.size + operand.mem.disp if operand.mem.base == X86_REG_RIP else operand.mem.disp)
                    label = wanted.get(target) or imports.get(target)
                    if label and pattern.search(label):
                        references.append({"va": hex(instruction.address), "target": label})
    neighborhoods = []
    for ref in references:
        address = int(ref["va"], 16)
        start = next((i for i, ins in enumerate(instructions) if ins.address == address), None)
        if start is not None:
            neighborhoods.append({**ref, "instructions": [f"{ins.address:x}: {ins.mnemonic} {ins.op_str}"
                                  for ins in instructions[max(0, start - 12):start + 8]]})
    export_code = {}
    for symbol in getattr(getattr(pe, "DIRECTORY_ENTRY_EXPORT", None), "symbols", []):
        name = symbol.name.decode() if symbol.name else ""
        if name in {"PDJ_GetMixerInputSelectorStatus", "PDJ_GetMixerSeratoActiveStatus", "PDJ_GetPortInfo",
                    "PDJ_GetFirmwareVersion", "PDJ_GetCurrentUSBInputAudioIndex2", "PDJ_SetUSBInputAudioDirect"}:
            rows = []
            for ins in disassembler.disasm(pe.get_data(symbol.address, 768), base + symbol.address):
                rows.append(f"{ins.address:x}: {ins.mnemonic} {ins.op_str}")
                if ins.mnemonic == "ret" or len(rows) >= 100:
                    break
            export_code[name] = rows
    internal_code = {hex(rva): [f"{ins.address:x}: {ins.mnemonic} {ins.op_str}"
                     for ins in disassembler.disasm(pe.get_data(rva, 384), base + rva)] for rva in rvas}
    return {"file": path.name, "size": len(data), "sha256": hashlib.sha256(data).hexdigest(),
            "machine": hex(pe.FILE_HEADER.Machine), "image_base": hex(base),
            "imports": sorted(imports.values()),
            "exports": [e.name.decode() if e.name else str(e.ordinal)
                        for e in getattr(getattr(pe, "DIRECTORY_ENTRY_EXPORT", None), "symbols", [])],
            "matched_strings": strings, "references": neighborhoods, "selected_export_disassembly": export_code,
            "selected_internal_disassembly": internal_code,
            "limitations": "Static string/import cross-references are leads, not proof of device behavior. No binary was executed."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("binary", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--rva", type=lambda value: int(value, 0), action="append", default=[])
    args = parser.parse_args()
    report = inspect(args.binary, args.rva)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"{report['file']}: {report['size']} bytes; SHA-256 {report['sha256']}; "
          f"{len(report['matched_strings'])} strings, {len(report['references'])} references")
