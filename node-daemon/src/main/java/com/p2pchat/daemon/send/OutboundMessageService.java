package com.p2pchat.daemon.send;

import com.p2pchat.network.ConnectionStrategy;
import com.p2pchat.network.ConnectivityStatus;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * M6b: the one outbound send path the "Open M6 design decisions" list named and M6a/M6e-1 both
 * left alone. Not new decision logic — {@link ConnectionStrategy} (M3b) already tries direct
 * first, falls back to relay, and always resolves to a {@link ConnectivityStatus} rather than
 * throwing. What's actually missing, confirmed by reading every M5c–M5e demo Main's real send
 * call before writing anything here: none of them call {@code ConnectionStrategy} at all — every
 * one bypasses it with a raw, synchronous {@code network.sendEnvelope(target, data)}, the 2-arg
 * overload with no timeout. {@code DialableAddressResolver}'s own Javadoc says as much directly:
 * "the existing direct-first/relay-fallback path... actually wired into a caller that currently
 * bypasses it... Tracked as M6 work, not attempted here." This class is that caller.
 *
 * <p><b>Why this needs to be async at all.</b> {@code ConnectionStrategy.send(...)} is
 * synchronous — it calls {@code PeerNetworkService.sendEnvelope(...)} directly and blocks until
 * that returns. M5c/M5d/M5e each independently hit, and each independently documented, the same
 * deadlock: {@code sendEnvelope} cannot be called synchronously from inside {@code
 * OnEnvelopeMessage}'s callback, because that callback runs on jvm-libp2p's own Netty event-loop
 * thread, and {@code sendEnvelope} blocks internally waiting on a new outbound connection that
 * needs that same event loop to complete — call it synchronously from there and the thread
 * waits on work it would itself have to service. Every demo Main's fix was the same one-off
 * pattern: wrap the call in {@code CompletableFuture.runAsync(...)}. This class is that pattern,
 * written once instead of four times, now actually calling {@code ConnectionStrategy} instead of
 * {@code sendEnvelope} directly.
 *
 * <p><b>A dedicated executor, not {@code ForkJoinPool.commonPool()}.</b> Every demo Main's
 * {@code CompletableFuture.runAsync(...)} used the JVM-wide common pool by default — harmless
 * for a one-shot process with a handful of sends, but M6's daemon is long-running and holds
 * concurrent sessions; sharing the common pool with whatever else runs in the same JVM (or a
 * future JSON-RPC handler, M6g) is exactly the kind of resource coupling a long-running service
 * shouldn't default into. This class owns a small dedicated pool instead, released via
 * {@link #close()}.
 *
 * <p><b>An overall timeout, on top of {@code ConnectionStrategy}'s own.</b> {@code
 * ConnectionStrategy} already bounds the direct attempt ({@code directTimeoutMillis}) — but its
 * relay attempt (a plain {@code relay.send(...)} call) has no timeout at all. Read that source
 * before writing this: confirmed, not assumed. Without a bound here, a hung relay call would tie
 * up a thread in this class's pool indefinitely rather than ever resolving. {@link
 * #send}'s returned future is bounded by {@code overallTimeout} end to end.
 *
 * <p><b>Any failure still resolves to {@code UNREACHABLE}, never a failed future.</b> {@code
 * ConnectionStrategy}'s own Javadoc states its principle directly: "Always returns a status
 * rather than sometimes throwing... so a caller always gets a definitive answer." A timeout or
 * an unexpected exception escaping this class's async boundary would otherwise complete the
 * returned future exceptionally, quietly breaking that guarantee one layer up. {@link #send}
 * extends the same principle here rather than letting it stop at {@code ConnectionStrategy}'s
 * own boundary.
 *
 * <p><b>What this deliberately does not do</b> — this is composition, not new capability:
 * <ul>
 *   <li>No relay address book / per-peer relay preference. {@link #send} takes {@code
 *   relayMultiaddr} as a parameter, same as {@code ConnectionStrategy} already does — where that
 *   value comes from (a single configured fallback relay, versus a per-peer preference learned
 *   via discovery) is M6e-2/M6f's decision, not this class's.</li>
 *   <li>No retry policy. One direct attempt, one relay attempt, exactly {@code
 *   ConnectionStrategy}'s existing contract — a caller wanting retries composes that on top of
 *   this class's returned future, this class doesn't hide retries inside itself.</li>
 * </ul>
 */
public final class OutboundMessageService implements AutoCloseable {

    private final ConnectionStrategy connectionStrategy;
    private final ExecutorService executor;
    private final Duration overallTimeout;

    public OutboundMessageService(ConnectionStrategy connectionStrategy, Duration overallTimeout) {
        this.connectionStrategy = connectionStrategy;
        this.overallTimeout = overallTimeout;
        // A small fixed pool, not cached/unbounded -- outbound sends are I/O-bound and
        // comparatively rare (human-paced messaging, not a tight loop), so a handful of threads
        // is plenty; unbounded would let a burst of sends to unreachable peers each hold a
        // thread for the full overallTimeout with no ceiling on how many pile up at once.
        this.executor = Executors.newFixedThreadPool(4);
    }

    /**
     * Sends {@code data} to a peer: direct first, relay fallback, exactly {@link
     * ConnectionStrategy#send}'s existing contract — this method's entire job is running that
     * call off the caller's thread and bounding it overall, not changing what it decides.
     *
     * @param directMultiaddr the peer's direct address, or {@code null}/blank to skip straight
     *                        to relay (same meaning as {@code ConnectionStrategy.send}'s own
     *                        parameter)
     * @param relayMultiaddr  a relay to fall back to, or {@code null} to skip relay entirely
     * @param targetPeerId    required if {@code relayMultiaddr} is non-null; ignored otherwise
     * @param data            the plaintext-encrypted wire bytes to send — this class has no
     *                        opinion on their shape, same as {@code ConnectionStrategy}
     * @return a future that always resolves to a {@link ConnectivityStatus}, never completes
     *         exceptionally — see this class's Javadoc for why that's a deliberate guarantee,
     *         not an accident of the implementation
     */
    public CompletableFuture<ConnectivityStatus> send(String directMultiaddr, String relayMultiaddr,
                                                        String targetPeerId, byte[] data) {
        try {
            return CompletableFuture
                    .supplyAsync(() -> connectionStrategy.send(directMultiaddr, relayMultiaddr, targetPeerId, data),
                            executor)
                    .orTimeout(overallTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .exceptionally(error -> ConnectivityStatus.UNREACHABLE);
        } catch (RejectedExecutionException e) {
            // supplyAsync(...) calls executor.execute(...) synchronously as part of setting up
            // the async stage -- on a closed/exhausted executor that throws straight out of this
            // method, before any CompletableFuture exists for the .exceptionally(...) above to
            // ever attach to. Found by actually running a close()-then-send() test, not by
            // reading this method: the guarantee this class's own Javadoc states -- "always
            // resolves to a status, never completes exceptionally" -- had a real hole here.
            return CompletableFuture.completedFuture(ConnectivityStatus.UNREACHABLE);
        }
    }

    /**
     * Releases this service's dedicated thread pool. Waits briefly for in-flight sends to
     * finish before forcing shutdown, since an in-flight send being cut off mid-attempt would
     * itself look like a lost message, not a clean stop — {@code node-daemon}'s eventual
     * shutdown path (M6h) should call this, but nothing before then holds an instance to close.
     */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
