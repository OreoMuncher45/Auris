"""Who is currently holding a socket, and how a frame reaches all of them.

Kept apart from [app.party] so the domain has no idea sockets exist: the party
decides *what* is true, this decides *who hears it*. One member has at most one
live socket — a second connection for the same membership replaces the first,
which is what makes a reconnect after a flaky handover land cleanly instead of
leaving a zombie receiving frames nobody reads.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from fastapi import WebSocket

log = logging.getLogger("jam.hub")


class Hub:
    def __init__(self) -> None:
        self._sockets: dict[str, dict[str, WebSocket]] = {}
        self._lock = asyncio.Lock()

    async def attach(self, code: str, member_id: str, socket: WebSocket) -> None:
        async with self._lock:
            room = self._sockets.setdefault(code, {})
            previous = room.get(member_id)
            room[member_id] = socket
        if previous is not None and previous is not socket:
            # Outside the lock: closing a socket can block on a peer that has
            # already gone away, and holding the hub shut while that times out
            # would stall every other party on the instance.
            await _close_quietly(previous)

    async def detach(self, code: str, member_id: str, socket: WebSocket) -> None:
        async with self._lock:
            room = self._sockets.get(code)
            if room is None:
                return
            # Only if it is still *this* socket. A reconnect that already
            # replaced it must not be torn down by the losing connection's own
            # cleanup running a moment later.
            if room.get(member_id) is socket:
                room.pop(member_id, None)
            if not room:
                self._sockets.pop(code, None)

    async def drop_party(self, code: str) -> None:
        async with self._lock:
            room = self._sockets.pop(code, None)
        for socket in (room or {}).values():
            await _close_quietly(socket)

    def members_online(self, code: str) -> set[str]:
        return set(self._sockets.get(code, {}).keys())

    def active_codes(self) -> set[str]:
        return set(self._sockets.keys())

    async def send(self, code: str, member_id: str, payload: dict[str, Any]) -> None:
        socket = self._sockets.get(code, {}).get(member_id)
        if socket is None:
            return
        await _send_quietly(socket, payload)

    async def broadcast(
        self,
        code: str,
        payload: dict[str, Any],
        *,
        skip: str | None = None,
    ) -> None:
        room = self._sockets.get(code)
        if not room:
            return
        targets = [(mid, sock) for mid, sock in room.items() if mid != skip]
        # Concurrently, so one member on a slow link does not delay the frame
        # for everyone else — which in this feature is not a latency nicety but
        # the thing being synchronised.
        await asyncio.gather(
            *(_send_quietly(sock, payload) for _, sock in targets),
            return_exceptions=True,
        )


async def _send_quietly(socket: WebSocket, payload: dict[str, Any]) -> None:
    try:
        await socket.send_json(payload)
    except Exception as exc:  # noqa: BLE001 — a dead peer is routine, not exceptional
        log.debug("dropped frame to a closed socket: %s", exc)


async def _close_quietly(socket: WebSocket) -> None:
    try:
        await socket.close()
    except Exception as exc:  # noqa: BLE001
        log.debug("close on an already-closed socket: %s", exc)
