package cl.campuslab.notify.messaging;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Notify's whole idempotency mechanism (CLAUDE.md's "consumers are idempotent, keyed on
 * eventId" mandate; design doc §5.3) - a bounded, in-memory Caffeine cache, exactly as
 * specified: 10,000 entries, 24h TTL. Deliberately not a database table (notify has none)
 * - a redelivery after a notify restart is not deduplicated, an accepted, documented gap
 * (design doc §5.3's "stated plainly, the accepted failure mode").
 */
@Component
public class DedupCache {

    private final Cache<String, Boolean> cache;

    public DedupCache(
            @Value("${notify.dedup.max-size:10000}") long maxSize,
            @Value("${notify.dedup.expire-after-write-hours:24}") long expireAfterWriteHours) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofHours(expireAfterWriteHours))
                .build();
    }

    public boolean isDuplicate(String eventId) {
        return cache.getIfPresent(eventId) != null;
    }

    public void markProcessed(String eventId) {
        cache.put(eventId, Boolean.TRUE);
    }
}
