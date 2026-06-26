package com.coin.webcointrader.common.exchange;

import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 거래소 어댑터 레지스트리.
 * ExchangeType → ExchangeAdapter 매핑을 관리하며, 각 거래소별 어댑터 조회를 제공한다.
 * Spring이 주입한 ExchangeAdapter 구현체 목록으로 자동 초기화된다.
 */
@Component
@Slf4j
public class ExchangeAdapterRegistry {

    private final Map<ExchangeType, ExchangeAdapter> registry;

    public ExchangeAdapterRegistry(List<ExchangeAdapter> adapters) {
        registry = new EnumMap<>(ExchangeType.class);
        adapters.forEach(adapter -> {
            registry.put(adapter.getExchangeType(), adapter);
            log.info("[ExchangeAdapterRegistry] 어댑터 등록: {}", adapter.getExchangeType());
        });
    }

    /**
     * 주어진 거래소 타입의 어댑터를 반환한다.
     *
     * @param exchangeType 거래소 타입
     * @return 해당 거래소 어댑터
     * @throws CustomException 등록되지 않은 거래소 타입인 경우
     */
    public ExchangeAdapter get(ExchangeType exchangeType) {
        ExchangeAdapter adapter = registry.get(exchangeType);
        if (adapter == null) {
            throw new CustomException(ExceptionMessage.EXCHANGE_NOT_SUPPORTED,
                    exchangeType.name() + " 거래소는 지원하지 않습니다.");
        }
        return adapter;
    }

    /**
     * 등록된 모든 어댑터를 반환한다.
     *
     * @return 어댑터 컬렉션
     */
    public Collection<ExchangeAdapter> getAll() {
        return registry.values();
    }
}
