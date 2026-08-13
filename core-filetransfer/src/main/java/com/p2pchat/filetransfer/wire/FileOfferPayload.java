package com.p2pchat.filetransfer.wire;

/**
 * Sent to offer a file, matching {@code FileOfferPayload} in docs/architecture-spec.md §6's
 * {@code .proto} sketch, plus one field not in that sketch: {@code senderAddress}.
 *
 * <p><b>Why {@code senderAddress} exists (added after the first real M4c test run):</b> the
 * receiver has to reply with a {@code FileChunkRequestPayload}, which means it needs a full
 * dialable multiaddr for the sender — not just the sender's peer ID, which is all the
 * {@code sender} parameter on the receiving callback provides. The original design required the
 * operator to pass the sender's address to {@code FileReceiverMain} as a CLI argument at
 * startup — but the documented workflow is "start the receiver first," at which point the
 * sender doesn't exist yet to have an address. That's a real chicken-and-egg bug, not a
 * hypothetical one — it's exactly what broke on the first real test. The fix: the sender
 * already knows its own address ({@code network.listenAddresses()}); it reports it here, inside
 * the encrypted, authenticated offer, so the receiver never needs to be told in advance.
 *
 * <p>{@code fileKey} is the raw AES-256 key for this transfer (see
 * {@code core.filetransfer.FileKey}), carried in the clear <i>within this payload</i> —
 * protection comes from this whole payload being encrypted for one specific recipient via
 * their Double Ratchet session before it ever reaches the wire. That is exactly what §8 means
 * by the file key being "wrapped individually per-recipient via their session": it's the outer
 * encryption M2a/M2c already built, not a second mechanism this payload needs of its own.
 */
public record FileOfferPayload(
        String transferId,
        String senderAddress,
        String fileName,
        long fileSize,
        String fileHash,
        int chunkSize,
        int totalChunks,
        byte[] fileKey
) implements FileTransferMessage {
}
