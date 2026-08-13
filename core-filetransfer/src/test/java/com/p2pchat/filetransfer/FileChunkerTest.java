package com.p2pchat.filetransfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileChunkerTest {

    @Test
    void chunkCountCalculation() {
        assertThat(FileChunker.chunkCount(0, 256 * 1024)).isEqualTo(0);
        assertThat(FileChunker.chunkCount(100, 256 * 1024)).isEqualTo(1);
        assertThat(FileChunker.chunkCount(256 * 1024, 256 * 1024)).isEqualTo(1);
        assertThat(FileChunker.chunkCount(256 * 1024 + 1, 256 * 1024)).isEqualTo(2);
        assertThat(FileChunker.chunkCount(1000, 16)).isEqualTo(63);
    }

    @Test
    void readChunkAndHashFile(@TempDir Path tempDir) throws IOException {
        Path tempFile = tempDir.resolve("sample.bin");
        byte[] content = "Hello, world! This is a test file for chunking.".getBytes();
        Files.write(tempFile, content);

        int chunkSize = 16;
        int totalChunks = FileChunker.chunkCount(content.length, chunkSize);
        assertThat(totalChunks).isEqualTo(3);

        byte[] chunk0 = FileChunker.readChunk(tempFile, 0, chunkSize);
        assertThat(chunk0).hasSize(16);
        assertThat(chunk0).isEqualTo("Hello, world! Th".getBytes());

        byte[] chunk2 = FileChunker.readChunk(tempFile, 2, chunkSize);
        assertThat(chunk2.length).isLessThan(16);

        String fullHash = FileChunker.sha256HexOfFile(tempFile);
        assertThat(fullHash).isNotNull().hasSize(64);

        assertThatThrownBy(() -> FileChunker.readChunk(tempFile, 99, chunkSize))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
