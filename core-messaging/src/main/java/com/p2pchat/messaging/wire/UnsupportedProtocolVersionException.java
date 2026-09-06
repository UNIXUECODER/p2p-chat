package com.p2pchat.messaging.wire;

/**
 * pre-m6h-hardening-plan.md finding B-1: thrown when a wire payload's version byte is
 * well-formed but not one this build knows how to decode — distinct from the generic {@link
 * IllegalArgumentException} every other decode failure in this codec throws, specifically so a
 * future daemon can tell "this peer sent garbage" from "this peer is running a newer protocol
 * version than I understand," which call for different responses (drop-and-log vs. a genuine
 * user-facing "please upgrade" message).
 *
 * <p>Extends {@link IllegalArgumentException} rather than being a wholly separate exception
 * type: every existing catch site in this codebase that already catches malformed-input
 * failures broadly (see {@code SessionManager}'s own outer catch-all, documented at every
 * {@code ChunkCipher.decrypt}/codec call site as "throws (unchecked) on tamper") keeps working
 * completely unchanged. A caller that wants to distinguish "too new" from "corrupt" can add a
 * {@code catch (UnsupportedProtocolVersionException e)} clause before a broader {@code catch
 * (IllegalArgumentException e)} one; a caller that doesn't care yet doesn't have to change
 * anything to keep working.
 *
 * <p>Duplicated in {@code core-filetransfer} and {@code core-network} rather than shared from
 * one place — none of the three modules with a version-checked codec currently depend on each
 * other or on a common module that could host this without becoming a new, unwarranted coupling
 * point purely to share an eight-line exception class. Same trade-off already made for the
 * owner-only-file-permission helper (pre-m6h-hardening-plan.md finding C-2).
 */
public final class UnsupportedProtocolVersionException extends IllegalArgumentException {

    public UnsupportedProtocolVersionException(byte receivedVersion, byte supportedVersion) {
        super("Unsupported protocol version: received " + receivedVersion
                + ", this build only decodes version " + supportedVersion);
    }
}
