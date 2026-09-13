"""BitChord Listen Together — the party server.

The whole service in one sentence: a party is a six-character code, up to five
signed-in devices, and one playback state that any of them may change and all of
them re-derive their playhead from.

The REST half is the paperwork — mint a code, join one, leave. The WebSocket
half is the feature: it carries controls up, state down, and the clock samples
that let each device translate the server's timeline into its own. A client that
only ever polls `GET /api/parties/{code}` would still work and would still be in
sync, just coarsely; the socket is what makes a pause land on five phones at
once rather than within a second or so of each other.

Deliberately stateless-per-request and stateful-per-process: see
[app.party.PartyStore] for why that rules out running more than one instance.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
from contextlib import asynccontextmanager
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from . import codes, config, protocol
from .clock import now_ms
from .hub import Hub
from .party import Member, Party, PartyError, PartyStore, Track

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("jam")

store = PartyStore()
hub = Hub()


@asynccontextmanager
async def lifespan(app: FastAPI):
    ticker = asyncio.create_task(_heartbeat())
    try:
        yield
    finally:
        ticker.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await ticker


app = FastAPI(
    title="BitChord Listen Together",
    version="1.0.0",
    lifespan=lifespan,
)

if config.ALLOWED_ORIGINS:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=config.ALLOWED_ORIGINS,
        allow_credentials=False,
        allow_methods=["*"],
        allow_headers=["*"],
    )


@app.exception_handler(PartyError)
async def _party_error(_: Request, exc: PartyError) -> JSONResponse:
    return JSONResponse(status_code=exc.status, content={"error": exc.code, "message": exc.message})


# ---------------------------------------------------------------- REST ----


@app.get("/")
async def root() -> dict[str, Any]:
    return {
        "service": "bitchord-listen-together",
        "maxMembers": config.MAX_MEMBERS,
        "parties": len(store),
        "serverMs": now_ms(),
    }


@app.get("/healthz")
async def healthz() -> dict[str, Any]:
    return {"ok": True, "serverMs": now_ms()}


@app.get("/api/time")
async def server_time() -> dict[str, Any]:
    """One reading of the server clock, for a client that has no socket yet.

    The client wants the round trip as much as the number: with `t0` and `t1`
    taken either side of this call, the offset is `serverMs - (t0 + t1) / 2` and
    the error is bounded by half the round trip. Repeating it and keeping the
    sample with the smallest round trip is the same trick NTP uses, and is
    enough to put two phones on the same millisecond-ish timeline over mobile
    data. The socket's own ping/pong does exactly this, continuously.
    """
    return {"serverMs": now_ms()}


async def _authenticated(
    code: str,
    authorization: str | None = Header(default=None),
) -> tuple[Party, Member]:
    party = store.get(code)
    token = ""
    if authorization and authorization.lower().startswith("bearer "):
        token = authorization[7:].strip()
    if not token:
        raise PartyError(401, "no_token", "Missing party token.")
    return party, party.authenticate(token)


@app.post("/api/parties", status_code=201)
async def create_party(body: protocol.JoinRequest) -> dict[str, Any]:
    """Mint a code and put the caller in it as host."""
    party = store.create()
    member = party.join(body.user_id, body.device_id, body.display_name, body.avatar_url)
    log.info("party %s created by %s", party.code, member.display_name)
    return _membership_payload(party, member)


@app.post("/api/parties/{code}/join")
async def join_party(code: str, body: protocol.JoinRequest) -> dict[str, Any]:
    normalised = codes.normalise(code)
    if not codes.is_valid(normalised):
        raise PartyError(400, "bad_code", "A party code is six letters or digits.")
    party = store.get(normalised)
    member = party.join(body.user_id, body.device_id, body.display_name, body.avatar_url)
    log.info("party %s joined by %s (%d/%d)", party.code, member.display_name,
             party.occupied_slots(), config.MAX_MEMBERS)
    # Everyone already in the party learns about the arrival now, rather than at
    # the next heartbeat — the member list is the one part of this feature that
    # is visible before any music plays.
    await hub.broadcast(party.code, _members_frame(party))
    return _membership_payload(party, member)


@app.get("/api/parties/{code}")
async def read_party(auth: tuple[Party, Member] = Depends(_authenticated)) -> dict[str, Any]:
    party, member = auth
    member.last_seen_ms = now_ms()
    return party.to_wire()


@app.post("/api/parties/{code}/leave", status_code=200)
async def leave_party(auth: tuple[Party, Member] = Depends(_authenticated)) -> dict[str, Any]:
    party, member = auth
    party.remove(member.member_id)
    await hub.send(party.code, member.member_id, {"type": protocol.BYE, "reason": "left"})
    if not party.members:
        store.drop(party.code)
        await hub.drop_party(party.code)
    else:
        await hub.broadcast(party.code, _members_frame(party))
    return {"ok": True}


# ----------------------------------------------------------- WebSocket ----


@app.websocket("/ws/parties/{code}")
async def party_socket(socket: WebSocket, code: str, token: str = "") -> None:
    await socket.accept()

    party = store.find(code)
    if party is None:
        await _reject(socket, 4404, "no_such_party", "No party with that code.")
        return
    try:
        member = party.authenticate(token)
    except PartyError as exc:
        await _reject(socket, 4401, exc.code, exc.message)
        return

    await hub.attach(party.code, member.member_id, socket)
    party.mark_connected(member, True)
    await socket.send_json({
        "type": protocol.WELCOME,
        "you": member.to_wire(),
        "party": party.to_wire(),
        "serverMs": now_ms(),
    })
    await hub.broadcast(party.code, _members_frame(party), skip=member.member_id)

    try:
        while True:
            frame = await socket.receive_json()
            await _handle_frame(party, member, frame)
    except WebSocketDisconnect:
        pass
    except Exception as exc:  # noqa: BLE001 — a malformed frame kills one socket, not the party
        log.info("socket error in %s: %s", party.code, exc)
    finally:
        await hub.detach(party.code, member.member_id, socket)
        # Still a member, just not currently holding a socket: the grace period
        # in [Party.expired_members] is what turns this into an actual
        # departure, so a tunnel or a screen-off does not empty the room.
        party.mark_connected(member, False)
        await hub.broadcast(party.code, _members_frame(party))


async def _handle_frame(party: Party, member: Member, frame: Any) -> None:
    if not isinstance(frame, dict):
        return
    kind = frame.get("type")
    member.last_seen_ms = now_ms()

    if kind == protocol.PING:
        # Echoed back unread. The client's own send timestamp is what lets it
        # pair the reply with the request and halve the round trip; the server
        # has no use for it and no business interpreting it.
        await _reply(party, member, {
            "type": protocol.PONG,
            "clientMs": frame.get("clientMs"),
            "serverMs": now_ms(),
        })
        return

    if kind == protocol.SYNC:
        await _reply(party, member, _state_frame(party))
        return

    if kind == protocol.SYNC_QUEUE:
        await _reply(party, member, _queue_frame(party))
        return

    if kind == protocol.REPORT:
        # A device saying where it actually is. Nothing is done with it beyond
        # keeping the membership alive and making drift visible in the log —
        # the server's state is the truth, not an average of what devices
        # report, or a straggler on a bad connection would drag the party to it.
        reported = _as_int(frame.get("positionMs"))
        if reported is not None and party.playback.is_playing:
            drift = reported - party.playback.position_at(now_ms())
            if abs(drift) > 1500:
                log.info("party %s: %s drifted %dms", party.code, member.display_name, drift)
        return

    if kind == protocol.CONTROL:
        if not party.spend_control_budget(member):
            await _reply(party, member, {
                "type": protocol.ERROR,
                "error": "rate_limited",
                "message": "Too many controls at once.",
            })
            return
        queue_before = party.playback.queue_seq
        if _apply_control(party, member, frame):
            party.touch()
            # The queue first, so that nobody is holding a state frame that
            # points at an index in a list they have not been given yet.
            if party.playback.queue_seq != queue_before:
                await hub.broadcast(party.code, _queue_frame(party))
            # To everyone, the sender included. The device that pressed pause
            # re-anchors off the same frame as the rest, so nobody is running on
            # a locally predicted state that the server never confirmed.
            await hub.broadcast(party.code, _state_frame(party))
        else:
            await _reply(party, member, {
                "type": protocol.ERROR,
                "error": "bad_control",
                "message": f"Unsupported control: {frame.get('action')!r}",
            })


def _apply_control(party: Party, member: Member, frame: dict[str, Any]) -> bool:
    """Any member may send any of these. There is no host privilege here.

    "Anyone can control the music" is a product decision, and this function is
    all of its enforcement: the member is identified so the state can say who
    moved it, and then not consulted about whether they were allowed to.
    """
    action = frame.get("action")
    playback = party.playback
    who = member.member_id

    if action == protocol.ACTION_PLAY:
        playback.play(who, _as_int(frame.get("positionMs")))
        return True
    if action == protocol.ACTION_PAUSE:
        playback.pause(who, _as_int(frame.get("positionMs")))
        return True
    if action == protocol.ACTION_SEEK:
        position = _as_int(frame.get("positionMs"))
        if position is None:
            return False
        playback.seek(who, position)
        return True
    if action == protocol.ACTION_SET_TRACK:
        track = Track.from_wire(frame.get("track"))
        playback.set_track(
            who,
            track,
            position_ms=_as_int(frame.get("positionMs")) or 0,
            is_playing=bool(frame.get("isPlaying", True)),
            queue_index=_as_int(frame.get("queueIndex")),
        )
        return True
    if action == protocol.ACTION_SET_QUEUE:
        raw = frame.get("queue")
        if not isinstance(raw, list):
            return False
        queue = [track for track in (Track.from_wire(item) for item in raw) if track is not None]
        playback.set_queue(who, queue, _as_int(frame.get("queueIndex")) if frame.get("queueIndex") is not None else -1)
        return True
    if action == protocol.ACTION_NEXT:
        playback.step(who, 1)
        return True
    if action == protocol.ACTION_PREVIOUS:
        playback.step(who, -1)
        return True
    return False


# -------------------------------------------------------------- shared ----


def _membership_payload(party: Party, member: Member) -> dict[str, Any]:
    return {
        "code": party.code,
        # The one time this is ever sent. It is the device's key to the party
        # for as long as the membership lasts, so it lives in the client's own
        # storage and goes back as a bearer header or a socket query parameter.
        "token": member.token,
        "you": member.to_wire(),
        "party": party.to_wire(),
        "serverMs": now_ms(),
    }


def _state_frame(party: Party) -> dict[str, Any]:
    now = now_ms()
    return {"type": protocol.STATE, "playback": party.playback.to_wire(now), "serverMs": now}


def _queue_frame(party: Party) -> dict[str, Any]:
    """Sent when the queue changes, and when a client says its copy is stale.

    Never on the heartbeat — that is the whole point of it being its own frame.
    """
    return {
        "type": protocol.QUEUE,
        "queue": party.playback.queue_to_wire(),
        "serverMs": now_ms(),
    }


def _members_frame(party: Party) -> dict[str, Any]:
    return {
        "type": protocol.MEMBERS,
        "members": [m.to_wire() for m in sorted(party.members.values(), key=lambda m: m.joined_at_ms)],
        "maxMembers": config.MAX_MEMBERS,
        "serverMs": now_ms(),
    }


async def _reply(party: Party, member: Member, payload: dict[str, Any]) -> None:
    await hub.send(party.code, member.member_id, payload)


async def _reject(socket: WebSocket, close_code: int, error: str, message: str) -> None:
    with contextlib.suppress(Exception):
        await socket.send_json({"type": protocol.ERROR, "error": error, "message": message})
        await socket.close(code=close_code)


def _as_int(value: Any) -> int | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return int(value)


async def _heartbeat() -> None:
    """Re-state the truth on a timer, and sweep what has gone quiet.

    The unprompted re-broadcast is the backstop for everything the protocol
    cannot notice: a frame that never arrived, a device whose clock offset has
    wandered, a phone that came back from doze believing it is still where it
    was. None of those announce themselves, so correctness cannot depend on
    anybody asking — every few seconds each party is simply told again where it
    is, and each device re-derives its playhead from that.
    """
    interval = max(1.0, config.STATE_HEARTBEAT_MS / 1000.0)
    while True:
        await asyncio.sleep(interval)
        try:
            now = now_ms()
            for party in store.all():
                if hub.members_online(party.code):
                    await hub.broadcast(party.code, _state_frame(party))
            for party in store.sweep(now):
                await hub.broadcast(party.code, _members_frame(party))
            # Sockets whose party the sweep has just deleted. Left attached they
            # would sit open forever receiving nothing, which on a phone is a
            # radio kept awake for a party that no longer exists.
            for code in hub.active_codes() - {p.code for p in store.all()}:
                await hub.drop_party(code)
        except Exception as exc:  # noqa: BLE001 — the ticker must outlive any one bad pass
            log.warning("heartbeat pass failed: %s", exc)
