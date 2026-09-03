package com.p2pchat.daemon.session;

import com.p2pchat.model.PeerId;
import com.p2pchat.storage.model.DeliveryState;
import com.p2pchat.storage.model.Message;
import com.p2pchat.storage.model.TransferState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * M6g-3 checkpoint: a minimal, real (not test-fake) {@link DaemonEventListener} — prints every
 * callback to the console, and auto-accepts every inbound file offer. Built specifically to
 * close a gap found before starting M6g-4: {@code SessionManagerListenerMain}/{@code
 * SessionManagerSenderMain} (M6e-2) still constructed a bare {@code new FileTransferHandler() {}}
 * no-op and relied on {@link DaemonEventListener#NONE}, so {@link DefaultFileTransferHandler}'s
 * real wiring into a live {@link SessionManager} — and every {@code DaemonEventListener} callback
 * — had only ever run against fakes ({@code SessionManagerReceivePipelineTest}, {@code
 * DefaultFileTransferHandlerTest}), never against real jvm-libp2p/libsignal-client between two
 * real processes. This class exists to give those two demo Mains a real listener + a real
 * file-transfer handler to run that checkpoint against.
 *
 * <p><b>Not a preview of the eventual M6g-4 listener.</b> The real one will forward these same
 * events as {@code event.*} JSON-RPC push frames over WebSocket, not print them — this one exists
 * only so this checkpoint's two demo Mains have something real (not {@link DaemonEventListener#NONE})
 * to construct {@link SessionManager} with, the same way {@code ChatListenerMain}'s own
 * auto-reply logic (M5c) proved a real round trip without being real product UX either.
 *
 * <p><b>Auto-accept, not a real accept/reject decision.</b> {@link #onFileOfferReceived} calls
 * {@link SessionManager#acceptFileTransfer} immediately and unconditionally — there is no UI here
 * to ask a real person. This checkpoint's job is proving the accept → chunk-request →
 * chunk-receive → hash-verify path actually works end to end against real hardware; a real
 * accept/reject decision is M6g-4/M7's job.
 *
 * <p><b>{@link #attachSessionManager} exists because of a real construction-order constraint, not
 * by choice.</b> {@code SessionManager}'s own constructor requires a {@code DaemonEventListener}
 * instance, but this listener's one piece of real behavior (auto-accepting a file offer) needs a
 * live {@code SessionManager} to call {@link SessionManager#acceptFileTransfer} on. Both Mains
 * construct this listener first, pass it into {@code SessionManager}'s constructor, then call
 * this setter with the very instance they just constructed — before calling {@code
 * SessionManager.start(...)}, the earliest point any callback could possibly fire, so there is no
 * window where a call could arrive before the reference is set.
 */
public final class PrintingDaemonEventListener implements DaemonEventListener {

    private final Path downloadDir;
    private volatile SessionManager sessionManager;

    public PrintingDaemonEventListener(Path downloadDir) {
        this.downloadDir = downloadDir;
    }

    /** See class Javadoc — must be called before {@code SessionManager.start(...)}. */
    public void attachSessionManager(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public void onMessageReceived(Message message) {
        String text = new String(message.plaintext(), StandardCharsets.UTF_8);
        System.out.println("[event] message.received conversation=" + message.conversationId()
                + " from=" + message.senderPeerId().value() + " content=\"" + text + "\"");
    }

    @Override
    public void onDeliveryStateChanged(String messageId, DeliveryState newState) {
        System.out.println("[event] delivery.changed messageId=" + messageId + " state=" + newState);
    }

    @Override
    public void onFileOfferReceived(String transferId, PeerId sender, String fileName, long fileSize) {
        System.out.println("[event] file.offer.received transferId=" + transferId + " from=" + sender.value()
                + " file=\"" + fileName + "\" (" + fileSize + " bytes) -- auto-accepting (checkpoint listener, no real UI)");
        SessionManager manager = this.sessionManager;
        if (manager == null) {
            System.err.println("[event] cannot auto-accept " + transferId + " -- SessionManager not yet attached");
            return;
        }
        Path savePath = downloadDir.resolve(transferId + "-" + sanitizeFileName(fileName));
        manager.acceptFileTransfer(transferId, savePath);
        System.out.println("[event] accepted " + transferId + ", saving to " + savePath.toAbsolutePath());
    }

    @Override
    public void onFileTransferProgress(String transferId, int chunksReceived, int totalChunks, TransferState state) {
        System.out.println("[event] file.transfer.progress transferId=" + transferId + " " + chunksReceived + "/"
                + totalChunks + " state=" + state);
    }

    @Override
    public void onNetworkStatusChanged() {
        System.out.println("[event] network.statusChanged (a session transitioned from not-existing to existing)");
    }

    // Defends only against path separators making it into a filename via a peer-supplied
    // FileOfferPayload.fileName -- not a full sanitizer, just enough that this checkpoint's own
    // downloadDir.resolve(...) call can't be walked outside downloadDir by a crafted offer.
    private static String sanitizeFileName(String fileName) {
        return fileName.replace("/", "_").replace("\\", "_");
    }
}
