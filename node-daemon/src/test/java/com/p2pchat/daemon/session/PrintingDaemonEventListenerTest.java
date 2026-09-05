package com.p2pchat.daemon.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers pre-m6h-hardening-plan.md finding C-4: {@link PrintingDaemonEventListener}'s handling
 * of peer-supplied {@code transferId}/{@code fileName} when deciding where to save an incoming
 * file. Exercises {@link PrintingDaemonEventListener#resolveSafeSavePath} directly (see that
 * method's package-private visibility note) rather than going through
 * {@link DaemonEventListener#onFileOfferReceived}, which would require constructing a full
 * {@link SessionManager} to reach logic that has nothing to do with SessionManager at all.
 */
class PrintingDaemonEventListenerTest {

    @Test
    void resultAlwaysStaysInsideDownloadDir(@TempDir Path downloadDir) {
        PrintingDaemonEventListener listener = new PrintingDaemonEventListener(downloadDir);

        Path resolved = listener.resolveSafeSavePath("abc-123", "report.pdf");

        assertThat(resolved.normalize().startsWith(downloadDir.toAbsolutePath().normalize())).isTrue();
        assertThat(resolved.getFileName().toString()).isEqualTo("abc-123-report.pdf");
    }

    // Classic path-traversal payloads in fileName. Each of these, before this fix, would have
    // been used almost verbatim in a Files-backed write via the old two-character denylist.
    @ParameterizedTest
    @ValueSource(strings = {
            "../../../etc/passwd",
            "../../secrets.txt",
            "..\\..\\windows\\system32\\config",
            "/etc/passwd",
            "C:\\Windows\\System32\\evil.dll",
            "....//....//etc/passwd", // double-encoded-style traversal attempt
    })
    void traversalAttemptsInFileNameStayContained(String maliciousFileName, @TempDir Path downloadDir) {
        PrintingDaemonEventListener listener = new PrintingDaemonEventListener(downloadDir);

        Path resolved = listener.resolveSafeSavePath("transfer-1", maliciousFileName);

        assertThat(resolved.normalize().startsWith(downloadDir.toAbsolutePath().normalize())).isTrue();
    }

    // The same attempts, but via transferId — the input the pre-fix version didn't sanitize at
    // all. This is the exact gap found while implementing C-4, not something the audit itself
    // named, so it gets its own explicit coverage rather than being folded into the fileName
    // cases above.
    @ParameterizedTest
    @ValueSource(strings = {
            "../../../etc/passwd",
            "../../secrets",
            "..\\..\\evil",
    })
    void traversalAttemptsInTransferIdStayContained(String maliciousTransferId, @TempDir Path downloadDir) {
        PrintingDaemonEventListener listener = new PrintingDaemonEventListener(downloadDir);

        Path resolved = listener.resolveSafeSavePath(maliciousTransferId, "report.pdf");

        assertThat(resolved.normalize().startsWith(downloadDir.toAbsolutePath().normalize())).isTrue();
    }

    @Test
    void reservedWindowsDeviceNamesAreRejectedRegardlessOfExtension() {
        assertThat(PrintingDaemonEventListener.allowlistedComponent("CON")).isNotEqualTo("CON");
        assertThat(PrintingDaemonEventListener.allowlistedComponent("con")).isNotEqualTo("con");
        assertThat(PrintingDaemonEventListener.allowlistedComponent("CON.txt")).isNotEqualTo("CON.txt");
        assertThat(PrintingDaemonEventListener.allowlistedComponent("LPT1")).isNotEqualTo("LPT1");
        assertThat(PrintingDaemonEventListener.allowlistedComponent("COM9.log")).isNotEqualTo("COM9.log");
        // A name that merely contains a reserved word isn't reserved itself — only an exact
        // base-name match is (Windows doesn't reserve "CONTACT" or "ICON").
        assertThat(PrintingDaemonEventListener.allowlistedComponent("CONTACT.txt")).isEqualTo("CONTACT.txt");
    }

    @Test
    void leadingDotsAreStrippedSoResultCannotBeHiddenOrDotDot() {
        assertThat(PrintingDaemonEventListener.allowlistedComponent("..")).isNotEqualTo("..");
        assertThat(PrintingDaemonEventListener.allowlistedComponent(".")).isNotEqualTo(".");
        assertThat(PrintingDaemonEventListener.allowlistedComponent(".hidden")).doesNotStartWith(".");
    }

    @Test
    void blankOrAllUnsafeInputProducesAFallbackName() {
        assertThat(PrintingDaemonEventListener.allowlistedComponent("")).isEqualTo("unnamed");
        assertThat(PrintingDaemonEventListener.allowlistedComponent("///")).isNotBlank();
    }

    @Test
    void veryLongNameIsCapped() {
        String longName = "a".repeat(1000) + ".txt";
        String result = PrintingDaemonEventListener.allowlistedComponent(longName);
        assertThat(result.length()).isLessThanOrEqualTo(150);
    }

    @Test
    void safeCharactersPassThroughUnchanged() {
        assertThat(PrintingDaemonEventListener.allowlistedComponent("My Report_v2 (final).txt"))
                .isEqualTo("My Report_v2 (final).txt");
    }

    @Test
    void collidingSaveGetsANumberedSuffixInsteadOfOverwriting(@TempDir Path downloadDir) throws IOException {
        PrintingDaemonEventListener listener = new PrintingDaemonEventListener(downloadDir);

        Path first = listener.resolveSafeSavePath("dup", "photo.png");
        Files.createFile(first);

        Path second = listener.resolveSafeSavePath("dup", "photo.png");

        assertThat(second).isNotEqualTo(first);
        assertThat(second.getFileName().toString()).isEqualTo("dup-photo (2).png");
        assertThat(second.normalize().startsWith(downloadDir.toAbsolutePath().normalize())).isTrue();
    }

    @Test
    void thirdCollisionGetsTheNextNumber(@TempDir Path downloadDir) throws IOException {
        PrintingDaemonEventListener listener = new PrintingDaemonEventListener(downloadDir);

        Path first = listener.resolveSafeSavePath("dup", "photo.png");
        Files.createFile(first);
        Path second = listener.resolveSafeSavePath("dup", "photo.png");
        Files.createFile(second);

        Path third = listener.resolveSafeSavePath("dup", "photo.png");

        assertThat(third.getFileName().toString()).isEqualTo("dup-photo (3).png");
    }
}
