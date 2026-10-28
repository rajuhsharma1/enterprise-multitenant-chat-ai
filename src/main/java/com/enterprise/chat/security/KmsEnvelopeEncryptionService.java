package com.enterprise.chat.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;

@Service
public class KmsEnvelopeEncryptionService {
  public record Envelope(
      int version, String keyArn, String wrappedKey, String iv, String ciphertext) {}

  private record DataKey(byte[] plaintext, String wrapped) {}

  private final Cache<String, DataKey> encryptKeys =
      Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(Duration.ofMinutes(5)).build();
  private final Cache<String, byte[]> decryptKeys =
      Caffeine.newBuilder().maximumSize(4000).expireAfterWrite(Duration.ofMinutes(5)).build();
  private final KmsClient kms;
  private final ObjectMapper json;
  private final SecureRandom random = new SecureRandom();

  public KmsEnvelopeEncryptionService(KmsClient kms, ObjectMapper json) {
    this.kms = kms;
    this.json = json;
  }

  public String encrypt(UUID org, String keyArn, String purpose, String plaintext) {
    try {
      DataKey key =
          encryptKeys.get(
              org + ":" + keyArn,
              k -> {
                var response =
                    kms.generateDataKey(
                        GenerateDataKeyRequest.builder()
                            .keyId(keyArn)
                            .keySpec(DataKeySpec.AES_256)
                            .encryptionContext(Map.of("org_id", org.toString()))
                            .build());
                return new DataKey(
                    response.plaintext().asByteArray(),
                    Base64.getEncoder().encodeToString(response.ciphertextBlob().asByteArray()));
              });
      byte[] iv = new byte[12];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(key.plaintext(), "AES"),
          new GCMParameterSpec(128, iv));
      cipher.updateAAD((org + ":" + purpose).getBytes(StandardCharsets.UTF_8));
      return json.writeValueAsString(
          new Envelope(
              1,
              keyArn,
              key.wrapped(),
              Base64.getEncoder().encodeToString(iv),
              Base64.getEncoder()
                  .encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)))));
    } catch (Exception e) {
      throw new IllegalStateException("Encryption failed", e);
    }
  }

  public String decrypt(UUID org, String expectedKeyArn, String purpose, String payload) {
    try {
      Envelope e = json.readValue(payload, Envelope.class);
      if (e.version() != 1 || !e.keyArn().equals(expectedKeyArn))
        throw new SecurityException("Envelope key mismatch");
      byte[] key =
          decryptKeys.get(
              org + ":" + e.keyArn() + ":" + e.wrappedKey(),
              k ->
                  kms.decrypt(
                          DecryptRequest.builder()
                              .keyId(e.keyArn())
                              .encryptionContext(Map.of("org_id", org.toString()))
                              .ciphertextBlob(
                                  SdkBytes.fromByteArray(
                                      Base64.getDecoder().decode(e.wrappedKey())))
                              .build())
                      .plaintext()
                      .asByteArray());
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(128, Base64.getDecoder().decode(e.iv())));
      cipher.updateAAD((org + ":" + purpose).getBytes(StandardCharsets.UTF_8));
      return new String(
          cipher.doFinal(Base64.getDecoder().decode(e.ciphertext())), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Decryption failed", e);
    }
  }
}
