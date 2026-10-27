package com.coin.webcointrader.binance.common;

import com.coin.webcointrader.binance.common.dto.BinanceErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Response;
import feign.codec.ErrorDecoder;
import lombok.RequiredArgsConstructor;

import java.io.InputStream;

/**
 * Binance 에러 응답 디코더.
 * HTTP 4xx/5xx 응답 본문 {code, msg} 를 BinanceApiException 으로 변환한다.
 */
@RequiredArgsConstructor
public class BinanceErrorDecoder implements ErrorDecoder {

    private final ObjectMapper objectMapper;

    @Override
    public Exception decode(String methodKey, Response response) {
        BinanceErrorResponse error = parse(response);
        // {code, msg} 형식이면 거래소 값 그대로, 아니면(게이트웨이 HTML·빈 본문 등) HTTP 상태·사유로 대체
        if (error != null && error.getCode() != null) {
            return new BinanceApiException(error.getCode(), error.getMsg());
        }
        return new BinanceApiException(response.status(), response.reason());
    }

    /** 본문을 {code, msg} 로 파싱한다. 본문이 없거나 형식이 다르면 null */
    private BinanceErrorResponse parse(Response response) {
        if (response.body() == null) return null;
        try (InputStream body = response.body().asInputStream()) {
            return objectMapper.readValue(body, BinanceErrorResponse.class);
        } catch (Exception e) {
            return null;
        }
    }
}
