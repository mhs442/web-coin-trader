package com.coin.webcointrader.common.autotrade.entity;

import com.coin.webcointrader.common.enums.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SideListConverterTest {

    private final SideListConverter converter = new SideListConverter();

    @Test
    @DisplayName("Side 목록을 순서대로 쉼표 구분 문자열로 변환한다")
    void toDatabaseColumn_joinsInOrder() {
        assertThat(converter.convertToDatabaseColumn(List.of(Side.LONG, Side.SHORT, Side.LONG)))
                .isEqualTo("LONG,SHORT,LONG");
    }

    @Test
    @DisplayName("쉼표 구분 문자열을 순서를 보존한 Side 목록으로 변환한다")
    void toEntityAttribute_splitsInOrder() {
        assertThat(converter.convertToEntityAttribute("SHORT,LONG,SHORT"))
                .containsExactly(Side.SHORT, Side.LONG, Side.SHORT);
    }

    @Test
    @DisplayName("변환 결과 목록은 수정 가능하다 (엔티티에서 add/clear 사용)")
    void toEntityAttribute_returnsMutableList() {
        List<Side> sides = converter.convertToEntityAttribute("LONG");
        sides.add(Side.SHORT);
        assertThat(sides).containsExactly(Side.LONG, Side.SHORT);
    }

    @Test
    @DisplayName("null 또는 빈 목록은 빈 문자열로 저장한다")
    void toDatabaseColumn_nullOrEmpty() {
        assertThat(converter.convertToDatabaseColumn(null)).isEmpty();
        assertThat(converter.convertToDatabaseColumn(new ArrayList<>())).isEmpty();
    }

    @Test
    @DisplayName("null 또는 공백 문자열은 빈 목록으로 읽는다")
    void toEntityAttribute_nullOrBlank() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute(" ")).isEmpty();
    }
}
