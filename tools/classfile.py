"""The small part of the JVM class-file format the build checks read.

Shared by check_constructor_widths.py and check_method_sizes.py: the
constant pool (for names and descriptors) and, per method, its name, its
descriptor and the length of its Code attribute. Anything malformed raises
ClassFileError rather than being guessed past, so a check built on this can
refuse to pass a class it could not read.
"""
import glob
import os
import struct


class ClassFileError(Exception):
    """The bytes are not a class file this reader understands."""


MAGIC = b"\xca\xfe\xba\xbe"


def _pool_end_and_entries(data):
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


def constant_pool(data):
    """The constant pool's UTF-8 strings (and Class/String indices) by index."""
    return _pool_end_and_entries(data)[0]


def methods(data):
    """(name, descriptor, code length or None) for every method in the class."""
    try:
        pool, off = _pool_end_and_entries(data)
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
            result.append((pool[name_index], pool[descriptor_index], code_length))
        return result
    except (KeyError, IndexError, struct.error) as e:
        raise ClassFileError("truncated or malformed (%s)" % e)


def class_files(root):
    """Every .class file under root. Raises ClassFileError if there are none."""
    if not os.path.isdir(root):
        raise ClassFileError("%s: no such directory (was the compile task run?)" % root)
    paths = glob.glob(os.path.join(root, "**", "*.class"), recursive=True)
    if not paths:
        raise ClassFileError("%s: no class files in it (was the compile task run?)" % root)
    return paths
