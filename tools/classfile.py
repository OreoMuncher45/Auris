"""The small part of the JVM class-file format the build checks read, and the
loop they share.

Used by check_constructor_widths.py and check_method_sizes.py: per method, its
name, its descriptor and the length of its Code attribute; and run_check(),
which walks the class directories it is given, hands each class to the check,
and decides the exit status. Anything it could not read fails the check rather
than being skipped: a check that cannot see a class cannot vouch for it.

Tests: python3 -B -m unittest discover -s tools/tests
"""
import os
import struct


class ClassFileError(Exception):
    """The bytes are not a class file this reader understands."""


MAGIC = b"\xca\xfe\xba\xbe"


def _constant_pool(data):
    """Walk the constant pool once: its UTF-8 entries by index, and where it ends."""
    if data[:4] != MAGIC:
        raise ClassFileError("not a class file (bad magic)")
    count = struct.unpack_from(">H", data, 8)[0]
    off = 10
    pool = {}
    i = 1
    while i < count:
        tag = data[off]
        off += 1
        if tag == 1:  # Utf8
            length = struct.unpack_from(">H", data, off)[0]
            off += 2
            pool[i] = data[off:off + length].decode("utf-8", "replace")
            off += length
        elif tag in (7, 8, 16, 19, 20):  # Class, String, MethodType, Module, Package
            pool[i] = struct.unpack_from(">H", data, off)[0]
            off += 2
        elif tag == 15:  # MethodHandle
            off += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):  # Integer, Float, refs, NameAndType, (Invoke)Dynamic
            off += 4
        elif tag in (5, 6):  # Long, Double take two slots
            off += 8
            i += 1
        else:
            raise ClassFileError("unknown constant pool tag %d" % tag)
        i += 1
    return pool, off


def methods(data):
    """(name, descriptor, code length or None) for every method in the class."""
    try:
        pool, off = _constant_pool(data)
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

        result = []
        count = struct.unpack_from(">H", data, off)[0]
        off += 2
        for _ in range(count):
            _, name_index, descriptor_index = struct.unpack_from(">HHH", data, off)
            off += 6
            attributes = struct.unpack_from(">H", data, off)[0]
            off += 2
            code_length = None
            for _ in range(attributes):
                attribute_name = struct.unpack_from(">H", data, off)[0]
                length = struct.unpack_from(">I", data, off + 2)[0]
                if pool.get(attribute_name) == "Code":
                    # Code: max_stack (2), max_locals (2), code_length (4), code ...
                    code_length = struct.unpack_from(">I", data, off + 6 + 4)[0]
                off += 6 + length
            if off > len(data):
                raise ClassFileError("truncated (attribute runs past the end)")
            result.append((pool[name_index], pool[descriptor_index], code_length))
        return result
    except (KeyError, IndexError, struct.error) as e:
        raise ClassFileError("truncated or malformed (%s)" % e)


def _unreadable_directory(error):
    raise ClassFileError("%s: cannot be listed (%s)" % (error.filename, error.strerror or error))


def class_files(root):
    """Every .class file under root, hidden directories included, in a stable order.

    Raises ClassFileError if root is missing or holds no class files (the
    compile did not run, which is not the same as every class being fine),
    or if any directory under it cannot be listed.
    """
    if not os.path.isdir(root):
        raise ClassFileError("%s: no such directory (was the compile task run?)" % root)
    paths = []
    # os.walk skips a directory it cannot list unless told otherwise, which
    # would leave its classes unchecked while the check passed.
    for directory, _, files in os.walk(root, onerror=_unreadable_directory):
        paths.extend(os.path.join(directory, name) for name in files if name.endswith(".class"))
    if not paths:
        raise ClassFileError("%s: no class files in it (was the compile task run?)" % root)
    return sorted(paths)


def run_check(roots, inspect, ok_message, failure_hint, usage, notes=None):
    """Run a check over every class under every root and return the exit status.

    inspect(root, path, methods) returns a list of (sort_key, message) findings
    for one class. notes, if given, is called once at the end and returns extra
    lines to print (they do not change the status).

    0: every class read and nothing found. 1: a finding, or something could
    not be read. 2: no class directory given at all.
    """
    if not roots:
        print(usage)
        return 2
    findings = []
    unreadable = []
    for root in roots:
        try:
            paths = class_files(root)
        except ClassFileError as e:
            unreadable.append(str(e))
            continue
        for path in paths:
            try:
                with open(path, "rb") as f:
                    found = methods(f.read())
            except ClassFileError as e:
                unreadable.append("%s: %s" % (path, e))
                continue
            findings.extend(inspect(root, path, found))
    for message in unreadable:
        print("could not check %s" % message)
    for _, message in sorted(findings, reverse=True):
        print(message)
    for line in (notes() if notes else []):
        print(line)
    if findings:
        print("\n%d finding(s). %s" % (len(findings), failure_hint))
    if unreadable:
        print("\n%d unreadable input(s): failing rather than passing unchecked." % len(unreadable))
    if findings or unreadable:
        return 1
    print(ok_message)
    return 0

