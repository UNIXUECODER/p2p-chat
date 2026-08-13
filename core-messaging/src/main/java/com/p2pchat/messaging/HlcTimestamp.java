package com.p2pchat.messaging;

import java.util.Objects;

/**
 * A single hybrid logical clock value — the {@code (l, c)} pair from Kulkarni, Demirbas,
 * Madeppa, Avva, Leone, <i>"Logical Physical Clocks"</i> (OPODIS 2014 / SUNY Buffalo Tech
 * Report 2014-04), Figure 4, where {@code l} ({@link #physical}) is "the maximum physical
 * clock value this node is aware of" (not necessarily this node's own clock reading) and
 * {@code c} ({@link #counter}) disambiguates causally-ordered events that share the same
 * {@code l}.
 *
 * <p><b>Ordering ({@link #compareTo}):</b> the paper defines comparison as lexicographic over
 * {@code (l, c)} alone: {@code (a,b) < (c,d) iff a<c or (a=c and b<d)} (§3.3, footnote 2). This
 * is exactly what {@link #compareTo} does for {@link #physical}/{@link #counter} — that much is
 * the paper's own guarantee (Theorem 1: {@code e hb f ⟹ (l.e,c.e) < (l.f,c.f)}) and is not
 * changed here.
 *
 * <p><b>{@link #nodeId} is an addition on top of the paper, not part of it</b> — added as a
 * final tie-breaker, consulted only when two timestamps have identical {@code (l, c)}. The
 * paper doesn't need this: it only ever proves things about pairs of events that are causally
 * related ({@code hb}), and for those, Theorem 1 already guarantees {@code (l, c)} differs. Two
 * genuinely <i>concurrent</i> events from different nodes, however, can legitimately land on the
 * exact same {@code (l, c)} — the paper is explicit that this is expected, not an error (§2:
 * {@code lc.e = lc.f ⟹ e||f}). For most consumers of a logical clock that's fine; ordering
 * concurrent events relative to each other is meaningless by definition. But {@code hlc_timestamp}
 * here is also a SQLite {@code TEXT} sort key (docs/architecture-spec.md §9) backing a UI's
 * message list, and a UI has to render *some* deterministic order for two messages sent at once
 * by different people, even though "which one is really first" has no causal answer. {@code
 * nodeId} exists only to make that deterministic — it never influences the causal guarantee
 * above, since it's only ever reached after {@code physical} and {@code counter} have already
 * compared equal.
 *
 * @param physical the {@code l} component — epoch milliseconds, not necessarily this node's
 *                  own {@link java.time.Clock} reading (see {@link HybridLogicalClock})
 * @param counter  the {@code c} component — resets to 0 whenever {@code physical} advances
 * @param nodeId   final string tie-breaker; see above. Never empty.
 */
public record HlcTimestamp(long physical, int counter, String nodeId) implements Comparable<HlcTimestamp> {

    private static final int PHYSICAL_WIDTH = 19; // Long.MAX_VALUE has 19 digits — always fits, zero-padded.
    private static final int COUNTER_WIDTH = 10;  // Integer.MAX_VALUE has 10 digits — always fits, zero-padded.
    private static final int PREFIX_LENGTH = PHYSICAL_WIDTH + 1 + COUNTER_WIDTH + 1; // + two '-' separators

    public HlcTimestamp {
        if (physical < 0) {
            throw new IllegalArgumentException("physical must be >= 0, got " + physical);
        }
        if (counter < 0) {
            throw new IllegalArgumentException("counter must be >= 0, got " + counter);
        }
        Objects.requireNonNull(nodeId, "nodeId");
        if (nodeId.isEmpty()) {
            throw new IllegalArgumentException("nodeId must not be empty");
        }
    }

    @Override
    public int compareTo(HlcTimestamp other) {
        int byPhysical = Long.compare(this.physical, other.physical);
        if (byPhysical != 0) {
            return byPhysical;
        }
        int byCounter = Integer.compare(this.counter, other.counter);
        if (byCounter != 0) {
            return byCounter;
        }
        return this.nodeId.compareTo(other.nodeId); // the documented addition — see class Javadoc
    }

    /**
     * Canonical sortable encoding: {@code "{physical, 19 zero-padded digits}-{counter, 10
     * zero-padded digits}-{nodeId}"}. Fixed-width zero-padding on the two numeric fields means
     * plain {@link String#compareTo} on this output agrees with {@link #compareTo} exactly —
     * pinned down directly by {@code HlcTimestampTest.stringOrderingMatchesCompareTo}, not just
     * asserted here. Positional, not delimiter-split, on decode ({@link #parse}) specifically so
     * a {@code nodeId} containing {@code '-'} can never be misparsed — see {@link #parse}.
     */
    @Override
    public String toString() {
        return String.format("%0" + PHYSICAL_WIDTH + "d-%0" + COUNTER_WIDTH + "d-%s", physical, counter, nodeId);
    }

    /**
     * Inverse of {@link #toString}. Reads {@code physical} and {@code counter} by fixed
     * character position (not {@code split("-")}) precisely so that a {@code nodeId} containing
     * {@code '-'} round-trips correctly — libp2p peer IDs don't in practice (base58btc has no
     * {@code '-'} in its alphabet), but nothing here should silently depend on that holding.
     */
    public static HlcTimestamp parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length() <= PREFIX_LENGTH
                || encoded.charAt(PHYSICAL_WIDTH) != '-'
                || encoded.charAt(PHYSICAL_WIDTH + 1 + COUNTER_WIDTH) != '-') {
            throw new IllegalArgumentException("Malformed HlcTimestamp encoding: " + encoded);
        }
        long physical = Long.parseLong(encoded.substring(0, PHYSICAL_WIDTH));
        int counter = Integer.parseInt(encoded.substring(PHYSICAL_WIDTH + 1, PHYSICAL_WIDTH + 1 + COUNTER_WIDTH));
        String nodeId = encoded.substring(PREFIX_LENGTH);
        return new HlcTimestamp(physical, counter, nodeId);
    }
}
