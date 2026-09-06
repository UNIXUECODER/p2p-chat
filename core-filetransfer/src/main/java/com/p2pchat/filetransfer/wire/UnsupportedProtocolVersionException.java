package com.p2pchat.filetransfer.wire;

/**
 * pre-m6h-hardening-plan.md finding B-1. See core-messaging.wire's identical copy of this class
 * for the full rationale (extends IllegalArgumentException so existing broad catch sites keep
 * working unchanged; duplicated rather than shared since this module has no dependency on
 * core-messaging or a common module that could host one class for both without becoming an
 * unwarranted new coupling point).
 */
public final class UnsupportedProtocolVersionException extends IllegalArgumentException {

    public UnsupportedProtocolVersionException(byte receivedVersion, byte supportedVersion) {
        super("Unsupported protocol version: received " + receivedVersion
                + ", this build only decodes version " + supportedVersion);
    }
}
