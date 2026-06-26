package com.coin.webcointrader.common.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 공통 페이징 응답 래퍼.
 * Spring Data의 Page 객체를 프론트엔드에 전달하기 위한 DTO로 변환한다.
 *
 * @param <T> 응답 데이터 타입
 */
@Getter
@Builder
@AllArgsConstructor
public class PageResponse<T> {
    private final List<T> content;       // 현재 페이지 데이터
    private final int page;              // 현재 페이지 번호 (0-based)
    private final int size;              // 페이지 크기
    private final long totalElements;    // 전체 데이터 수
    private final int totalPages;        // 전체 페이지 수
    private final boolean last;          // 마지막 페이지 여부

    /**
     * Spring Data Page 객체를 PageResponse로 변환한다.
     *
     * @param page      Spring Data Page 객체
     * @param converter 엔티티 → 응답 DTO 변환 함수
     * @param <E>       엔티티 타입
     * @param <T>       응답 DTO 타입
     * @return PageResponse 인스턴스
     */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> converter) {
        List<T> content = page.getContent().stream()
                .map(converter)
                .toList();

        return PageResponse.<T>builder()
                .content(content)
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }
}
