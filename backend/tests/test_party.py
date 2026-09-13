"""The sync rule, tested as arithmetic rather than through a socket."""

import pytest

from app import config
from app.clock import now_ms
from app.party import Party, PartyError, PlaybackState, Track


@pytest.fixture
def instant_start(monkeypatch):
    """Removes the scheduled-start lead, so positions are readable in a test."""
    monkeypatch.setattr(config, "PLAY_LEAD_MS", 0)


def a_track(duration_ms: int | None = 240_000) -> Track:
    return Track(video_id="abc123", title="Song", artist="Artist", duration_ms=duration_ms)


def test_a_paused_state_does_not_move(instant_start):
    state = PlaybackState(track=a_track(), position_ms=30_000, anchor_ms=1_000, is_playing=False)
    assert state.position_at(1_000) == 30_000
    assert state.position_at(999_000) == 30_000


def test_a_playing_state_advances_with_the_server_clock(instant_start):
    state = PlaybackState(track=a_track(), position_ms=30_000, anchor_ms=1_000, is_playing=True)
    assert state.position_at(1_000) == 30_000
    assert state.position_at(6_000) == 35_000


def test_a_late_frame_still_lands_in_the_right_place(instant_start):
    """The whole point of anchoring: delivery delay does not become drift."""
    state = PlaybackState(track=a_track(), position_ms=30_000, anchor_ms=1_000, is_playing=True)
    # Two devices read the same frame 400ms apart and each asks where the party
    # is *now*; both answers describe the same instant on the same timeline.
    assert state.position_at(2_000) - state.position_at(1_600) == 400


def test_playback_holds_at_the_position_until_the_scheduled_start(monkeypatch):
    monkeypatch.setattr(config, "PLAY_LEAD_MS", 500)
    state = PlaybackState(track=a_track(), position_ms=10_000)
    state.play("m1")
    start = state.anchor_ms
    # Before the common start instant every device sits at the same position,
    # rather than the nearest one running ahead.
    assert state.position_at(start - 400) == 10_000
    assert state.position_at(start) == 10_000
    assert state.position_at(start + 250) == 10_250


def test_position_never_runs_past_the_track(instant_start):
    state = PlaybackState(track=a_track(duration_ms=5_000), position_ms=0, is_playing=True)
    assert state.position_at(state.anchor_ms + 60_000) == 5_000


def test_pause_takes_the_party_position_not_the_callers(instant_start):
    state = PlaybackState(track=a_track(), position_ms=0, is_playing=True)
    state.anchor_ms = now_ms() - 5_000

    state.pause("m1")

    assert not state.is_playing
    # Five seconds of server time have passed since the anchor, so that is where
    # the party is — not wherever the pausing device's own playhead had got to.
    assert 4_900 <= state.position_ms <= 5_200
    assert state.position_at(state.anchor_ms + 100_000) == state.position_ms


def test_pause_with_an_explicit_position_uses_it(instant_start):
    state = PlaybackState(track=a_track(), position_ms=0, is_playing=True)
    state.anchor_ms = now_ms() - 5_000
    state.pause("m1", position_ms=1_234)
    assert state.position_ms == 1_234


def test_seek_is_taken_as_given(instant_start):
    state = PlaybackState(track=a_track(), position_ms=0, is_playing=True)
    state.seek("m1", 90_000)
    assert state.position_at(state.anchor_ms) == 90_000


def test_every_mutation_bumps_the_sequence(instant_start):
    state = PlaybackState(track=a_track())
    seen = []
    for mutate in (
        lambda: state.play("m1"),
        lambda: state.pause("m1"),
        lambda: state.seek("m1", 1_000),
        lambda: state.set_track("m1", a_track()),
    ):
        mutate()
        seen.append(state.seq)
    assert seen == sorted(set(seen))
    assert state.updated_by == "m1"


def test_next_and_previous_walk_the_queue(instant_start):
    state = PlaybackState()
    queue = [Track(video_id=f"v{i}") for i in range(3)]
    state.set_queue("m1", queue, 0)
    assert state.step("m1", 1)
    assert state.track.video_id == "v1"
    assert state.step("m1", -1)
    assert state.track.video_id == "v0"
    assert not state.step("m1", -1)  # nothing before the first track


def test_a_party_fills_up_at_the_configured_ceiling():
    party = Party(code="ABC123")
    for i in range(config.MAX_MEMBERS):
        party.join(f"user{i}", f"device{i}", f"Listener {i}", None)
    with pytest.raises(PartyError) as caught:
        party.join("extra", "device-extra", "One Too Many", None)
    assert caught.value.status == 409
    assert caught.value.code == "party_full"


def test_rejoining_reclaims_a_slot_rather_than_taking_a_new_one():
    party = Party(code="ABC123")
    first = party.join("user1", "device1", "Kushagra", None)
    # Read now, because a rejoin mutates that membership in place rather than
    # handing back a second one — which is the thing being tested.
    first_id, first_token = first.member_id, first.token
    for i in range(config.MAX_MEMBERS - 1):
        party.join(f"user{i}", f"other{i}", f"Listener {i}", None)

    again = party.join("user1", "device1", "Kushagra", None)

    assert party.occupied_slots() == config.MAX_MEMBERS
    assert again.member_id == first_id
    assert again.is_host
    # A rejoin re-keys the membership, so a token left on a lost session is
    # not a second way in.
    assert again.token != first_token
    assert party.authenticate(again.token).member_id == first_id


def test_a_stale_token_is_refused():
    party = Party(code="ABC123")
    member = party.join("user1", "device1", "Kushagra", None)
    stale = member.token
    party.join("user1", "device1", "Kushagra", None)
    with pytest.raises(PartyError) as caught:
        party.authenticate(stale)
    assert caught.value.status == 401


def test_the_host_role_passes_on_rather_than_ending_the_party():
    party = Party(code="ABC123")
    host = party.join("user1", "device1", "Host", None)
    guest = party.join("user2", "device2", "Guest", None)
    party.remove(host.member_id)
    assert party.host is not None
    assert party.host.member_id == guest.member_id


def test_a_dropped_socket_is_not_a_departure():
    party = Party(code="ABC123")
    member = party.join("user1", "device1", "Host", None)
    party.mark_connected(member, True)
    party.mark_connected(member, False)
    just_after = member.last_seen_ms + config.DISCONNECT_GRACE_MS - 1
    assert party.expired_members(just_after) == []
    later = member.last_seen_ms + config.DISCONNECT_GRACE_MS + 1
    assert [m.member_id for m in party.expired_members(later)] == [member.member_id]


def test_the_control_budget_refuses_a_flood():
    party = Party(code="ABC123")
    member = party.join("user1", "device1", "Host", None)
    spent = sum(1 for _ in range(config.CONTROL_RATE_PER_SECOND * 3) if party.spend_control_budget(member))
    assert spent <= config.CONTROL_RATE_PER_SECOND + 1
