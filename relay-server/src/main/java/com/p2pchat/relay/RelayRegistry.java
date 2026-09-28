package com.p2pchat.relay;

import com.p2pchat.network.RelayController;
import com.p2pchat.network.RelayEventHandler;
import com.p2pchat.network.RelayFrame;
import com.p2pchat.model.PeerId;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The relay server's actual logic: remembers which peer is on which live
 * connection (via onConnected, fired by RelayProtocol the moment a stream
 * activates — no separate REGISTER message needed, the connection itself
 * IS the registration), and forwards FORWARD-type frames to their target's
 * connection, relabeled as a DELIVER-type frame carrying the original sender.
 *
 * <p><b>A-1 update (pre-m6h-hardening-plan.md, Track A):</b> before this, a client that
 * disconnected and never came back left its dead controller in {@code registered} forever —
 * every later FORWARD to it would find a stale entry instead of correctly seeing "not currently
 * connected." {@link #onDisconnected} now removes it, using the 2-arg {@code Map.remove(key,
 * value)} form specifically so a disconnect signal that arrives late (after the same peer already
 * reconnected on a fresh controller) can't evict that newer, valid entry — it only removes the
 * mapping if it's still pointing at the exact controller that just closed.
 *
 * <p><b>Commit 4 (Track A-3): a {@code FORWARD} frame with no route no longer just drops.</b> It
 * used to; now it's handed to {@link RelaySpool#enqueue}, and {@link #onConnected} drains
 * whatever's queued for a peer the moment it (re)registers. Two things worth confirming precisely
 * rather than assuming, since they were exactly the kind of thing Track A-2 got wrong the first
 * time on the client side: the {@code sender} parameter this class's own {@link #onFrame} already
 * receives — unlike the client-side seam A-2 had to fix — genuinely is the true original sender
 * for a {@code FORWARD} frame, not some other identity, because the client sending it is directly
 * connected to this relay; and {@link RelayController#send} still gives no signal about whether a
 * write actually reached the wire, so "delete from the spool right after attempting the send" in
 * {@link #onConnected}'s drain is exactly as reliable as this class's live, never-spooled forward
 * path already was — not a new risk, the same one this whole relay has always carried.
 */
public class RelayRegistry implements RelayEventHandler {

    private final Map<String, RelayController> registered = new ConcurrentHashMap<>();
    private final RelaySpool spool;

    public RelayRegistry(RelaySpool spool) {
        this.spool = Objects.requireNonNull(spool, "spool must not be null");
    }

    @Override
    public void onConnected(PeerId peerId, RelayController controller) {
        registered.put(peerId.toString(), controller);
        System.out.println("[relay] registered: " + peerId + " (" + registered.size() + " peer(s) connected)");
        drainSpooledMessages(peerId.toString(), controller);
    }

    /**
     * Delivers everything {@link RelaySpool} has queued for {@code targetPeerId}, oldest first,
     * deleting each one immediately after attempting delivery — see this class's own Javadoc for
     * why that's exactly as reliable as this relay's live forward path, not less. Runs
     * asynchronously off whatever thread {@link #onConnected} was called on (Netty's own I/O
     * worker — see {@link RelaySpool}'s own Javadoc for why that thread must never block on this).
     */
    private void drainSpooledMessages(String targetPeerId, RelayController controller) {
        spool.fetchForTarget(targetPeerId).thenAccept(messages -> {
            int deliveredCount = 0;
            for (SpooledMessage message : messages) {
                if (registered.get(targetPeerId) != controller) {
                    System.out.println("[relay] peer " + targetPeerId
                            + " disconnected or changed connection mid-drain — stopping spool flush");
                    break;
                }
                controller.send(new RelayFrame(false, message.senderPeerId(), message.payload()));
                spool.delete(message.id()).exceptionally(ex -> {
                    System.err.println("[relay] failed to remove delivered spooled message " + message.id() + ": " + ex);
                    return null;
                });
                deliveredCount++;
            }
            if (deliveredCount > 0) {
                System.out.println("[relay] delivered " + deliveredCount + " spooled message(s) to " + targetPeerId
                        + " on reconnect");
            }
        }).exceptionally(ex -> {
            System.err.println("[relay] failed to drain spooled messages for " + targetPeerId + ": " + ex);
            return null;
        });
    }

    @Override
    public void onDisconnected(PeerId peerId, RelayController controller) {
        if (registered.remove(peerId.toString(), controller)) {
            System.out.println("[relay] disconnected: " + peerId + " (" + registered.size() + " peer(s) connected)");
        }
    }

    @Override
    public void onFrame(PeerId sender, RelayFrame frame) {
        if (!frame.isForwardRequest()) {
            // A relay server should only ever RECEIVE forward requests — only clients
            // receive deliveries. Ignore defensively rather than fail loudly on a
            // message shape that should never reach this side of the protocol.
            return;
        }

        RelayController target = registered.get(frame.peerId());
        if (target == null) {
            spool.enqueue(frame.peerId(), sender.toString(), frame.payload())
                    .thenAccept(result -> logEnqueueResult(frame.peerId(), result))
                    .exceptionally(ex -> {
                        System.err.println("[relay] failed to spool message for " + frame.peerId() + ": " + ex);
                        return null;
                    });
            return;
        }

        target.send(new RelayFrame(false, sender.toString(), frame.payload()));
        System.out.println("[relay] forwarded " + frame.payload().length + " bytes from " + sender + " to " + frame.peerId());
    }

    private void logEnqueueResult(String targetPeerId, EnqueueResult result) {
        switch (result) {
            case ACCEPTED -> System.out.println(
                    "[relay] no route to " + targetPeerId + " (not currently connected) — spooled for later delivery");
            case REJECTED_QUOTA_EXCEEDED -> System.out.println(
                    "[relay] no route to " + targetPeerId + " and its spool quota is exhausted — dropped");
            case REJECTED_MESSAGE_TOO_LARGE -> System.out.println(
                    "[relay] message for " + targetPeerId + " exceeds the spool's max frame size — dropped");
        }
    }
}
