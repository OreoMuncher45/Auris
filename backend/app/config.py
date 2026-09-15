"""Deployment knobs, all overridable from the environment on Render."""

from __future__ import annotations

import os


def _int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or not raw.strip():
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def _csv(name: str, default: str) -> list[str]:
    raw = os.environ.get(name) or default
    return [item.strip() for item in raw.split(",") if item.strip()]


# The party ceiling, counted in devices rather than in people: the same account
# signed in on a phone and a tablet is two things that have to be fed audio, and
# that is what the limit is protecting.
MAX_MEMBERS = _int("JAM_MAX_MEMBERS", 5)

# How often the server re-states the truth to everyone, unprompted. Clients
# correct their own drift against these; nothing waits for a user action.
STATE_HEARTBEAT_MS = _int("JAM_STATE_HEARTBEAT_MS", 5_000)

# Playback resumes slightly in the future rather than immediately, so that every
# device has the same instant to aim at instead of each starting whenever its
# own packet happened to arrive. See PlaybackState.play.
PLAY_LEAD_MS = _int("JAM_PLAY_LEAD_MS", 350)

# A dropped connection is not a departure — phones lose WebSockets in tunnels,
# on screen-off, and on every network handover. The member keeps their slot for
# this long so a reconnect is invisible to the rest of the party.
DISCONNECT_GRACE_MS = _int("JAM_DISCONNECT_GRACE_MS", 45_000)

# A party with nobody in it, or one nobody has touched in a very long time, is
# swept so codes and memory come back.
EMPTY_PARTY_TTL_MS = _int("JAM_EMPTY_PARTY_TTL_MS", 120_000)
PARTY_MAX_AGE_MS = _int("JAM_PARTY_MAX_AGE_MS", 12 * 60 * 60 * 1000)

# Ceiling on control frames one member may send per second. A scrubbing finger
# is a legitimate burst, so this is set well above the UI's own throttle and
# exists only to stop a broken client shouting the party down.
CONTROL_RATE_PER_SECOND = _int("JAM_CONTROL_RATE_PER_SECOND", 25)

# Bounded so one member cannot hand the server an unbounded queue to hold and
# then rebroadcast to everyone else.
MAX_QUEUE_LENGTH = _int("JAM_MAX_QUEUE_LENGTH", 500)

# Browsers are not a client of this service today, so the default is closed;
# set JAM_ALLOWED_ORIGINS if a web player is ever pointed at it.
ALLOWED_ORIGINS = _csv("JAM_ALLOWED_ORIGINS", "")
