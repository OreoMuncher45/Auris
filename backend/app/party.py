"""The domain: what a party is, who is in it, and what it is playing.

Nothing here touches HTTP or WebSockets — that is [app.main] and [app.hub]. The
point of the separation is that the sync rule, which is the whole feature, is
testable without a socket: a [PlaybackState] is a position, the server time that
position was true at, and whether the clock is running. Every device derives its
own playhead from those three numbers, so "in sync" is a property of arithmetic
rather than of message timing.

The wire format is camelCase throughout, which is not this language's
convention: the only consumer is the Android client, and matching Kotlin's own
naming there keeps `@SerialName` off every field of every model.
"""

from __future__ import annotations

import secrets
from dataclasses import dataclass, field
from typing import Any

from . import codes, config
from .clock import now_ms


class PartyError(Exception):
    """A request that is refused for a reason the caller should be told."""

    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


@dataclass(slots=True)
class Track:
    """A song, in the only terms every device needs to agree on.

    Deliberately not the app's own `Song`: the party carries what it takes to
    identify and to *show* a track, and nothing else. Everything else — stream
    URLs, the source that resolved them, quality ceilings, download state — is
    each device's own business, and has to stay that way. Two people in a party
    may be on different sources at different bitrates; what they share is which
    song is playing and where the playhead is.
    """

    video_id: str
    title: str = ""
    artist: str = ""
    thumbnail_url: str | None = None
    duration_ms: int | None = None

    @staticmethod
    def from_wire(raw: Any) -> "Track | None":
        if not isinstance(raw, dict):
            return None
        video_id = str(raw.get("videoId") or "").strip()
        if not video_id:
            return None
        duration = raw.get("durationMs")
        return Track(
            video_id=video_id[:128],
            title=str(raw.get("title") or "")[:300],
            artist=str(raw.get("artist") or "")[:300],
            thumbnail_url=(str(raw["thumbnailUrl"])[:1000] if raw.get("thumbnailUrl") else None),
            duration_ms=int(duration) if isinstance(duration, (int, float)) and duration > 0 else None,
        )

    def to_wire(self) -> dict[str, Any]:
        return {
            "videoId": self.video_id,
            "title": self.title,
            "artist": self.artist,
            "thumbnailUrl": self.thumbnail_url,
            "durationMs": self.duration_ms,
        }


@dataclass(slots=True)
class PlaybackState:
    """Where the party is in what it is playing, as of a server timestamp.

    [position_ms] is not "the current position" — it is the position at
    [anchor_ms], and it only becomes a current position once a device adds the
    time since. That indirection is what survives the network: a packet delayed
    by 300 ms carries an anchor 300 ms in the past and still lands the receiving
    device in exactly the right place.
    """

    track: Track | None = None
    queue: list[Track] = field(default_factory=list)
    queue_index: int = -1
    is_playing: bool = False
    position_ms: int = 0
    anchor_ms: int = field(default_factory=now_ms)
    #: Bumped on every mutation. Clients drop any state whose seq they have
    #: already passed, which is what makes two controllers pressing pause at the
    #: same moment settle rather than oscillate.
    seq: int = 0
    updated_by: str | None = None
    updated_at_ms: int = field(default_factory=now_ms)

    def position_at(self, server_ms: int) -> int:
        """The playhead this state implies at a given server time."""
        if not self.is_playing:
            return self.position_ms
        # Clamped at zero because an anchor is allowed to be in the future: a
        # resume schedules the start slightly ahead so that every device aims at
        # one instant rather than at its own arrival time. Before that instant
        # the party is holding at `position_ms`, which is exactly what a device
        # that has already buffered should be showing.
        elapsed = max(0, server_ms - self.anchor_ms)
        position = self.position_ms + elapsed
        duration = self.track.duration_ms if self.track else None
        if duration is not None:
            return min(position, duration)
        return position

    # -- mutations -------------------------------------------------------
    #
    # Each one re-anchors. That is the invariant the whole feature rests on:
    # `position_ms` is never left describing a moment that has passed.

    def _touch(self, member_id: str | None) -> None:
        self.seq += 1
        self.updated_by = member_id
        self.updated_at_ms = now_ms()

    def play(self, member_id: str | None, position_ms: int | None = None) -> None:
        now = now_ms()
        start_at = position_ms if position_ms is not None else self.position_at(now)
        self.position_ms = max(0, start_at)
        # The lead time is the difference between "everyone starts when their
        # packet lands" and "everyone starts together". Resuming at `now` means
        # the nearest device begins while the furthest is still reading the
        # frame, and each then corrects by seeking — audible as a stumble on
        # every play. Anchoring a few hundred milliseconds out gives every
        # device a common instant to aim at, and time to have buffered by it.
        self.is_playing = True
        self.anchor_ms = now + config.PLAY_LEAD_MS
        self._touch(member_id)

    def pause(self, member_id: str | None, position_ms: int | None = None) -> None:
        now = now_ms()
        # Where the *party* was, not where the pausing device's own playhead
        # happened to be. Taking the client's number would fold that one
        # device's drift into the state everybody else then corrects to.
        self.position_ms = max(0, position_ms if position_ms is not None else self.position_at(now))
        self.is_playing = False
        self.anchor_ms = now
        self._touch(member_id)

    def seek(self, member_id: str | None, position_ms: int) -> None:
        # A seek *is* the client's number — it is a user intent, not a
        # measurement — so unlike pause it is taken as given.
        self.position_ms = max(0, position_ms)
        self.anchor_ms = now_ms() + (config.PLAY_LEAD_MS if self.is_playing else 0)
        self._touch(member_id)

    def set_track(
        self,
        member_id: str | None,
        track: Track | None,
        position_ms: int = 0,
        is_playing: bool = True,
        queue_index: int | None = None,
    ) -> None:
        self.track = track
        self.position_ms = max(0, position_ms)
        self.is_playing = is_playing and track is not None
        self.anchor_ms = now_ms() + (config.PLAY_LEAD_MS if self.is_playing else 0)
        if queue_index is not None:
            self.queue_index = queue_index
        elif track is not None:
            match = next(
                (i for i, item in enumerate(self.queue) if item.video_id == track.video_id),
                -1,
            )
            self.queue_index = match
        self._touch(member_id)

    def set_queue(
        self,
        member_id: str | None,
        queue: list[Track],
        queue_index: int,
    ) -> None:
        self.queue = queue[: config.MAX_QUEUE_LENGTH]
        self.queue_index = queue_index if 0 <= queue_index < len(self.queue) else -1
        self._touch(member_id)

    def step(self, member_id: str | None, delta: int) -> bool:
        """Next/previous. False when the queue has nowhere to go."""
        target = self.queue_index + delta
        if not (0 <= target < len(self.queue)):
            return False
        self.set_track(member_id, self.queue[target], position_ms=0, queue_index=target)
        return True

    def to_wire(self, server_ms: int | None = None) -> dict[str, Any]:
        now = server_ms if server_ms is not None else now_ms()
        return {
            "seq": self.seq,
            "track": self.track.to_wire() if self.track else None,
            "queue": [item.to_wire() for item in self.queue],
            "queueIndex": self.queue_index,
            "isPlaying": self.is_playing,
            "positionMs": self.position_ms,
            "anchorMs": self.anchor_ms,
            # Redundant with the three fields above, and worth the bytes: it is
            # what a log, a test, or a human reading a frame needs in order to
            # tell "everyone is at 1:03" from "everyone agrees on the formula".
            "effectivePositionMs": self.position_at(now),
            "updatedBy": self.updated_by,
            "updatedAtMs": self.updated_at_ms,
        }


@dataclass(slots=True)
class Member:
    """One signed-in device in a party.

    Identity comes from the app's own account layer: [user_id] is derived from
    the signed-in Google/YouTube profile, and a request without one is refused —
    that is what "you have to be signed in to jam" means on this side of the
    wire. The server does not and cannot verify that claim (it holds no Google
    credential, and an Innertube round trip per join would be both slow and a
    second way to get rate-limited), so treat [user_id] as an assertion the
    client makes, not as proof. What *is* server-held is [token]: minted here,
    never guessable, and required on every subsequent call. So a member can lie
    about who they are, but cannot act as a member they are not.
    """

    member_id: str
    user_id: str
    device_id: str
    display_name: str
    avatar_url: str | None
    token: str
    is_host: bool = False
    joined_at_ms: int = field(default_factory=now_ms)
    last_seen_ms: int = field(default_factory=now_ms)
    connected: bool = False
    #: Token bucket for control frames. See [Party.spend_control_budget].
    control_budget: float = float(config.CONTROL_RATE_PER_SECOND)
    control_budget_at_ms: int = field(default_factory=now_ms)

    def to_wire(self) -> dict[str, Any]:
        return {
            "memberId": self.member_id,
            "userId": self.user_id,
            "displayName": self.display_name,
            "avatarUrl": self.avatar_url,
            "isHost": self.is_host,
            "connected": self.connected,
            "joinedAtMs": self.joined_at_ms,
            "lastSeenMs": self.last_seen_ms,
        }


@dataclass(slots=True)
class Party:
    code: str
    members: dict[str, Member] = field(default_factory=dict)
    playback: PlaybackState = field(default_factory=PlaybackState)
    created_at_ms: int = field(default_factory=now_ms)
    #: Last time anything happened — a join, a control, a heartbeat from a
    #: connected device. Drives the sweep, not [created_at_ms].
    touched_at_ms: int = field(default_factory=now_ms)
    #: When the last connected member dropped off, or None while someone is on.
    empty_since_ms: int | None = field(default_factory=now_ms)

    @property
    def host(self) -> Member | None:
        return next((m for m in self.members.values() if m.is_host), None)

    def occupied_slots(self) -> int:
        return len(self.members)

    def join(
        self,
        user_id: str,
        device_id: str,
        display_name: str,
        avatar_url: str | None,
    ) -> Member:
        """Admit a device, or hand back the slot it already holds.

        Rejoining is not a second membership. A reinstall, a force-stop, a lost
        WebSocket that the grace period outlived — all of them come back through
        this path, and a party of five would otherwise fill up with ghosts of
        the same five devices.
        """
        existing = next(
            (m for m in self.members.values() if m.device_id == device_id),
            None,
        )
        if existing is not None:
            existing.display_name = display_name
            existing.avatar_url = avatar_url
            existing.user_id = user_id
            existing.last_seen_ms = now_ms()
            # A fresh token: the old one may be on a device that lost the
            # session, and a membership should only ever have one live key.
            existing.token = secrets.token_urlsafe(24)
            self.touch()
            return existing

        if len(self.members) >= config.MAX_MEMBERS:
            raise PartyError(
                409,
                "party_full",
                f"This party is full ({config.MAX_MEMBERS} devices).",
            )

        member = Member(
            member_id=secrets.token_hex(8),
            user_id=user_id,
            device_id=device_id,
            display_name=display_name,
            avatar_url=avatar_url,
            token=secrets.token_urlsafe(24),
            is_host=not self.members,
        )
        self.members[member.member_id] = member
        self.touch()
        return member

    def authenticate(self, token: str) -> Member:
        member = next((m for m in self.members.values() if secrets.compare_digest(m.token, token)), None)
        if member is None:
            raise PartyError(401, "bad_token", "This device is not a member of that party.")
        return member

    def remove(self, member_id: str) -> Member | None:
        member = self.members.pop(member_id, None)
        if member is None:
            return None
        # The host leaving hands the party on rather than ending it: everyone
        # else is still listening, and the only thing the role carries is who
        # gets asked to leave last.
        if member.is_host:
            successor = next(iter(self.members.values()), None)
            if successor is not None:
                successor.is_host = True
        self.touch()
        self._refresh_emptiness()
        return member

    def spend_control_budget(self, member: Member) -> bool:
        """One token from the member's bucket; False when they have run dry."""
        now = now_ms()
        elapsed_s = max(0, now - member.control_budget_at_ms) / 1000.0
        member.control_budget = min(
            float(config.CONTROL_RATE_PER_SECOND),
            member.control_budget + elapsed_s * config.CONTROL_RATE_PER_SECOND,
        )
        member.control_budget_at_ms = now
        if member.control_budget < 1.0:
            return False
        member.control_budget -= 1.0
        return True

    def touch(self) -> None:
        self.touched_at_ms = now_ms()

    def mark_connected(self, member: Member, connected: bool) -> None:
        member.connected = connected
        member.last_seen_ms = now_ms()
        self.touch()
        self._refresh_emptiness()

    def _refresh_emptiness(self) -> None:
        if any(m.connected for m in self.members.values()):
            self.empty_since_ms = None
        elif self.empty_since_ms is None:
            self.empty_since_ms = now_ms()

    def expired_members(self, now: int) -> list[Member]:
        """Members whose disconnect grace has run out."""
        return [
            m
            for m in self.members.values()
            if not m.connected and now - m.last_seen_ms > config.DISCONNECT_GRACE_MS
        ]

    def is_expired(self, now: int) -> bool:
        if now - self.created_at_ms > config.PARTY_MAX_AGE_MS:
            return True
        # An empty party is not immediately a dead one, and a brand new party is
        # empty by definition — the code is minted first and the creator joins
        # against it. Both cases fall to the same grace below rather than to a
        # `not self.members` shortcut, which would let the sweeper delete a code
        # in the window between handing it out and the creator using it.
        if self.empty_since_ms is not None:
            return now - self.empty_since_ms > config.EMPTY_PARTY_TTL_MS
        return False

    def to_wire(self) -> dict[str, Any]:
        now = now_ms()
        return {
            "code": self.code,
            "createdAtMs": self.created_at_ms,
            "maxMembers": config.MAX_MEMBERS,
            "members": [m.to_wire() for m in sorted(self.members.values(), key=lambda m: m.joined_at_ms)],
            "playback": self.playback.to_wire(now),
            "serverMs": now,
        }


class PartyStore:
    """Every live party, in this process's memory.

    In memory on purpose, and the one thing to know before scaling this: a party
    lives entirely inside the instance that is holding its WebSockets, so two
    instances would be two disjoint sets of parties and a join would land in
    whichever one the load balancer picked. On Render that means a single
    instance with no autoscaling — which is the right shape for this anyway,
    since a party is at most five devices and the state is a few hundred bytes.
    Outgrowing that is a Redis pub/sub swap behind this class, not a rewrite of
    anything above it.
    """

    def __init__(self) -> None:
        self._parties: dict[str, Party] = {}

    def __len__(self) -> int:
        return len(self._parties)

    def create(self) -> Party:
        for _ in range(12):
            code = codes.new_code()
            if code not in self._parties:
                party = Party(code=code)
                self._parties[code] = party
                return party
        # 33**6 possibilities against a handful of live parties: twelve
        # collisions in a row is not luck, it is a broken RNG, and carrying on
        # would mean handing somebody a code into a stranger's party.
        raise PartyError(503, "code_exhausted", "Could not allocate a party code.")

    def get(self, code: str) -> Party:
        party = self._parties.get(codes.normalise(code))
        if party is None:
            raise PartyError(404, "no_such_party", "No party with that code.")
        return party

    def find(self, code: str) -> Party | None:
        return self._parties.get(codes.normalise(code))

    def drop(self, code: str) -> None:
        self._parties.pop(code, None)

    def all(self) -> list[Party]:
        return list(self._parties.values())

    def sweep(self, now: int) -> list[Party]:
        """Evict timed-out members and dead parties. Returns parties that changed."""
        changed: list[Party] = []
        for party in list(self._parties.values()):
            gone = party.expired_members(now)
            for member in gone:
                party.remove(member.member_id)
            if party.is_expired(now):
                self._parties.pop(party.code, None)
                continue
            if gone:
                changed.append(party)
        return changed
