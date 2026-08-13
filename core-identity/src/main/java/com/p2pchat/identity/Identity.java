package com.p2pchat.identity;

public record Identity(String peerId, String displayName, byte[] publicKey, long createdAt) {}
