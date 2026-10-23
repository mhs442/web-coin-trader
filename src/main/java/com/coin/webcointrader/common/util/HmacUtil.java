package com.coin.webcointrader.common.util;

import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * HMAC 서명 유틸.
 * 서명 대상 문자열 조립 규칙(REST/WS)은 호출부가 담당하고, 이 클래스는 HMAC 계산만 수행한다.
 */
public final class HmacUtil {

    private HmacUtil() {
    }

    /**
     * HMAC-SHA256 서명을 16진수 소문자 문자열로 생성한다.
     *
     * @param secret 서명에 사용할 비밀키 (API Secret)
     * @param data   서명할 데이터 문자열
     * @return 16진수 소문자로 인코딩된 서명 (64자)
     * @throws CustomException 서명 생성 실패 시 (SIGNATURE_GENERATION_FAILED)
     */
    public static String sha256Hex(String secret, String data) {
        try {
            Mac sha256Hmac = Mac.getInstance("HmacSHA256");
            sha256Hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = sha256Hmac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder();
            for (byte b : bytes) hash.append(String.format("%02x", b));
            return hash.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new CustomException(ExceptionMessage.SIGNATURE_GENERATION_FAILED, e);
        }
    }
}
