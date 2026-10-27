package com.coin.webcointrader.common;

import com.coin.webcointrader.common.account.client.ExchangeAccountClient;
import com.coin.webcointrader.common.market.client.ExchangeMarketClient;
import com.coin.webcointrader.common.trade.client.ExchangeTradeClient;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 거래소 클라이언트 레지스트리.
 * ExchangeType → 시세/거래/계정 클라이언트 매핑을 관리하며, 거래소별 구현체 조회를 제공한다.
 * Spring이 주입한 구현체 목록으로 자동 초기화된다.
 */
@Component
@Slf4j
public class ExchangeClientRegistry {

    private final Map<ExchangeType, ExchangeMarketClient> markets;     // 시세 클라이언트
    private final Map<ExchangeType, ExchangeTradeClient> trades;       // 거래 클라이언트
    private final Map<ExchangeType, ExchangeAccountClient> accounts;   // 계정 클라이언트

    public ExchangeClientRegistry(List<ExchangeMarketClient> marketClients,
                                  List<ExchangeTradeClient> tradeClients,
                                  List<ExchangeAccountClient> accountClients) {
        markets = index(marketClients, ExchangeMarketClient::getExchangeType, "시세");
        trades = index(tradeClients, ExchangeTradeClient::getExchangeType, "거래");
        accounts = index(accountClients, ExchangeAccountClient::getExchangeType, "계정");
    }

    /**
     * 구현체 목록을 거래소 타입별로 색인한다.
     *
     * @param clients 구현체 목록
     * @param typeOf  구현체의 거래소 타입 추출 함수
     * @param label   로그용 구분명
     * @return 거래소 타입 → 구현체 맵
     */
    private static <T> Map<ExchangeType, T> index(List<T> clients, Function<T, ExchangeType> typeOf, String label) {
        Map<ExchangeType, T> map = new EnumMap<>(ExchangeType.class);
        clients.forEach(client -> {
            map.put(typeOf.apply(client), client);
            log.info("[ExchangeClientRegistry] {} 클라이언트 등록: {}", label, typeOf.apply(client));
        });
        return map;
    }

    /**
     * 맵에서 거래소 구현체를 꺼낸다.
     *
     * @throws CustomException 등록되지 않은 거래소 타입인 경우 (EXCHANGE_NOT_SUPPORTED)
     */
    private static <T> T require(Map<ExchangeType, T> map, ExchangeType exchangeType) {
        T client = map.get(exchangeType);
        if (client == null) {
            throw new CustomException(ExceptionMessage.EXCHANGE_NOT_SUPPORTED,
                    exchangeType.name() + " 거래소는 지원하지 않습니다.");
        }
        return client;
    }

    /**
     * 거래소 시세 클라이언트를 반환한다.
     *
     * @param exchangeType 거래소 타입
     * @return 시세 클라이언트
     * @throws CustomException 등록되지 않은 거래소 타입인 경우 (EXCHANGE_NOT_SUPPORTED)
     */
    public ExchangeMarketClient market(ExchangeType exchangeType) {
        return require(markets, exchangeType);
    }

    /**
     * 거래소 거래 클라이언트를 반환한다.
     *
     * @param exchangeType 거래소 타입
     * @return 거래 클라이언트
     * @throws CustomException 등록되지 않은 거래소 타입인 경우 (EXCHANGE_NOT_SUPPORTED)
     */
    public ExchangeTradeClient trade(ExchangeType exchangeType) {
        return require(trades, exchangeType);
    }

    /**
     * 거래소 계정 클라이언트를 반환한다.
     *
     * @param exchangeType 거래소 타입
     * @return 계정 클라이언트
     * @throws CustomException 등록되지 않은 거래소 타입인 경우 (EXCHANGE_NOT_SUPPORTED)
     */
    public ExchangeAccountClient account(ExchangeType exchangeType) {
        return require(accounts, exchangeType);
    }

    /**
     * 시세·거래·계정 클라이언트가 모두 등록된 거래소 목록을 반환한다. (화면 노출용)
     * 구현 중이라 일부 클라이언트만 있는 거래소는 제외한다.
     *
     * @return 지원 거래소 목록 (ExchangeType 선언 순서)
     */
    public List<ExchangeType> supportedTypes() {
        return Arrays.stream(ExchangeType.values())
                .filter(type -> markets.containsKey(type) && trades.containsKey(type) && accounts.containsKey(type))
                .toList();
    }

    /**
     * 등록된 모든 시세 클라이언트를 반환한다. (스트림 리스너 등록·구독 동기화용)
     *
     * @return 시세 클라이언트 컬렉션
     */
    public Collection<ExchangeMarketClient> markets() {
        return markets.values();
    }

    /**
     * 등록된 모든 거래 클라이언트를 반환한다. (청산 리스너 등록·private 스트림 동기화용)
     *
     * @return 거래 클라이언트 컬렉션
     */
    public Collection<ExchangeTradeClient> trades() {
        return trades.values();
    }
}
