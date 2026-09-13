"""Request bodies for the REST half, and the names the WebSocket half uses.

The WebSocket frames are hand-validated in [app.main] rather than modelled here:
they are small, they arrive on a hot path, and half of them are a single number.
What is worth pinning down is the identity a device presents when it joins,
which is the one place a bad or absent value has to be refused rather than
defaulted.
"""

from __future__ import annotations

from pydantic import BaseModel, Field, field_validator


class JoinRequest(BaseModel):
    """The identity a device presents in order to create or join a party.

    All three of these come from the app's signed-in account, and a request
    without them is refused — listening together is a named activity, and a
    party of "Listener", "Listener" and "Listener" is not the feature. See
    [app.party.Member] for what the server can and cannot prove about the claim.
    """

    user_id: str = Field(alias="userId", min_length=1, max_length=128)
    device_id: str = Field(alias="deviceId", min_length=1, max_length=128)
    display_name: str = Field(alias="displayName", min_length=1, max_length=80)
    avatar_url: str | None = Field(alias="avatarUrl", default=None, max_length=1000)

    model_config = {"populate_by_name": True}

    @field_validator("user_id", "device_id", "display_name")
    @classmethod
    def _not_blank(cls, value: str) -> str:
        cleaned = value.strip()
        if not cleaned:
            raise ValueError("must not be blank")
        return cleaned

    @field_validator("avatar_url")
    @classmethod
    def _http_only(cls, value: str | None) -> str | None:
        if value is None:
            return None
        cleaned = value.strip()
        if not cleaned:
            return None
        # The avatar is rebroadcast to every other device, which will then load
        # it. Anything but http(s) — `file:`, `content:`, a `data:` URI big
        # enough to be a payload — has no business making that trip.
        if not cleaned.startswith(("http://", "https://")):
            raise ValueError("must be an http(s) URL")
        return cleaned


# -- WebSocket frame types, server → client --------------------------------

WELCOME = "welcome"
STATE = "state"
QUEUE = "queue"
MEMBERS = "members"
PONG = "pong"
ERROR = "error"
BYE = "bye"

# -- WebSocket frame types, client → server --------------------------------

PING = "ping"
CONTROL = "control"
SYNC = "sync"
#: "my queue is stale, send it again". The self-healing half of splitting the
#: queue out of the state frame: a client that misses a [QUEUE] broadcast sees a
#: queueSeq it does not have on the very next heartbeat and asks.
SYNC_QUEUE = "syncQueue"
REPORT = "report"

# -- Control actions, any of which any member may send ---------------------

ACTION_PLAY = "play"
ACTION_PAUSE = "pause"
ACTION_SEEK = "seek"
ACTION_SET_TRACK = "setTrack"
ACTION_SET_QUEUE = "setQueue"
ACTION_NEXT = "next"
ACTION_PREVIOUS = "previous"
