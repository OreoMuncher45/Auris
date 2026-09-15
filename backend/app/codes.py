"""Party codes: six characters, read aloud across a room without ambiguity.

The alphabet is alphanumeric minus `I`, `L` and `O` — the three letters that get
misheard as, or mistyped for, the digits `1` and `0`. Dropping the letter rather
than the digit is what makes the ambiguity resolvable instead of merely rarer:
with no `O` anywhere in the namespace, "oh" can only have meant zero, so a
mistyped code is corrected on arrival by [normalise] rather than bounced back at
whoever read it out.

That leaves 33 symbols, so a code is 33**6 ≈ 1.29 billion possibilities. The
collision check at the call site makes the birthday maths moot; the size is here
so that guessing your way into a stranger's party is not a thing that happens.

`secrets` rather than `random`, for the same reason: the code *is* the
invitation. A Mersenne Twister seeded from the clock is the wrong tool for
minting one, however unlikely the attack.
"""

from __future__ import annotations

import secrets

ALPHABET = "0123456789ABCDEFGHJKMNPQRSTUVWXYZ"
CODE_LENGTH = 6

_CONFUSIONS = str.maketrans({"I": "1", "L": "1", "O": "0"})


def new_code() -> str:
    return "".join(secrets.choice(ALPHABET) for _ in range(CODE_LENGTH))


def normalise(code: str) -> str:
    """A user-typed code in the canonical form parties are keyed by.

    Spaces and dashes people add for readability are dropped, case is folded up,
    and the three excluded letters are read as the digit they were meant to be.
    """
    cleaned = "".join(ch for ch in code.strip().upper() if ch.isalnum())
    return cleaned.translate(_CONFUSIONS)


def is_valid(code: str) -> bool:
    return len(code) == CODE_LENGTH and all(ch in ALPHABET for ch in code)
