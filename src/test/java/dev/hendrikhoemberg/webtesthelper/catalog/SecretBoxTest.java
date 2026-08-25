package dev.hendrikhoemberg.webtesthelper.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretBoxTest {

    @Test
    void roundTripSucceedsForAsciiAndUmlauts(@TempDir Path tempDir) {
        SecretBox box = new SecretBox(tempDir);

        String ascii = "simple-secret-123!";
        String umlauts = "Paßwort mit Ümlaut und €uro-Zeichen";

        assertThat(box.decrypt(box.encrypt(ascii))).isEqualTo(ascii);
        assertThat(box.decrypt(box.encrypt(umlauts))).isEqualTo(umlauts);
    }

    @Test
    void encryptingSamePlaintextTwiceProducesDifferentCiphertext(@TempDir Path tempDir) {
        SecretBox box = new SecretBox(tempDir);
        String plaintext = "same-password-twice";

        String first = box.encrypt(plaintext);
        String second = box.encrypt(plaintext);

        assertThat(first).isNotEqualTo(second);
        assertThat(box.decrypt(first)).isEqualTo(plaintext);
        assertThat(box.decrypt(second)).isEqualTo(plaintext);
    }

    @Test
    void ciphertextIsBase64AndDoesNotContainPlaintext(@TempDir Path tempDir) {
        SecretBox box = new SecretBox(tempDir);
        String plaintext = "SuperSecretPassword12345";

        String ciphertext = box.encrypt(plaintext);

        assertThat(ciphertext).doesNotContain(plaintext);
        assertThat(ciphertext).doesNotContain("SuperSecret");
        assertThat(ciphertext).doesNotContain("Password12345");

        byte[] decoded = Base64.getDecoder().decode(ciphertext);
        assertThat(decoded).isNotEmpty();
    }

    @Test
    void tamperedCiphertextThrowsException(@TempDir Path tempDir) {
        SecretBox box = new SecretBox(tempDir);
        String plaintext = "untampered-data";

        String ciphertext = box.encrypt(plaintext);

        // Flip one character in base64 string
        char originalChar = ciphertext.charAt(ciphertext.length() / 2);
        char flippedChar = (originalChar == 'A') ? 'B' : 'A';
        String tampered = ciphertext.substring(0, ciphertext.length() / 2)
                + flippedChar
                + ciphertext.substring(ciphertext.length() / 2 + 1);

        assertThatThrownBy(() -> box.decrypt(tampered))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void keyFileIsCreatedWithRestrictedPermissionsAndReused(@TempDir Path tempDir) throws IOException {
        Path keyFile = tempDir.resolve("keyfile");
        assertThat(keyFile).doesNotExist();

        SecretBox box1 = new SecretBox(tempDir);
        String ciphertext = box1.encrypt("persistent-secret");

        assertThat(keyFile).exists();
        byte[] keyBytes = Files.readAllBytes(keyFile);
        assertThat(keyBytes).hasSize(32); // 256-bit AES key

        // Check file permissions if POSIX is supported
        PosixFileAttributeView posixView = Files.getFileAttributeView(keyFile, PosixFileAttributeView.class);
        if (posixView != null) {
            String perms = PosixFilePermissions.toString(Files.getPosixFilePermissions(keyFile));
            assertThat(perms).isEqualTo("rw-------");
        }

        // Second instance reusing the keyfile
        SecretBox box2 = new SecretBox(tempDir);
        assertThat(box2.decrypt(ciphertext)).isEqualTo("persistent-secret");

        // Keyfile was not overwritten or regenerated
        assertThat(Files.readAllBytes(keyFile)).isEqualTo(keyBytes);
    }
}
