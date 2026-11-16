"""Tests for tools/classfile.py and the two build checks built on it.

Run from the repository root:

    python3 -B -m unittest discover -s tools/tests

The class files are assembled here byte by byte, just enough of the format for
the reader: a constant pool, no fields, and methods with a Code attribute of a
chosen length. No compiler is needed.
"""
import contextlib
import io
import os
import struct
import sys
import tempfile
import unittest
from unittest import mock

sys.dont_write_bytecode = True
TOOLS = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir)
sys.path.insert(0, os.path.abspath(TOOLS))

import check_constructor_widths  # noqa: E402
import check_method_sizes  # noqa: E402
import classfile  # noqa: E402


def class_bytes(methods):
    """A class file with the given (name, descriptor, code length) methods."""
    pool = []  # utf8 strings; constant pool index = position + 1 (index 1.. for utf8)

    def utf8(text):
        if text not in pool:
            pool.append(text)
        return pool.index(text) + 1

    code_name = utf8("Code")
    encoded_methods = []
    for name, descriptor, code_length in methods:
        name_index = utf8(name)
        descriptor_index = utf8(descriptor)
        code = b"\x00" * code_length
        code_attribute = struct.pack(">HHI", 1, 1, code_length) + code + struct.pack(">HH", 0, 0)
        encoded_methods.append(
            struct.pack(">HHHH", 0x0001, name_index, descriptor_index, 1)
            + struct.pack(">HI", code_name, len(code_attribute))
            + code_attribute
        )
    this_class_name = utf8("Example")
    pool_bytes = b"".join(
        b"\x01" + struct.pack(">H", len(text.encode())) + text.encode() for text in pool
    )
    class_index = len(pool) + 1  # a Class entry after the utf8 strings
    pool_bytes += b"\x07" + struct.pack(">H", this_class_name)
    return (
        classfile.MAGIC
        + struct.pack(">HH", 0, 52)
        + struct.pack(">H", len(pool) + 2)  # count is entries + 1
        + pool_bytes
        + struct.pack(">HHH", 0x0021, class_index, 0)  # access, this_class, super_class
        + struct.pack(">H", 0)  # interfaces
        + struct.pack(">H", 0)  # fields
        + struct.pack(">H", len(encoded_methods))
        + b"".join(encoded_methods)
        + struct.pack(">H", 0)  # class attributes
    )


class ClassDir:
    """A temporary class directory; files are (relative path, bytes)."""

    def __init__(self, *files):
        self.files = files

    def __enter__(self):
        self.tmp = tempfile.TemporaryDirectory()
        for relative, data in self.files:
            path = os.path.join(self.tmp.name, relative)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "wb") as f:
                f.write(data)
        return self.tmp.name

    def __exit__(self, *exc):
        self.tmp.cleanup()


def run(main, roots):
    """Exit status and printed output of a check's main()."""
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        status = main(roots)
    return status, out.getvalue()


WIDE = "(" + "I" * 255 + ")V"


class ClassFileTest(unittest.TestCase):
    def test_reads_names_descriptors_and_code_lengths(self):
        data = class_bytes([("<init>", "(ILjava/lang/String;J)V", 7), ("run", "()V", 40)])
        self.assertEqual(
            classfile.methods(data),
            [("<init>", "(ILjava/lang/String;J)V", 7), ("run", "()V", 40)],
        )

    def test_rejects_a_file_that_is_not_a_class(self):
        with self.assertRaises(classfile.ClassFileError):
            classfile.methods(b"PK\x03\x04 a zip, not a class")

    def test_rejects_a_truncated_class(self):
        data = class_bytes([("run", "()V", 40)])
        with self.assertRaises(classfile.ClassFileError):
            classfile.methods(data[: len(data) // 2])

    def test_finds_classes_in_hidden_directories(self):
        with ClassDir((".hidden/A.class", class_bytes([])), ("b/B.class", class_bytes([]))) as root:
            found = [os.path.relpath(p, root) for p in classfile.class_files(root)]
        self.assertEqual(found, [os.path.join(".hidden", "A.class"), os.path.join("b", "B.class")])

    def test_missing_or_empty_directory_is_an_error(self):
        with ClassDir() as root:
            with self.assertRaises(classfile.ClassFileError):
                classfile.class_files(root)
            with self.assertRaises(classfile.ClassFileError):
                classfile.class_files(os.path.join(root, "absent"))


class ChecksTest(unittest.TestCase):
    """Both checks share run_check, so each failure mode is tried on both."""

    checks = (check_constructor_widths.main, check_method_sizes.main)

    def test_pass(self):
        ok = class_bytes([("<init>", "(II)V", 10), ("run", "()V", 100)])
        for main in self.checks:
            with ClassDir(("a/Ok.class", ok)) as root:
                status, _ = run(main, [root])
            self.assertEqual(status, 0, main.__module__)

    def test_constructor_too_wide_fails(self):
        with ClassDir(("a/Wide.class", class_bytes([("<init>", WIDE, 10)]))) as root:
            status, out = run(check_constructor_widths.main, [root])
        self.assertEqual(status, 1)
        self.assertIn("constructor takes 255 arguments", out)

    def test_constructor_at_the_limit_passes(self):
        at_limit = "(" + "I" * 254 + ")V"
        with ClassDir(("a/Edge.class", class_bytes([("<init>", at_limit, 10)]))) as root:
            status, _ = run(check_constructor_widths.main, [root])
        self.assertEqual(status, 0)

    def test_method_over_the_limit_fails(self):
        big = class_bytes([("compose", "()V", check_method_sizes.LIMIT + 1)])
        with ClassDir(("a/Big.class", big)) as root:
            status, out = run(check_method_sizes.main, [root])
        self.assertEqual(status, 1)
        self.assertIn("compose is %d bytes" % (check_method_sizes.LIMIT + 1), out)

    def test_method_at_the_limit_passes(self):
        edge = class_bytes([("compose", "()V", check_method_sizes.LIMIT)])
        with ClassDir(("a/Edge.class", edge)) as root:
            status, _ = run(check_method_sizes.main, [root])
        self.assertEqual(status, 0)

    def test_missing_directory_fails(self):
        for main in self.checks:
            with ClassDir() as root:
                status, out = run(main, [os.path.join(root, "absent")])
            self.assertEqual(status, 1, main.__module__)
            self.assertIn("no such directory", out)

    def test_empty_directory_fails(self):
        for main in self.checks:
            with ClassDir() as root:
                status, out = run(main, [root])
            self.assertEqual(status, 1, main.__module__)
            self.assertIn("no class files", out)

    def test_non_class_file_fails(self):
        for main in self.checks:
            with ClassDir(("a/Ok.class", class_bytes([])), ("a/Fake.class", b"not a class at all")) as root:
                status, out = run(main, [root])
            self.assertEqual(status, 1, main.__module__)
            self.assertIn("bad magic", out)

    def test_truncated_class_fails(self):
        data = class_bytes([("run", "()V", 40)])
        for main in self.checks:
            with ClassDir(("a/Cut.class", data[:30])) as root:
                status, out = run(main, [root])
            self.assertEqual(status, 1, main.__module__)
            self.assertIn("could not check", out)

    def test_no_arguments_fails(self):
        for main in self.checks:
            status, out = run(main, [])
            self.assertEqual(status, 2, main.__module__)
            self.assertIn("usage:", out)

    def test_one_bad_root_fails_even_when_another_passes(self):
        for main in self.checks:
            with ClassDir(("a/Ok.class", class_bytes([]))) as root:
                status, _ = run(main, [root, os.path.join(root, "absent")])
            self.assertEqual(status, 1, main.__module__)


class UnreadableDirectoryTest(unittest.TestCase):
    """A directory the walk cannot list fails the check, next to readable classes."""

    checks = (check_constructor_widths.main, check_method_sizes.main)

    def test_injected_listing_error_fails(self):
        real_scandir = os.scandir

        def scandir(path="."):
            if os.path.basename(str(path)) == "locked":
                raise PermissionError(13, "Permission denied", str(path))
            return real_scandir(path)

        files = (("a/Ok.class", class_bytes([])), ("locked/B.class", class_bytes([])))
        with ClassDir(*files) as root:
            with mock.patch("os.scandir", scandir):
                with self.assertRaises(classfile.ClassFileError):
                    classfile.class_files(root)
                for main in self.checks:
                    status, out = run(main, [root])
                    self.assertEqual(status, 1, main.__module__)
                    self.assertIn("cannot be listed", out)

    @unittest.skipIf(hasattr(os, "geteuid") and os.geteuid() == 0, "root can list any directory")
    def test_directory_without_permission_fails(self):
        files = (("a/Ok.class", class_bytes([])), ("locked/B.class", class_bytes([])))
        with ClassDir(*files) as root:
            locked = os.path.join(root, "locked")
            os.chmod(locked, 0)
            try:
                for main in self.checks:
                    status, out = run(main, [root])
                    self.assertEqual(status, 1, main.__module__)
                    self.assertIn("cannot be listed", out)
            finally:
                os.chmod(locked, 0o755)


if __name__ == "__main__":
    unittest.main()
