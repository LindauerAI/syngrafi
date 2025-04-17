import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Handles simple AES/GCM encryption/decryption for API keys.
 * Stores the key in a file.
 * Note: This provides basic obfuscation, not strong security against
 * an attacker with file system access.
 */
public class ApiKeyEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int AES_KEY_SIZE = 128; // 128, 192, or 256
    private static final int GCM_IV_LENGTH = 12; // Standard IV length for GCM
    private static final int GCM_TAG_LENGTH = 128; // Authentication tag length

    private SecretKey secretKey;
    private final Path keyFilePath;

    public ApiKeyEncryptor(Path keyFilePath) {
        this.keyFilePath = keyFilePath;
        loadOrGenerateKey();
    }

    private void loadOrGenerateKey() {
        try {
            if (Files.exists(keyFilePath)) {
                byte[] keyBytes = Base64.getDecoder().decode(Files.readString(keyFilePath, StandardCharsets.UTF_8));
                secretKey = new SecretKeySpec(keyBytes, "AES");
                // System.out.println("Loaded encryption key from: " + keyFilePath);
            } else {
                // System.out.println("Generating new encryption key...");
                KeyGenerator keyGen = KeyGenerator.getInstance("AES");
                keyGen.init(AES_KEY_SIZE);
                secretKey = keyGen.generateKey();
                String encodedKey = Base64.getEncoder().encodeToString(secretKey.getEncoded());
                Files.writeString(keyFilePath, encodedKey, StandardCharsets.UTF_8);
                // System.out.println("Saved new encryption key to: " + keyFilePath);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("AES algorithm not available", e);
        } catch (Exception e) {
            throw new RuntimeException("Could not load or generate encryption key from " + keyFilePath, e);
        }
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return "";
        }
        if (secretKey == null) throw new IllegalStateException("Secret key not initialized.");

        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);

            byte[] cipherTextBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // Combine IV and Ciphertext: Base64(IV) + ":" + Base64(Ciphertext)
            String encodedIv = Base64.getEncoder().encodeToString(iv);
            String encodedCiphertext = Base64.getEncoder().encodeToString(cipherTextBytes);

            return encodedIv + ":" + encodedCiphertext;

        } catch (Exception e) {
            System.err.println("Encryption failed: " + e.getMessage());
            // Return empty string or throw exception? Returning empty for now.
            return ""; 
        }
    }

    public String decrypt(String encryptedData) {
        if (encryptedData == null || encryptedData.isEmpty() || !encryptedData.contains(":")) {
            return ""; // Return empty for null, empty, or invalid format
        }
        if (secretKey == null) throw new IllegalStateException("Secret key not initialized.");

        try {
            String[] parts = encryptedData.split(":", 2);
            if (parts.length != 2) return ""; // Invalid format

            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] cipherTextBytes = Base64.getDecoder().decode(parts[1]);

            // Ensure IV length matches expected GCM IV length
            if (iv.length != GCM_IV_LENGTH) {
                 System.err.println("Decryption failed: Incorrect IV length.");
                 return "";
            }

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);

            byte[] plainTextBytes = cipher.doFinal(cipherTextBytes);

            return new String(plainTextBytes, StandardCharsets.UTF_8);

        } catch (IllegalArgumentException e) {
            // Handle Base64 decoding errors
            System.err.println("Decryption failed (Base64 error): " + e.getMessage());
            return "";
        } catch (Exception e) {
            // Handles AEADBadTagException (tampering/wrong key) and others
            System.err.println("Decryption failed: " + e.getMessage());
            // Don't print stack trace for common decryption failures (wrong key/tampered data)
            // If debugging: e.printStackTrace(); 
            return ""; // Return empty string on decryption failure
        }
    }
} 