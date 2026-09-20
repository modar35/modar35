#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Мини-замена zipalign: собирает APK заново, выравнивая несжатые записи
(resources.arsc и т.п.) на 4 байта, и добавляет файлы (classes.dex).

Использование:
    python3 zipalign.py <входной.apk> <выходной.apk> [файл=путь_в_apk ...]
"""
import struct
import sys
import zipfile
import zlib

ALIGN = 4


def deflate(data):
    """Сырой deflate без zlib-обёртки — именно это ожидает формат zip."""
    compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
    return compressor.compress(data) + compressor.flush()


def align_extra(name_len, offset):
    """Extra-поле, сдвигающее данные на выровненное смещение.

    Структура extra: 4 байта заголовка (id, размер) + payload.
    Итоговая длина 4+need сравнима с need по модулю 4 — выравнивание сходится.
    """
    need = (ALIGN - ((offset + 30 + name_len) % ALIGN)) % ALIGN
    if need == 0:
        return b""
    return struct.pack("<HH", 0xD935, need) + b"\x00" * need


def repack(src, dst, additions):
    zin = zipfile.ZipFile(src, "r")
    infos = zin.infolist()

    entries = []  # (name, method, crc, comp, uncomp, data_bytes)
    for info in infos:
        if info.is_dir():
            continue
        raw = zin.read(info.filename)
        if info.compress_type == zipfile.ZIP_STORED:
            method = zipfile.ZIP_STORED
            comp = raw
        else:
            method = zipfile.ZIP_DEFLATED
            comp = deflate(raw)
        crc = zlib.crc32(raw) & 0xFFFFFFFF
        entries.append([info.filename, method, crc, comp, len(raw)])

    for pair in additions:
        target, path = pair.split("=", 1)
        with open(path, "rb") as fh:
            raw = fh.read()
        comp = deflate(raw)
        entries.append([target, zipfile.ZIP_DEFLATED, zlib.crc32(raw) & 0xFFFFFFFF, comp, len(raw)])

    # classes.dex должен идти сразу после манифеста/ресурсов — не критично, но стабильно
    entries.sort(key=lambda e: (0 if e[0] in ("AndroidManifest.xml", "resources.arsc") else 1, e[0]))

    out = open(dst, "wb")
    central = []
    for name, method, crc, comp, uncomp in entries:
        name_bytes = name.encode("utf-8")
        offset = out.tell()
        extra = b""
        if method == zipfile.ZIP_STORED:
            extra = align_extra(len(name_bytes), offset)
        header = struct.pack(
            "<IHHHHHIIIHH",
            0x04034B50, 20, 0, method, 0, 0x21, crc,
            len(comp), uncomp, len(name_bytes), len(extra),
        )
        out.write(header)
        out.write(name_bytes)
        out.write(extra)
        out.write(comp)
        central.append((name_bytes, method, crc, len(comp), uncomp, offset, extra))

    cd_offset = out.tell()
    for name_bytes, method, crc, csize, usize, offset, extra in central:
        out.write(struct.pack(
            "<IHHHHHHIIIHHHHHII",
            0x02014B50, 20, 20, 0, method, 0, 0x21, crc,
            csize, usize, len(name_bytes), len(extra), 0, 0, 0, 0, offset,
        ))
        out.write(name_bytes)
        out.write(extra)

    cd_size = out.tell() - cd_offset
    out.write(struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, len(central), len(central),
                          cd_size, cd_offset, 0))
    out.close()
    zin.close()

    # контроль: все несжатые записи должны начинаться с выровненного смещения
    check = zipfile.ZipFile(dst)
    bad = []
    for info in check.infolist():
        if info.compress_type == zipfile.ZIP_STORED:
            start = info.header_offset + 30 + len(info.filename.encode("utf-8")) + len(info.extra)
            if start % ALIGN != 0:
                bad.append(info.filename)
    check.close()
    if bad:
        raise SystemExit("Не удалось выровнять: %s" % ", ".join(bad))
    return len(central)


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    count = repack(sys.argv[1], sys.argv[2], sys.argv[3:])
    print("zipalign: готово, записей в APK: %d -> %s" % (count, sys.argv[2]))
