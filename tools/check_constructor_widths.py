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
import sys

sys.dont_write_bytecode = True  # importing classfile must not leave a __pycache__ in tools/
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from classfile import ClassFileError, methods  # noqa: E402


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
                for name, descriptor, _ in methods(data):
                    if name != "<init>":
                        continue
                    words = argument_words(descriptor)
                    if words > limit:
                        bad.append((words, path))
            except ClassFileError:
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
