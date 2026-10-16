package com.dijo.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dijo.DatabaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

class EventServiceTest extends DatabaseTest {
    @Autowired
    EventService events;

    @Autowired
    DrawService draws;

    @BeforeEach
    void setUp() {
        account("admin", "ADMIN", "USER");
        signIn("admin", "ADMIN", "USER");
    }

    @Test
    void creatingAnEventCreatesAnEmptyDrawOfItsSize() {
        String id = events.createEvent("Singles", 8);

        Event event = events.event(id).orElseThrow();
        assertThat(event.name()).isEqualTo("Singles");
        assertThat(event.contestantCount()).isEqualTo(8);
        assertThat(event.drawLocked()).isFalse();
        assertThat(draws.draw(id)).hasSize(8).allMatch(DrawSlot::empty);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3, 6, 2048})
    void rejectsSizesThatAreNotAPowerOfTwoUpTo1024(int size) {
        assertThatThrownBy(() -> events.createEvent("Singles", size))
                .hasMessage("Contestants must be a power of 2 from 2 to 1024");
    }

    @Test
    void sizeListMatchesTheRule() {
        assertThat(EventService.CONTESTANT_SIZES).first().isEqualTo(2);
        assertThat(EventService.CONTESTANT_SIZES).last().isEqualTo(1024);
        assertThat(EventService.CONTESTANT_SIZES).allMatch(EventService::isValidContestantCount);
    }

    @Test
    void requiresAName() {
        assertThatThrownBy(() -> events.createEvent(" ", 8)).hasMessage("Event name is required");
    }

    @Test
    void listsEventsByNameWithHowFullTheirDrawIs() {
        signedUpPlayer("ada");
        String doubles = events.createEvent("Doubles", 4);
        events.createEvent("Archery", 2);
        draws.assign(doubles, 1, "ada");
        draws.assignBye(doubles, 2);

        assertThat(events.events()).extracting(Event::name).containsExactly("Archery", "Doubles");
        assertThat(events.eventSummaries()).extracting(EventSummary::filled).containsExactly(0, 2);
    }

    @Test
    void resizingAnEmptyDrawRebuildsIt() {
        String id = events.createEvent("Singles", 8);
        events.updateEvent(id, "Singles A", 4);

        assertThat(events.event(id).orElseThrow().name()).isEqualTo("Singles A");
        assertThat(draws.draw(id)).hasSize(4);
    }

    @Test
    void resizingIsBlockedOnceTheDrawHasEntries() {
        signedUpPlayer("ada");
        String id = events.createEvent("Singles", 8);
        draws.assign(id, 1, "ada");

        assertThatThrownBy(() -> events.updateEvent(id, "Singles", 4))
                .hasMessage("Clear the draw before changing the number of contestants");
        events.updateEvent(id, "Renamed", 8);
        assertThat(events.event(id).orElseThrow().name()).isEqualTo("Renamed");
    }

    @Test
    void deletingRemovesTheEventAndItsDraw() {
        String id = events.createEvent("Singles", 4);
        events.deleteEvent(id);

        assertThat(events.event(id)).isEmpty();
        assertThat(count("SELECT count(*) FROM draw_slots WHERE event_id = ?", id)).isZero();
    }

    @Test
    void lockedEventsCannotBeDeleted() {
        signedUpPlayer("ada", "bob");
        String id = events.createEvent("Singles", 2);
        draws.assign(id, 1, "ada");
        draws.assign(id, 2, "bob");
        draws.lock(id);

        assertThatThrownBy(() -> events.deleteEvent(id))
                .hasMessage("Unlock the draw before deleting the event");
    }

    @Test
    void playersCanReadEventsButNotChangeThem() {
        String id = events.createEvent("Singles", 4);
        signIn("ada", "USER");

        assertThat(events.event(id)).isPresent();
        assertThatThrownBy(() -> events.createEvent("Doubles", 4)).isInstanceOf(AccessDeniedException.class);
    }
}
