package party

import (
	"fmt"
	"testing"

	"github.com/KabirSinghBhatia/BitChord/backend/codes"
	"github.com/KabirSinghBhatia/BitChord/backend/config"
)

func TestCodeNormalisation(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{"abc-def", "ABCDEF"},
		{"o 1 l i", "0111"},
		{" 123 456 ", "123456"},
	}
	for _, c := range cases {
		got := codes.Normalise(c.in)
		if got != c.want {
			t.Errorf("Normalise(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestPartyCreationAndJoin(t *testing.T) {
	store := NewPartyStore()
	p, err := store.Create()
	if err != nil {
		t.Fatalf("Failed to create party: %v", err)
	}

	avatar := "https://example.com/a.png"
	m1, err := p.Join("user1", "device1", "Alice", &avatar)
	if err != nil {
		t.Fatalf("Join failed: %v", err)
	}
	if !m1.IsHost {
		t.Errorf("First member should be host")
	}

	// Re-join with same deviceId should return existing member with fresh token
	oldToken := m1.Token
	m1Again, err := p.Join("user1", "device1", "Alice New", &avatar)
	if err != nil {
		t.Fatalf("Re-join failed: %v", err)
	}
	if m1Again.MemberId != m1.MemberId {
		t.Errorf("Re-join should maintain same memberId")
	}
	if m1Again.Token == oldToken {
		t.Errorf("Re-join should issue a fresh token")
	}
	if m1Again.DisplayName != "Alice New" {
		t.Errorf("Re-join should update displayName")
	}
}

func TestPartyCapacityLimit(t *testing.T) {
	p := NewParty("TEST12")
	for i := 0; i < config.MaxMembers; i++ {
		_, err := p.Join(fmt.Sprintf("u%d", i), fmt.Sprintf("d%d", i), fmt.Sprintf("User%d", i), nil)
		if err != nil {
			t.Fatalf("Join %d failed: %v", i, err)
		}
	}

	// Joining beyond capacity must be rejected
	_, err := p.Join("u_extra", "d_extra", "Extra", nil)
	if err == nil {
		t.Fatalf("Expected party_full error when joining past MaxMembers")
	}
	if pe, ok := err.(*PartyError); !ok || pe.Code != "party_full" {
		t.Errorf("Expected party_full error code, got %v", err)
	}
}

func TestQueueUpcoming25Limit(t *testing.T) {
	ps := NewPlaybackState()
	current := &Track{VideoId: "now_playing", Title: "Now Playing"}
	ps.SetTrack(nil, current, 0, true, nil, nil)
	ps.SetQueue(nil, []*Track{current}, 0)

	// Add 20 tracks
	var tracks []*Track
	for i := 0; i < 20; i++ {
		tracks = append(tracks, &Track{VideoId: fmt.Sprintf("track_%d", i), Title: fmt.Sprintf("Title %d", i)})
	}
	ok, reason := ps.AddUpcoming(nil, tracks, false)
	if !ok {
		t.Fatalf("Expected AddUpcoming to succeed for 20 tracks, failed: %s", reason)
	}
	if len(ps.Queue) != 21 { // 1 playing + 20 upcoming
		t.Fatalf("Expected 21 tracks in queue, got %d", len(ps.Queue))
	}

	// Attempt to add 10 more (should only accept 5 up to limit of 25)
	var more []*Track
	for i := 20; i < 30; i++ {
		more = append(more, &Track{VideoId: fmt.Sprintf("track_%d", i), Title: fmt.Sprintf("Title %d", i)})
	}
	ok, _ = ps.AddUpcoming(nil, more, false)
	if !ok {
		t.Fatalf("Expected partial addition to fill upcoming queue up to 25")
	}
	// Total queue must be 1 playing + 25 upcoming = 26
	if len(ps.Queue) != 26 {
		t.Fatalf("Expected 26 total tracks, got %d", len(ps.Queue))
	}

	// Attempting to add when full (25 upcoming) must return queue_full
	extra := []*Track{{VideoId: "overflow", Title: "Overflow"}}
	ok, reason = ps.AddUpcoming(nil, extra, false)
	if ok {
		t.Fatalf("Expected AddUpcoming to fail when 25 upcoming tracks exist")
	}
	if reason != "queue_full" {
		t.Fatalf("Expected reason queue_full, got %s", reason)
	}
}

func TestQueueRemoveAndClear(t *testing.T) {
	ps := NewPlaybackState()
	current := &Track{VideoId: "curr", Title: "Current"}
	t1 := &Track{VideoId: "t1", Title: "T1"}
	t2 := &Track{VideoId: "t2", Title: "T2"}

	ps.SetTrack(nil, current, 0, true, nil, nil)
	ps.SetQueue(nil, []*Track{current, t1, t2}, 0)

	// Remove t1
	if !ps.RemoveUpcoming(nil, "t1") {
		t.Fatalf("RemoveUpcoming failed for t1")
	}
	if len(ps.Queue) != 2 {
		t.Fatalf("Expected queue length 2 after removal, got %d", len(ps.Queue))
	}

	// Cannot remove playing track
	if ps.RemoveUpcoming(nil, "curr") {
		t.Fatalf("RemoveUpcoming should not allow removing currently playing track")
	}

	// Clear upcoming
	if !ps.ClearUpcoming(nil) {
		t.Fatalf("ClearUpcoming failed")
	}
	if len(ps.Queue) != 1 || ps.Queue[0].VideoId != "curr" {
		t.Fatalf("ClearUpcoming should preserve currently playing track, got len %d", len(ps.Queue))
	}
}

func TestQueueStep(t *testing.T) {
	ps := NewPlaybackState()
	t1 := &Track{VideoId: "t1", Title: "T1"}
	t2 := &Track{VideoId: "t2", Title: "T2"}
	ps.SetTrack(nil, t1, 0, true, nil, nil)
	ps.SetQueue(nil, []*Track{t1, t2}, 0)

	name := "Alice"
	if !ps.Step(nil, 1, &name) {
		t.Fatalf("Step forward failed")
	}
	if ps.QueueIndex != 1 || ps.Track.VideoId != "t2" {
		t.Fatalf("Expected playing track t2 at index 1, got %v at index %d", ps.Track, ps.QueueIndex)
	}

	// Cannot step forward past end of queue
	if ps.Step(nil, 1, &name) {
		t.Fatalf("Expected Step forward past end to fail")
	}
}
