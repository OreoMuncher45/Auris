#!/usr/bin/env python3
"""Catch the dex argument-count overflow before it reaches an APK.

dex encodes a call as either 35c (up to five arguments, packed into
nibbles) or 3rc (a register window, with the number of words in one byte
of the instruction). The byte holds 255, so a call that needs 256 words
cannot be written down: the count wraps to zero and Android's verifier
reads that as "no arguments at all" and rejects the class outright.

    VerifyError: Verifier rejected class com.music.bitchord.MainActivityKt
    [0xFFFFFFFF] invalid arg count (0) in invoke-direct/range

The JVM allows a constructor 254 arguments long (255 slots with the
object itself), and the call to it needs 255 words, which is exactly
what dex can carry. One more argument and the call cannot be written at
all. Kotlin does not complain: the limit is dex's, and dex is not the
compiler.

So: every constructor in the compiled classes is counted, and anything
wider than 254 arguments fails the build.

    python3 tools/check_constructor_widths.py app/build/tmp/kotlin-classes/devRelease
"""
import glob
import os
import struct
import sys


def constant_pool(data):
    off = 8
    count = struct.unpack_from(">H", data, off)[0]
    off += 2
    pool = {}
    i = 1
    while i < count:
        tag = data[off]
        off += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, off)[0]
            off += 2
            pool[i] = data[off:off + length].decode("utf-8", "replace")
            off += length
        elif tag in (7, 8, 16, 19, 20):
            pool[i] = struct.unpack_from(">H", data, off)[0]
            off += 2
        elif tag == 15:
            off += 3
        elif tag in (17, 18):
            off += 4
        elif tag in (3, 4, 9, 10, 11, 12):
            off += 4
        elif tag in (5, 6):
            off += 8
            i += 1
        else:
            raise ValueError("unknown constant pool tag %d" % tag)
        i += 1
    return pool


def methods_of(data, pool):
    off = 8 + 2 + (len(pool) + 1) * 0  # the pool was parsed already; walk on
    # Reparse to find where the pool ended.
    count = struct.unpack_from(">H", data, 8)[0]
    off = 10
    i = 1
    while i < count:
        tag = data[off]
        off += 1
        if tag == 1:
            off += 2 + struct.unpack_from(">H", data, off)[0]
        elif tag in (7, 8, 16, 19, 20):
            off += 2
        elif tag == 15:
            off += 3
        elif tag in (17, 18, 3, 4, 9, 10, 11, 12):
            off += 4
        elif tag in (5, 6):
            off += 8
            i += 1
        i += 1

    off += 6  # access_flags, this_class, super_class
    interfaces = struct.unpack_from(">H", data, off)[0]
    off += 2 + 2 * interfaces
    fields = struct.unpack_from(">H", data, off)[0]
    off += 2
    for _ in range(fields):
        off += 6
        attributes = struct.unpack_from(">H", data, off)[0]
        off += 2
        for _ in range(attributes):
            off += 2
            length = struct.unpack_from(">I", data, off)[0]
            off += 4 + length

    methods = []
    count = struct.unpack_from(">H", data, off)[0]
    off += 2
    for _ in range(count):
        _, name_index, descriptor_index = struct.unpack_from(">HHH", data, off)
        off += 6
        attributes = struct.unpack_from(">H", data, off)[0]
        off += 2
        for _ in range(attributes):
            off += 2
            length = struct.unpack_from(">I", data, off)[0]
            off += 4 + length
        methods.append((pool[name_index], pool[descriptor_index]))
    return methods


def argument_words(descriptor):
    inside = descriptor[descriptor.index("(") + 1:descriptor.rindex(")")]
    words = 0
    i = 0
    while i < len(inside):
        char = inside[i]
        if char == "L":
            words += 1
            i = inside.index(";", i) + 1
        elif char == "[":
            i += 1
        elif char in "JD":
            words += 2
            i += 1
        else:
            words += 1
            i += 1
    return words


def main(roots):
    limit = 254
    bad = []
    for root in roots:
        for path in glob.glob(os.path.join(root, "**", "*.class"), recursive=True):
            data = open(path, "rb").read()
            if data[:4] != b"\xca\xfe\xba\xbe":
                continue
            try:
                pool = constant_pool(data)
                for name, descriptor in methods_of(data, pool):
                    if name != "<init>":
                        continue
                    words = argument_words(descriptor)
                    if words > limit:
                        bad.append((words, path))
            except (ValueError, KeyError, IndexError, struct.error):
                continue
    for words, path in sorted(bad, reverse=True):
        print("%s: constructor takes %d arguments" % (path, words))
    if bad:
        print(
            "\n%d constructor(s) wider than dex can call. A call to a "
            "constructor with N arguments needs N+1 registers, and dex "
            "keeps that count in one byte (255). Split the captures: a "
            "lambda that holds more than 254 of them cannot be called." % len(bad)
        )
        return 1
    print("constructors: all within dex's argument limit")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:] or ["."]))
