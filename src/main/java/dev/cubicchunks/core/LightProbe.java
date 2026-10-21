package dev.cubicchunks.core;

import dev.cubicchunks.CubicChunks;

/**
 * Counts sections that the light engine reads with no storage behind them, from
 * {@code LightStorageDiagnosticMixin}. The exception itself carries no position, which is why this
 * fault has been guessed at repeatedly; this makes it a location and a count instead.
 *
 * The split that matters is inside the vanilla band versus outside it. A missing section inside the
 * band means the mod's height extension disturbed a chunk the engine had already prepared. A missing
 * section below it means the extension put sections in front of an engine that never allocated for
 * them. Those need different fixes.
 */
public final class LightProbe {

    /** The vanilla band is y -64..319, which is sections -4..19. */
    private static final int BAND_MIN_SECTION = -4;
    private static final int BAND_MAX_SECTION_EXCLUSIVE = 20;
    private static final int MAX_REPORTS = 24;

    private static final java.util.concurrent.ConcurrentHashMap<Long, Boolean> SEEN =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong IN_BAND = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong OUT_OF_BAND = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicInteger REPORTED = new java.util.concurrent.atomic.AtomicInteger();

    private LightProbe() {}

    public static void recordMissing(int sectionX, int sectionY, int sectionZ) {
        long key = ((long)sectionX << 40) ^ ((long)(sectionY + 4096) << 20) ^ (long)sectionZ;
        if (SEEN.put(key, Boolean.TRUE) != null) {
            return;
        }
        boolean insideBand = sectionY >= BAND_MIN_SECTION && sectionY < BAND_MAX_SECTION_EXCLUSIVE;
        if (insideBand) {
            IN_BAND.incrementAndGet();
        } else {
            OUT_OF_BAND.incrementAndGet();
        }
        if (REPORTED.incrementAndGet() > MAX_REPORTS) {
            return;
        }
        CubicChunks.LOGGER.warn(
                "[CubicChunks] light engine has no storage for section x={} sectionY={} (block y={}) z={}"
                        + " - {}",
                sectionX, sectionY, sectionY << 4, sectionZ,
                insideBand ? "inside the vanilla band" : "outside the vanilla band");
    }

    /** One-line summary for the benchmark report. */
    public static String summary() {
        return "sections with no light storage: " + IN_BAND.get() + " inside the vanilla band, "
                + OUT_OF_BAND.get() + " outside it, " + SEEN.size() + " distinct";
    }
}
