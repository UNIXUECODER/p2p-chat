package com.p2pchat.messaging;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HlcTimestampTest {

    @Test
    void comparesByPhysicalFirst() {
        HlcTimestamp earlier = new HlcTimestamp(1000L, 99, "node-a");
        HlcTimestamp later = new HlcTimestamp(1001L, 0, "node-a"); // lower counter, but higher physical
        assertThat(earlier).isLessThan(later);
        assertThat(later).isGreaterThan(earlier);
    }

    @Test
    void comparesByCounterWhenPhysicalTies() {
        HlcTimestamp lower = new HlcTimestamp(1000L, 5, "node-a");
        HlcTimestamp higher = new HlcTimestamp(1000L, 6, "node-a");
        assertThat(lower).isLessThan(higher);
    }

    @Test
    void nodeIdOnlyBreaksTiesWhenPhysicalAndCounterAreBothEqual() {
        HlcTimestamp a = new HlcTimestamp(1000L, 5, "alice");
        HlcTimestamp b = new HlcTimestamp(1000L, 5, "bob");
        assertThat(a).isLessThan(b); // "alice" < "bob" lexicographically

        // Confirm nodeId never overrides a real physical/counter difference — it must only be
        // consulted as the last resort, never given priority over the causal fields.
        HlcTimestamp earlierButLaterNodeId = new HlcTimestamp(999L, 0, "zzz");
        HlcTimestamp laterButEarlierNodeId = new HlcTimestamp(1000L, 0, "aaa");
        assertThat(earlierButLaterNodeId).isLessThan(laterButEarlierNodeId);
    }

    @Test
    void equalTimestampsCompareAsZero() {
        HlcTimestamp a = new HlcTimestamp(1000L, 5, "node-a");
        HlcTimestamp b = new HlcTimestamp(1000L, 5, "node-a");
        assertThat(a.compareTo(b)).isZero();
        assertThat(a).isEqualTo(b);
    }

    @Test
    void toStringThenParseRoundTrips() {
        HlcTimestamp original = new HlcTimestamp(1_770_000_000_123L, 42, "12D3KooWAbc123");
        HlcTimestamp roundTripped = HlcTimestamp.parse(original.toString());
        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void toStringThenParseRoundTripsWithDashesInNodeId() {
        // Not realistic for a libp2p peer ID (base58btc has no '-'), but parse() is positional,
        // not split("-")-based, specifically so this isn't a latent assumption. Prove it.
        HlcTimestamp original = new HlcTimestamp(5000L, 3, "node-with-dashes-in-it");
        HlcTimestamp roundTripped = HlcTimestamp.parse(original.toString());
        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void parseRejectsMalformedInput() {
        assertThatThrownBy(() -> HlcTimestamp.parse("not-a-valid-encoding"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HlcTimestamp.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativePhysicalOrCounter() {
        assertThatThrownBy(() -> new HlcTimestamp(-1L, 0, "node-a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HlcTimestamp(0L, -1, "node-a"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyNodeId() {
        assertThatThrownBy(() -> new HlcTimestamp(0L, 0, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The load-bearing claim in {@link HlcTimestamp#toString}'s Javadoc: plain
     * {@link String#compareTo} on the encoded form must agree with {@link HlcTimestamp#compareTo}
     * exactly, for arbitrary values — not just the hand-picked cases above. This is what makes
     * {@code ORDER BY hlc_timestamp} in SQLite (docs/architecture-spec.md §9) actually correct.
     */
    @Test
    void stringOrderingMatchesCompareToAcrossManyRandomValues() {
        Random random = new Random(42); // fixed seed — deterministic, reproducible failure if this ever breaks
        List<HlcTimestamp> timestamps = random.longs(500, 0, 10_000)
                .mapToObj(physical -> new HlcTimestamp(physical, random.nextInt(1000), "node-" + random.nextInt(5)))
                .collect(Collectors.toList());

        List<HlcTimestamp> sortedByCompareTo = timestamps.stream().sorted().collect(Collectors.toList());
        List<String> sortedByString = timestamps.stream()
                .map(HlcTimestamp::toString)
                .sorted()
                .collect(Collectors.toList());

        List<String> expectedStrings = sortedByCompareTo.stream()
                .map(HlcTimestamp::toString)
                .collect(Collectors.toList());

        assertThat(sortedByString).isEqualTo(expectedStrings);
    }
}
