package com.coin.webcointrader.common.trade.service;

import com.coin.webcointrader.common.trade.dto.DailyProfitLossDTO;
import com.coin.webcointrader.common.trade.dto.ProfitLossSummaryDTO;
import com.coin.webcointrader.common.trade.dto.SymbolProfitLossDTO;
import com.coin.webcointrader.common.trade.dto.WinLossCountDTO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 투자 히스토리 집계 쿼리의 Object[] 결과를 DTO로 변환하는 공용 매퍼.
 * MAIN/SIM Repository가 동일한 집계 쿼리 형태를 가지므로 {@link HistoryService} 구현체들이 함께 사용한다.
 */
public final class HistoryAggregateMapper {

    private HistoryAggregateMapper() {
    }

    /** [수익 합, 손실 합] 단일 행 → DTO. 행이 없으면 0/0. */
    public static ProfitLossSummaryDTO toSummary(List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return new ProfitLossSummaryDTO(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        Object[] row = rows.get(0);
        return new ProfitLossSummaryDTO(toBigDecimal(row[0]), toBigDecimal(row[1]));
    }

    /** [승, 패, 전체] 단일 행 → DTO. 행이 없거나 SUM 결과가 null(데이터 없음)이면 0으로 처리. */
    public static WinLossCountDTO toWinLoss(List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return new WinLossCountDTO(0, 0, 0);
        }
        Object[] row = rows.get(0);
        return new WinLossCountDTO(toLong(row[0]), toLong(row[1]), toLong(row[2]));
    }

    /** [날짜, 손익 합] 다건 → DTO 목록. */
    public static List<DailyProfitLossDTO> toDaily(List<Object[]> rows) {
        return rows.stream()
                .map(r -> new DailyProfitLossDTO(String.valueOf(r[0]), toBigDecimal(r[1])))
                .toList();
    }

    /** [심볼, 손익 합] 다건 → DTO 목록. */
    public static List<SymbolProfitLossDTO> toSymbol(List<Object[]> rows) {
        return rows.stream()
                .map(r -> new SymbolProfitLossDTO(String.valueOf(r[0]), toBigDecimal(r[1])))
                .toList();
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        return o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    private static long toLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }
}
