package com.p2pchat.network;

/**
 * pre-m6h-hardening-plan.md finding B-1. See core-messaging.wire's identical copy of this class
 * for the full rationale (extends IllegalArgumentException so existing broad catch sites keep
 * working unchanged; duplicated rather than shared since this module has no dependency on
 * core-messaging/core-filetransfer or a common module that could host one class for all three
 * without becoming an unwarranted new coupling point). Shared by both RelayFrameCodec and
 * DiscoveryFrameCodec, which already live in this same package.
 */
public final class UnsupportedProtocolVersionException extends IllegalArgumentException {

    public UnsupportedProtocolVersionException(byte receivedVersion, byte supportedVersion) {
        super("Unsupported protocol version: received " + receivedVersion
                + ", this build only decodes version " + supportedVersion);
    }
}
