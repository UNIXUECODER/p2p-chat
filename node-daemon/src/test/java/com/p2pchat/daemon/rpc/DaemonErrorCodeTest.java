package com.p2pchat.daemon.rpc;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonErrorCodeTest {

    @Test
    void standardCodesMatchTheJsonRpc20ReservedValues() {
        assertThat(DaemonErrorCode.INVALID_REQUEST.rpcCode()).isEqualTo(-32600);
        assertThat(DaemonErrorCode.METHOD_NOT_FOUND.rpcCode()).isEqualTo(-32601);
    }

    @Test
    void applicationCodesFallInsideTheReservedServerErrorRange() {
        // JSON-RPC 2.0 reserves -32000 to -32099 for implementation-defined server errors -- see
        // this enum's own Javadoc. Every application-specific value here must land inside it.
        for (DaemonErrorCode code : DaemonErrorCode.values()) {
            if (code == DaemonErrorCode.INVALID_REQUEST || code == DaemonErrorCode.METHOD_NOT_FOUND) {
                continue;
            }
            assertThat(code.rpcCode()).isBetween(-32099, -32000);
        }
    }

    @Test
    void everyValueHasADistinctRpcCode() {
        long distinctCount = Arrays.stream(DaemonErrorCode.values())
                .map(DaemonErrorCode::rpcCode)
                .collect(Collectors.toSet())
                .size();
        assertThat(distinctCount).isEqualTo(DaemonErrorCode.values().length);
    }

    @Test
    void exactlyTenValuesExistMatchingTheGapAnalysisPlan() {
        // docs/M6g-gap-analysis-and-plan.md §3 names exactly ten -- no more, no fewer.
        assertThat(DaemonErrorCode.values()).hasSize(10);
    }
}
