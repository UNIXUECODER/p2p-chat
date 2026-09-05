package com.p2pchat.daemon.session;

import com.p2pchat.model.PeerId;
import com.p2pchat.storage.model.DeliveryState;
import com.p2pchat.storage.model.Message;
import com.p2pchat.storage.model.TransferState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

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
        Path savePath = resolveSafeSavePath(transferId, fileName);
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

    // Everything below is pre-m6h-hardening-plan.md finding C-4. The previous version only
    // replaced "/" and "\\" in fileName, which was flagged as fragile-by-construction: a denylist
    // guarding a resolve() call breaks the moment any caller uses it differently, or a new
    // traversal trick surfaces. Reviewing it for this fix also turned up a second input the old
    // version never touched at all — transferId, which is just as wire-supplied and attacker-
    // controlled as fileName (see FileOfferPayload/FileTransferMessageCodec — decoded as a plain
    // string, never validated as the UUID format the sending side happens to generate), and was
    // being concatenated into the same path unsanitized. An allowlist on fileName alone would
    // have left that path open. The fix below is allowlist-plus-containment-assertion on the
    // *resolved path as a whole*, specifically because that closes both inputs at once rather
    // than requiring a matching fix be remembered for every field that ever ends up in a path.

    private static final int MAX_SAFE_NAME_LENGTH = 150;
    // Parentheses are allowed deliberately, not just permissively: dedupe() below generates
    // "name (2).ext" itself, so excluding "(" ")" from user-supplied names while the collision
    // suffix uses them would be an inconsistency, not extra safety — a name with real parens
    // (e.g. a peer sending "final (draft).pdf") would otherwise be mangled for no security benefit.
    private static final Pattern UNSAFE_CHARACTERS = Pattern.compile("[^A-Za-z0-9._ ()-]");
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    /**
     * Turns a peer-supplied {@code transferId} and {@code fileName} into a path that is
     * guaranteed to resolve inside {@link #downloadDir}, guaranteed not to collide with an
     * existing file, and safe on both POSIX and Windows filesystems.
     *
     * <p>Three layers, deliberately not relying on any single one alone:
     * <ol>
     *   <li><b>Allowlist</b> each candidate component down to {@code [A-Za-z0-9._ -]}, strip
     *       leading dots (so a component can't become {@code "."}, {@code ".."}, or a hidden
     *       file), cap length, and reject Windows-reserved device names — case-insensitively,
     *       and against the base name before any extension, since Windows treats
     *       {@code "CON.txt"} as reserved too, not just bare {@code "CON"}.</li>
     *   <li><b>Containment assertion after resolution</b>: the allowlist above is defence in
     *       depth, not the load-bearing check. The load-bearing check is that
     *       {@code resolved.normalize()} must still start with {@code downloadDir.normalize()} —
     *       this is what actually stops a traversal, independent of which input caused it or
     *       whether the allowlist has a gap nobody's found yet.</li>
     *   <li><b>Collision handling</b>: if the resolved path already exists, append
     *       {@code " (2)"}, {@code " (3)"}, ... before the extension rather than silently
     *       overwriting a previous download — relevant even with {@code transferId} prefixed,
     *       since transferId is peer-supplied and nothing stops a peer (malicious or just
     *       retrying) from reusing one.</li>
     * </ol>
     */
    // Package-private, not private: lets PrintingDaemonEventListenerTest exercise the path-safety
    // logic directly, against a real @TempDir, without needing to stand up a full SessionManager
    // (a heavy, many-dependency construction this class only needs for the one live call this
    // method's caller makes) just to reach code that has nothing to do with SessionManager at all.
    Path resolveSafeSavePath(String transferId, String fileName) {
        Path normalizedDownloadDir = downloadDir.toAbsolutePath().normalize();
        String candidateBase = allowlistedComponent(transferId) + "-" + allowlistedComponent(fileName);

        Path resolved = normalizedDownloadDir.resolve(candidateBase).normalize();
        if (!resolved.startsWith(normalizedDownloadDir)) {
            // Should be unreachable given the allowlist above (it strips "/", "\\", and leading
            // dots, so no candidate can produce ".." or an absolute path) — kept anyway as the
            // real defence per this method's Javadoc, not just a sanity check.
            throw new SecurityException(
                    "Refusing to save file outside download directory: transferId=" + transferId + " fileName=" + fileName);
        }

        return dedupe(resolved, normalizedDownloadDir);
    }

    /** Applies the allowlist/length-cap/reserved-name rules to a single path component. */
    static String allowlistedComponent(String raw) {
        String cleaned = UNSAFE_CHARACTERS.matcher(raw == null ? "" : raw).replaceAll("_");
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isBlank()) {
            cleaned = "unnamed";
        }
        if (cleaned.length() > MAX_SAFE_NAME_LENGTH) {
            cleaned = cleaned.substring(0, MAX_SAFE_NAME_LENGTH);
        }

        String baseName = cleaned.contains(".") ? cleaned.substring(0, cleaned.indexOf('.')) : cleaned;
        if (WINDOWS_RESERVED_NAMES.contains(baseName.toUpperCase(Locale.ROOT))) {
            cleaned = "_" + cleaned;
        }
        return cleaned;
    }

    /** If {@code path} already exists, finds the first "name (n).ext" that doesn't. */
    private static Path dedupe(Path path, Path normalizedDownloadDir) {
        if (!Files.exists(path)) {
            return path;
        }
        String fullName = path.getFileName().toString();
        int dot = fullName.lastIndexOf('.');
        String stem = dot > 0 ? fullName.substring(0, dot) : fullName;
        String extension = dot > 0 ? fullName.substring(dot) : "";

        for (int suffix = 2; suffix < 10_000; suffix++) {
            Path candidate = normalizedDownloadDir.resolve(stem + " (" + suffix + ")" + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        // Practically unreachable (would mean 10,000 same-named collisions in one directory) —
        // fail loudly rather than silently overwrite, which is the one thing this method exists
        // to prevent.
        throw new IllegalStateException("Could not find a free filename for " + fullName + " in " + normalizedDownloadDir);
    }
}
