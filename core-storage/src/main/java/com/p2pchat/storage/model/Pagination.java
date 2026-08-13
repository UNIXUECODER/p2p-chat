package com.p2pchat.storage.model;

/**
 * A pagination cursor for {@code StorageService.queryMessages}, matching the {@code cursor} /
 * {@code limit} params {@code messages.history} already uses on the wire (docs/architecture-spec.md §7).
 * {@code cursor} is an {@code hlc_timestamp} value ("give me messages after this point"), or
 * null/blank for "from the beginning."
 */
public record Pagination(String cursor, int limit) {
}
