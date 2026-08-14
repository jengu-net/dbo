package cloud.jengu.dbo.core;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * RFC 9562 UUIDv7: 48-bit unix-millis prefix + random. Time-ordered ids keep
 * B-tree inserts append-mostly and give feed cursors a natural tiebreak; the
 * groomed decision of dbo#4.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    public static String newId() {
        long millis = System.currentTimeMillis();
        byte[] rnd = new byte[10];
        RANDOM.nextBytes(rnd);

        long msb = (millis & 0xFFFF_FFFF_FFFFL) << 16;
        msb |= 0x7000L; // version 7
        msb |= (rnd[0] & 0x0FL) << 8;
        msb |= (rnd[1] & 0xFFL);

        long lsb = 0x8000_0000_0000_0000L; // RFC 4122 variant
        lsb |= (rnd[2] & 0x3FL) << 56;
        for (int i = 3; i < 10; i++) {
            lsb |= (rnd[i] & 0xFFL) << (8 * (9 - i));
        }
        return new UUID(msb, lsb).toString();
    }
}
