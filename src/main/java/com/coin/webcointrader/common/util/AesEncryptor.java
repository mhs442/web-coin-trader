package com.coin.webcointrader.common.util;

import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class AesEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;       // GCM 권장 IV 길이 (12byte)
    private static final int TAG_LENGTH = 128;     // 인증 태그 길이 (128bit)

    @Value("${aes.encryption.key}")
    private String encryptionKey;

    @PostConstruct
    private void validateKey(){
        if(encryptionKey == null || encryptionKey.getBytes(StandardCharsets.UTF_8).length != 32){
            throw new CustomException(ExceptionMessage.INVALID_ENCRYPTION_KEY);
        }
    }

    /**
     * AES-GCM 암호화
     * - SecureRandom으로 매번 랜덤 IV 생성
     * - 결과: Base64( IV(12byte) + 암호문 + 인증태그 )
     *
     * @param plainText 암호화할 평문
     * @return Base64 인코딩된 암호문 (IV 포함)
     */
    public String encrypt(String plainText) {
        try {
            // 랜덤 IV 생성
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            SecretKeySpec keySpec = new SecretKeySpec(
                    encryptionKey.getBytes(StandardCharsets.UTF_8), "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH, iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // IV + 암호문을 합쳐서 저장
            ByteBuffer buffer = ByteBuffer.allocate(IV_LENGTH + encrypted.length);
            buffer.put(iv);
            buffer.put(encrypted);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new CustomException(ExceptionMessage.ENCRYPTION_ERROR, e);
        }
    }

    /**
     * AES-GCM 복호화
     * - 암호문 앞 12byte에서 IV 추출 후 복호화
     * - 인증 태그 검증 실패 시 예외 발생 (변조 감지)
     *
     * @param encryptedText Base64 인코딩된 암호문 (IV 포함)
     * @return 복호화된 평문
     */
    public String decrypt(String encryptedText) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encryptedText);

            // IV 추출
            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);

            // 암호문 추출
            byte[] cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);

            SecretKeySpec keySpec = new SecretKeySpec(
                    encryptionKey.getBytes(StandardCharsets.UTF_8), "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH, iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
            byte[] decrypted = cipher.doFinal(cipherText);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new CustomException(ExceptionMessage.DECRYPTION_ERROR, e);
        }
    }
}
