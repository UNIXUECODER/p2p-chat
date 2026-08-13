package com.p2pchat.crypto;

/**
 * A single wire-ready encrypted unit: whether this is a PreKey-type message
 * (carries the PQXDH handshake, only ever the first message in a session) or
 * a Whisper-type message (plain Double Ratchet, every message after that),
 * plus its serialized ciphertext bytes.
 */
public record EncryptedFrame(boolean isPreKeyMessage, byte[] ciphertext) {
}
