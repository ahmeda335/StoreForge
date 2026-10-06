package dev.storeforge.shared.ids;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * UUIDv7 generator (RFC 9562): 48-bit Unix-millisecond timestamp followed by random bits.
 *
 * <p>Why v7 instead of {@link UUID#randomUUID()} (v4): IDs are still unguessable, but they are
 * roughly time-ordered, so new rows land at the "right end" of B-tree primary-key indexes instead
 * of random pages (less index fragmentation and fewer page splits). Generated in the application,
 * so an entity has its ID before it is saved.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        return newId(Clock.systemUTC());
    }

    static UUID newId(Clock clock) {
        long millis = clock.millis();
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);

        long mostSignificant = (millis & 0xFFFF_FFFF_FFFFL) << 16    // 48 bits: unix_ts_ms
                | 0x7000L                                            // 4 bits: version 7
                | ((random[0] & 0x0FL) << 8) | (random[1] & 0xFFL);  // 12 bits: rand_a
        long leastSignificant = 0x8000_0000_0000_0000L               // 2 bits: variant 10
                | ((random[2] & 0x3FL) << 56);
        for (int i = 3; i < 10; i++) {
            leastSignificant |= (random[i] & 0xFFL) << (8 * (9 - i));
        }
        return new UUID(mostSignificant, leastSignificant);
    }
}
