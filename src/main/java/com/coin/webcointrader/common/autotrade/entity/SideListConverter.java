package com.coin.webcointrader.common.autotrade.entity;

import com.coin.webcointrader.common.enums.Side;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 패턴 블록 방향 목록 ↔ 쉼표 구분 문자열 변환기.
 * 예: [LONG, SHORT, LONG] ↔ "LONG,SHORT,LONG" (순서 보존, 마지막 원소가 leaf 블록)
 */
@Converter
public class SideListConverter implements AttributeConverter<List<Side>, String> {

    private static final String DELIMITER = ",";

    @Override
    public String convertToDatabaseColumn(List<Side> sides) {
        // 빈 목록은 빈 문자열로 저장 (컬럼 NOT NULL)
        if (sides == null || sides.isEmpty()) {
            return "";
        }
        return sides.stream().map(Side::name).collect(Collectors.joining(DELIMITER));
    }

    @Override
    public List<Side> convertToEntityAttribute(String dbData) {
        // 엔티티에서 목록을 수정할 수 있도록 항상 가변 리스트로 반환
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(dbData.split(DELIMITER))
                .map(String::trim)
                .map(Side::valueOf)
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
