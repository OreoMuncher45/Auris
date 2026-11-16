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

The class-file walk is tools/classfile.py, shared with
check_constructor_widths.py.
"""
import os
import sys

sys.dont_write_bytecode = True  # importing classfile must not leave a __pycache__ in tools/
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from classfile import run_check  # noqa: E402

LIMIT = 32000


def inspect(root, path, methods):
    """A finding for every method whose bytecode is over LIMIT bytes."""
    found = []
    for name, _, size in methods:
        if size is not None and size > LIMIT:
            found.append((size, "%s: %s is %d bytes of bytecode" % (os.path.relpath(path, root), name, size)))
    return found


def main(roots):
    return run_check(
        roots,
        inspect,
        ok_message="methods: all within %d bytes" % LIMIT,
        failure_hint=(
            "The JVM refuses a method over 65,535 bytes; split the composable "
            "(or function) before it gets there rather than raising this limit."
        ),
        usage="usage: check_method_sizes.py <classes-dir>...",
    )


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
