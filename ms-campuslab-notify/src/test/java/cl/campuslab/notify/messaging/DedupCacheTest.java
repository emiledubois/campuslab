package cl.campuslab.notify.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DedupCacheTest {

    private final DedupCache cache = new DedupCache(10_000, 24);

    @Test
    void isDuplicate_forUnseenEventId_returnsFalse() {
        assertThat(cache.isDuplicate("never-seen")).isFalse();
    }

    @Test
    void isDuplicate_afterMarkProcessed_returnsTrue() {
        cache.markProcessed("event-1");

        assertThat(cache.isDuplicate("event-1")).isTrue();
    }

    @Test
    void isDuplicate_forDifferentEventId_staysIndependent() {
        cache.markProcessed("event-1");

        assertThat(cache.isDuplicate("event-2")).isFalse();
    }
}
