package dev.hendrikhoemberg.webtesthelper.catalog;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

@Component
public class SecretBox {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    @Autowired
    public SecretBox(@Value("${webtesthelper.data-dir:./data}") String dataDir) {
        this(Path.of(dataDir));
    }

    public SecretBox(Path dataDir) {
        try {
            Path keyFile = dataDir.resolve("keyfile");
            if (Files.exists(keyFile)) {
                byte[] encoded = Files.readAllBytes(keyFile);
                this.key = new SecretKeySpec(encoded, "AES");
            } else {
                if (keyFile.getParent() != null) {
                    Files.createDirectories(keyFile.getParent());
                }
                KeyGenerator keyGen = KeyGenerator.getInstance("AES");
                keyGen.init(256);
                SecretKey generated = keyGen.generateKey();
                byte[] encoded = generated.getEncoded();

                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                try {
                    FileAttribute<Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(perms);
                    Files.createFile(keyFile, attr);
                    Files.write(keyFile, encoded);
                } catch (UnsupportedOperationException e) {
                    Files.write(keyFile, encoded);
                } catch (FileAlreadyExistsException e) {
                    // In case another thread/process created it concurrently
                    encoded = Files.readAllBytes(keyFile);
                    generated = new SecretKeySpec(encoded, "AES");
                }

                try {
                    Files.setPosixFilePermissions(keyFile, perms);
                } catch (Exception ignored) {
                }

                this.key = generated;
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Failed to initialize SecretBox key", e);
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + ct.length)
                            .put(iv)
                            .put(ct)
                            .array()
            );
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored);
            if (combined.length < IV_BYTES) {
                throw new IllegalArgumentException("Ciphertext too short");
            }
            byte[] iv = new byte[IV_BYTES];
            byte[] ct = new byte[combined.length - IV_BYTES];
            ByteBuffer.wrap(combined).get(iv).get(ct);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}
