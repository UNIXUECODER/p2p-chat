package com.p2pchat.filetransfer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkCipherTest {

    @Test
    void encryptDecryptRoundTrip() {
        FileKey key = FileKey.generate();
        byte[] originalData = "Super secret chunk data to encrypt".getBytes();

        EncryptedChunk encrypted = ChunkCipher.encrypt(key, 0, originalData);
        assertThat(encrypted.chunkIndex()).isEqualTo(0);
        assertThat(encrypted.nonce()).hasSize(12);
        assertThat(encrypted.ciphertext().length).isGreaterThan(originalData.length);

        byte[] decrypted = ChunkCipher.decrypt(key, encrypted);
        assertThat(decrypted).isEqualTo(originalData);
    }

    @Test
    void rejectTamperedCiphertext() {
        FileKey key = FileKey.generate();
        byte[] originalData = "Sensitive data".getBytes();

        EncryptedChunk encrypted = ChunkCipher.encrypt(key, 0, originalData);
        byte[] tamperedCiphertext = encrypted.ciphertext().clone();
        tamperedCiphertext[0] ^= 0x01; // flip 1 bit

        EncryptedChunk tamperedChunk = new EncryptedChunk(0, encrypted.nonce(), tamperedCiphertext);

        assertThatThrownBy(() -> ChunkCipher.decrypt(key, tamperedChunk))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("failed authentication");
    }

    @Test
    void rejectWrongKey() {
        FileKey key1 = FileKey.generate();
        FileKey key2 = FileKey.generate();
        byte[] originalData = "Test payload".getBytes();

        EncryptedChunk encrypted = ChunkCipher.encrypt(key1, 0, originalData);

        assertThatThrownBy(() -> ChunkCipher.decrypt(key2, encrypted))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("failed authentication");
    }
}
