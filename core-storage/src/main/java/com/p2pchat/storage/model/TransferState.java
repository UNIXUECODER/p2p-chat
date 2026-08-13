package com.p2pchat.storage.model;

/** State of a file transfer. Matches file_transfers.state's CHECK constraint. */
public enum TransferState {
    OFFERED,
    ACCEPTED,
    IN_PROGRESS,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}
