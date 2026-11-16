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
import os
import sys

sys.dont_write_bytecode = True  # importing classfile must not leave a __pycache__ in tools/
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from classfile import run_check  # noqa: E402


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


LIMIT = 254


def inspect(root, path, methods):
    """A finding for every constructor wider than dex can call."""
    found = []
    for name, descriptor, _ in methods:
        if name == "<init>":
            words = argument_words(descriptor)
            if words > LIMIT:
                found.append((words, "%s: constructor takes %d arguments" % (path, words)))
    return found


def main(roots):
    return run_check(
        roots,
        inspect,
        ok_message="constructors: all within dex's argument limit",
        failure_hint=(
            "A call to a constructor with N arguments needs N+1 registers, and dex "
            "keeps that count in one byte (255). Split the captures: a lambda that "
            "holds more than 254 of them cannot be called."
        ),
        usage="usage: check_constructor_widths.py <classes-dir>...",
    )


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
