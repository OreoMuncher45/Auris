#!/usr/bin/env python3
"""Catch a method growing towards the JVM's 64 KB limit before it gets there.

The JVM caps a method's bytecode at 65,535 bytes, and the Kotlin compiler
reports the overflow as a bare MethodTooLargeException in the middle of a
build (OVERHAUL_PLAN.md records one in BitChordApp). A composable is one
method however long its source is, and every line added to it adds to that
one method, so the overflow arrives without warning on an ordinary change.

This check fails the build well before that, at 32,000 bytes per method,
the target set in issue #5. A method over it is the signal to split the
composable, not to raise the limit.

    python3 tools/check_method_sizes.py app/build/tmp/kotlin-classes/devRelease

The class-file walk is the one tools/check_constructor_widths.py uses; this
reads each method's Code attribute length as well.
"""
import glob
import os
import struct
import sys

sys.dont_write_bytecode = True  # importing the sibling must not leave a __pycache__ in tools/
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from check_constructor_widths import constant_pool  # noqa: E402

LIMIT = 32000


def method_code_sizes(data, pool):
    """(name, descriptor, code length) for every method that has a Code attribute."""
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
            attribute_name = struct.unpack_from(">H", data, off)[0]
            length = struct.unpack_from(">I", data, off + 2)[0]
            if pool.get(attribute_name) == "Code":
                # Code: max_stack (2), max_locals (2), code_length (4), code ...
                code_length = struct.unpack_from(">I", data, off + 6 + 4)[0]
                methods.append((pool[name_index], pool[descriptor_index], code_length))
            off += 6 + length
    return methods


def main(roots):
    bad = []
    for root in roots:
        for path in glob.glob(os.path.join(root, "**", "*.class"), recursive=True):
            data = open(path, "rb").read()
            if data[:4] != b"\xca\xfe\xba\xbe":
                continue
            try:
                pool = constant_pool(data)
                for name, _, size in method_code_sizes(data, pool):
                    if size > LIMIT:
                        bad.append((size, os.path.relpath(path, root), name))
            except (ValueError, KeyError, IndexError, struct.error):
                continue
    for size, path, name in sorted(bad, reverse=True):
        print("%s: %s is %d bytes of bytecode" % (path, name, size))
    if bad:
        print(
            "\n%d method(s) over %d bytes. The JVM refuses a method over "
            "65,535; split the composable (or function) before it gets "
            "there rather than raising this limit." % (len(bad), LIMIT)
        )
        return 1
    print("methods: all within %d bytes" % LIMIT)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:] or ["."]))
