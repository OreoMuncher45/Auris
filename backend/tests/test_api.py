"""The protocol as a client actually meets it: HTTP in, frames out."""

import pytest
from fastapi.testclient import TestClient

from app import config, main
from app.main import app

HOST = {"userId": "u-host", "deviceId": "d-host", "displayName": "Kushagra", "avatarUrl": None}
GUEST = {"userId": "u-guest", "deviceId": "d-guest", "displayName": "Friend", "avatarUrl": None}


@pytest.fixture(autouse=True)
def a_clean_server():
    """Each test gets an empty process-wide store, which is where parties live."""
    main.store._parties.clear()
    yield
    main.store._parties.clear()


@pytest.fixture
def client():
    # No `with`: the lifespan starts the heartbeat ticker, whose unprompted
    # broadcasts would race the frames these tests are asserting on. The routes
    # are what is under test here; the ticker has its own reasoning in
    # [_heartbeat] and nothing in it is request-shaped.
    return TestClient(app)


def create(client) -> dict:
    response = client.post("/api/parties", json=HOST)
    assert response.status_code == 201
    return response.json()


def test_creating_a_party_returns_a_code_a_token_and_a_host(client):
    body = create(client)
    assert len(body["code"]) == 6
    assert body["token"]
    assert body["you"]["isHost"] is True
    assert body["party"]["maxMembers"] == config.MAX_MEMBERS
    assert [m["displayName"] for m in body["party"]["members"]] == ["Kushagra"]


def test_a_join_without_an_identity_is_refused(client):
    body = create(client)
    for missing in ({}, {**GUEST, "displayName": "   "}, {**GUEST, "userId": ""}):
        response = client.post(f"/api/parties/{body['code']}/join", json=missing)
        assert response.status_code == 422


def test_an_avatar_has_to_be_an_http_url(client):
    body = create(client)
    response = client.post(
        f"/api/parties/{body['code']}/join",
        json={**GUEST, "avatarUrl": "file:///data/data/com.music.bitchord/avatar.png"},
    )
    assert response.status_code == 422


def test_a_mistyped_code_still_finds_the_party(client):
    body = create(client)
    # Lower case, spaced out, and with the letters the alphabet excludes typed
    # where the digits belong — all of which a person reading a code aloud will
    # produce, and none of which should be a dead end.
    typed = body["code"].lower().replace("0", "o").replace("1", "l")
    response = client.post(f"/api/parties/{' '.join(typed)}/join", json=GUEST)
    assert response.status_code == 200
    assert response.json()["code"] == body["code"]


def test_an_unknown_code_is_a_404(client):
    response = client.post("/api/parties/ZZZZZZ/join", json=GUEST)
    assert response.status_code == 404
    assert response.json()["error"] == "no_such_party"


def test_a_sixth_device_is_turned_away(client):
    body = create(client)
    for i in range(config.MAX_MEMBERS - 1):
        filling = {"userId": f"u{i}", "deviceId": f"d{i}", "displayName": f"Listener {i}"}
        assert client.post(f"/api/parties/{body['code']}/join", json=filling).status_code == 200

    response = client.post(f"/api/parties/{body['code']}/join", json=GUEST)
    assert response.status_code == 409
    assert response.json()["error"] == "party_full"


def test_the_member_list_is_the_same_on_every_device(client):
    host = create(client)
    guest = client.post(f"/api/parties/{host['code']}/join", json=GUEST).json()

    seen = []
    for who in (host, guest):
        response = client.get(
            f"/api/parties/{host['code']}",
            headers={"Authorization": f"Bearer {who['token']}"},
        )
        assert response.status_code == 200
        seen.append([m["displayName"] for m in response.json()["members"]])
    assert seen[0] == seen[1] == ["Kushagra", "Friend"]


def test_reading_a_party_needs_a_token(client):
    host = create(client)
    assert client.get(f"/api/parties/{host['code']}").status_code == 401
    assert client.get(
        f"/api/parties/{host['code']}",
        headers={"Authorization": "Bearer not-a-real-token"},
    ).status_code == 401


def test_leaving_frees_the_slot(client):
    host = create(client)
    guest = client.post(f"/api/parties/{host['code']}/join", json=GUEST).json()
    response = client.post(
        f"/api/parties/{host['code']}/leave",
        headers={"Authorization": f"Bearer {guest['token']}"},
    )
    assert response.status_code == 200
    remaining = client.get(
        f"/api/parties/{host['code']}",
        headers={"Authorization": f"Bearer {host['token']}"},
    ).json()
    assert [m["displayName"] for m in remaining["members"]] == ["Kushagra"]


# ------------------------------------------------------------- sockets ----


def test_a_socket_without_a_valid_token_is_closed(client):
    host = create(client)
    with client.websocket_connect(f"/ws/parties/{host['code']}?token=wrong") as socket:
        frame = socket.receive_json()
    assert frame["type"] == "error"
    assert frame["error"] == "bad_token"


def test_a_socket_opens_with_the_whole_party(client):
    host = create(client)
    with client.websocket_connect(f"/ws/parties/{host['code']}?token={host['token']}") as socket:
        frame = socket.receive_json()
    assert frame["type"] == "welcome"
    assert frame["you"]["isHost"] is True
    assert frame["party"]["code"] == host["code"]
    assert frame["serverMs"] > 0


def test_ping_returns_the_clients_own_stamp_alongside_the_servers(client):
    host = create(client)
    with client.websocket_connect(f"/ws/parties/{host['code']}?token={host['token']}") as socket:
        socket.receive_json()  # welcome
        socket.send_json({"type": "ping", "clientMs": 1234567})
        frame = socket.receive_json()
    assert frame["type"] == "pong"
    # Echoed unread — it is how the client pairs the reply and halves the round
    # trip, and the server has no business interpreting it.
    assert frame["clientMs"] == 1234567
    assert frame["serverMs"] > 0


def test_a_guests_control_reaches_the_host(client):
    """Anyone controls the music: the guest is not the host and needs no leave."""
    host = create(client)
    guest = client.post(f"/api/parties/{host['code']}/join", json=GUEST).json()

    with client.websocket_connect(f"/ws/parties/{host['code']}?token={host['token']}") as host_socket:
        host_socket.receive_json()  # welcome
        with client.websocket_connect(f"/ws/parties/{host['code']}?token={guest['token']}") as guest_socket:
            guest_socket.receive_json()  # welcome
            host_socket.receive_json()   # members, now that the guest is on

            guest_socket.send_json({
                "type": "control",
                "action": "setTrack",
                "track": {"videoId": "dQw4w9WgXcQ", "title": "Song", "artist": "Artist", "durationMs": 213_000},
                "positionMs": 0,
                "isPlaying": True,
            })

            state = host_socket.receive_json()
            echo = guest_socket.receive_json()

    assert state["type"] == "state"
    assert state["playback"]["track"]["videoId"] == "dQw4w9WgXcQ"
    assert state["playback"]["isPlaying"] is True
    assert state["playback"]["updatedBy"] == guest["you"]["memberId"]
    # The controller re-anchors off the same frame as everybody else rather
    # than running on a state it predicted locally.
    assert echo["playback"]["seq"] == state["playback"]["seq"]


def test_state_frames_carry_an_anchor_a_client_can_derive_a_playhead_from(client):
    host = create(client)
    with client.websocket_connect(f"/ws/parties/{host['code']}?token={host['token']}") as socket:
        socket.receive_json()
        socket.send_json({
            "type": "control",
            "action": "setTrack",
            "track": {"videoId": "v1", "durationMs": 200_000},
            "positionMs": 42_000,
            "isPlaying": True,
        })
        playback = socket.receive_json()["playback"]

    assert playback["positionMs"] == 42_000
    # Anchored in the future by the scheduled-start lead, so every device has a
    # common instant to begin at.
    assert playback["anchorMs"] > playback["updatedAtMs"]
    assert playback["effectivePositionMs"] == 42_000


def test_setting_a_queue_sends_the_queue_then_the_state(client):
    host = create(client)
    with client.websocket_connect(f"/ws/parties/{host['code']}?token={host['token']}") as socket:
        socket.receive_json()  # welcome
        socket.send_json({
            "type": "control",
            "action": "setQueue",
            "queue": [{"videoId": "v0"}, {"videoId": "v1"}, {"videoId": "v2"}],
            "queueIndex": 1,
        })
        first = socket.receive_json()
        second = socket.receive_json()

    # The queue arrives first, so nobody holds a state pointing at an index in a
    # list they have not been given.
    assert first["type"] == "queue"
    assert [item["videoId"] for item in first["queue"]["items"]] == ["v0", "v1", "v2"]
    assert second["type"] == "state"
    assert "queue" not in second["playback"]
    assert second["playback"]["queueLength"] == 3
    assert second["playback"]["queueSeq"] == first["queue"]["seq"]


def test_a_heartbeat_sized_control_does_not_resend_the_queue(client):
    """A play after a setQueue must cost the same whether the queue is 3 or 300."""
    host = create(client)
    with client.websocket_connect(f"/ws/parties/{host['code']}?token={host['token']}") as socket:
        socket.receive_json()  # welcome
        socket.send_json({
            "type": "control",
            "action": "setQueue",
            "queue": [{"videoId": f"v{i}"} for i in range(50)],
            "queueIndex": 0,
        })
        socket.receive_json()  # queue
        socket.receive_json()  # state

        socket.send_json({"type": "control", "action": "play"})
        frame = socket.receive_json()

    assert frame["type"] == "state"
    assert "queue" not in frame["playback"]
    assert frame["playback"]["queueLength"] == 50


def test_a_client_can_ask_for_a_queue_it_missed(client):
    host = create(client)
    with client.websocket_connect(f"/ws/parties/{host['code']}?token={host['token']}") as socket:
        socket.receive_json()  # welcome
        socket.send_json({
            "type": "control",
            "action": "setQueue",
            "queue": [{"videoId": "v0"}],
            "queueIndex": 0,
        })
        socket.receive_json()  # queue
        socket.receive_json()  # state

        socket.send_json({"type": "syncQueue"})
        frame = socket.receive_json()

    assert frame["type"] == "queue"
    assert [item["videoId"] for item in frame["queue"]["items"]] == ["v0"]


def test_a_joining_device_is_given_the_whole_queue(client):
    """The one place the queue always travels: there is no other way to learn it."""
    host = create(client)
    with client.websocket_connect(f"/ws/parties/{host['code']}?token={host['token']}") as socket:
        socket.receive_json()
        socket.send_json({
            "type": "control",
            "action": "setQueue",
            "queue": [{"videoId": "v0"}, {"videoId": "v1"}],
            "queueIndex": 0,
        })
        socket.receive_json()
        socket.receive_json()

    guest = client.post(f"/api/parties/{host['code']}/join", json=GUEST).json()
    assert [item["videoId"] for item in guest["party"]["queue"]["items"]] == ["v0", "v1"]

    with client.websocket_connect(f"/ws/parties/{host['code']}?token={guest['token']}") as socket:
        welcome = socket.receive_json()
    assert [item["videoId"] for item in welcome["party"]["queue"]["items"]] == ["v0", "v1"]


def test_the_sequence_number_only_ever_climbs(client):
    host = create(client)
    with client.websocket_connect(f"/ws/parties/{host['code']}?token={host['token']}") as socket:
        socket.receive_json()
        seen = []
        for action in ("play", "pause", "play", "seek"):
            socket.send_json({"type": "control", "action": action, "positionMs": 1_000})
            seen.append(socket.receive_json()["playback"]["seq"])
    assert seen == sorted(seen)
    assert len(set(seen)) == len(seen)


def test_an_unknown_control_is_reported_not_applied(client):
    host = create(client)
    with client.websocket_connect(f"/ws/parties/{host['code']}?token={host['token']}") as socket:
        socket.receive_json()
        socket.send_json({"type": "control", "action": "selfDestruct"})
        frame = socket.receive_json()
    assert frame["type"] == "error"
    assert frame["error"] == "bad_control"


def test_a_sync_request_is_answered_with_the_current_state(client):
    host = create(client)
    with client.websocket_connect(f"/ws/parties/{host['code']}?token={host['token']}") as socket:
        socket.receive_json()
        socket.send_json({"type": "sync"})
        frame = socket.receive_json()
    assert frame["type"] == "state"
    assert frame["playback"]["seq"] == 0


def test_a_joining_device_is_announced_to_the_ones_already_listening(client):
    host = create(client)
    with client.websocket_connect(f"/ws/parties/{host['code']}?token={host['token']}") as socket:
        socket.receive_json()  # welcome
        client.post(f"/api/parties/{host['code']}/join", json=GUEST)
        frame = socket.receive_json()
    assert frame["type"] == "members"
    assert [m["displayName"] for m in frame["members"]] == ["Kushagra", "Friend"]
