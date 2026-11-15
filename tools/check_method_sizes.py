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
from classfile import ClassFileError, class_files, methods  # noqa: E402

LIMIT = 32000


def main(roots):
    bad = []
    unreadable = []
    for root in roots:
        # Fails rather than passes when there is nothing to check: see
        # check_constructor_widths.py.
        try:
            paths = class_files(root)
        except ClassFileError as e:
            unreadable.append(str(e))
            continue
        for path in paths:
            try:
                for name, _, size in methods(open(path, "rb").read()):
                    if size is not None and size > LIMIT:
                        bad.append((size, os.path.relpath(path, root), name))
            except ClassFileError as e:
                unreadable.append("%s: %s" % (path, e))
    for message in unreadable:
        print("could not check %s" % message)
    if unreadable and not bad:
        print("\n%d unreadable input(s): failing rather than passing unchecked." % len(unreadable))
        return 1
    for size, path, name in sorted(bad, reverse=True):
        print("%s: %s is %d bytes of bytecode" % (path, name, size))
    if bad:
        print(
            "\n%d method(s) over %d bytes. The JVM refuses a method over "
            "65,535; split the composable (or function) before it gets "
            "there rather than raising this limit." % (len(bad), LIMIT)
        )
        return 1
    if unreadable:
        return 1
    print("methods: all within %d bytes" % LIMIT)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:] or ["."]))
