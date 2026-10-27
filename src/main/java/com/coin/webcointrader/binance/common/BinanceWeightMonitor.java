package com.coin.webcointrader.binance.common;

import com.coin.webcointrader.common.enums.LogMessage;
import feign.InvocationContext;
import feign.Response;
import feign.ResponseInterceptor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Map;

/**
 * Binance 응답 헤더의 최근 1분 사용 가중치(X-MBX-USED-WEIGHT-1M)를 확인해, 한도에 가까우면 경고 로그를 남긴다.
 * 한도를 넘으면 429(이후 반복 시 418 IP 차단)를 받으므로 미리 알아차리기 위한 용도이며, 호출을 막지는 않는다.
 */
@Slf4j
public class BinanceWeightMonitor implements ResponseInterceptor {

    private static final String USED_WEIGHT_HEADER = "X-MBX-USED-WEIGHT-1M";
    // IP당 분당 가중치 한도 (exchangeInfo rateLimits REQUEST_WEIGHT 예시값)
    private static final int WEIGHT_LIMIT_1M = 2400;
    // [사용자 정책 2026-10-27] 한도의 80% 이상이면 경고
    private static final int WARN_THRESHOLD = WEIGHT_LIMIT_1M * 80 / 100;

    @Override
    public Object intercept(InvocationContext invocationContext, Chain chain) throws Exception {
        warnIfHigh(invocationContext.response());
        return chain.next(invocationContext);
    }

    /**
     * 응답의 사용 가중치가 경고 기준 이상이면 경고 로그를 남긴다.
     *
     * @param response Binance 응답
     * @return 경고했으면 true (헤더가 없거나 숫자가 아니면 false)
     */
    boolean warnIfHigh(Response response) {
        Integer used = usedWeight(response.headers());
        if (used == null || used < WARN_THRESHOLD) {
            return false;
        }
        log.warn(LogMessage.BINANCE_WEIGHT_HIGH.getMessage(), used, WEIGHT_LIMIT_1M,
                response.request().httpMethod(), pathOf(response.request().url()));
        return true;
    }

    /** 헤더 이름은 대소문자 구분 없이 찾는다. */
    private static Integer usedWeight(Map<String, Collection<String>> headers) {
        for (Map.Entry<String, Collection<String>> header : headers.entrySet()) {
            if (!USED_WEIGHT_HEADER.equalsIgnoreCase(header.getKey()) || header.getValue().isEmpty()) {
                continue;
            }
            try {
                return Integer.parseInt(header.getValue().iterator().next().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 서명 등 쿼리가 로그에 남지 않도록 경로만 남긴다. */
    private static String pathOf(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }
}
