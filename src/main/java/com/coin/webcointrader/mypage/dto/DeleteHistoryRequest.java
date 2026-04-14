package com.coin.webcointrader.mypage.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 히스토리 선택 삭제 요청 DTO.
 * 삭제할 ID 목록과 거래 모드를 전달한다.
 */
@Getter
@Setter
public class DeleteHistoryRequest {
    private List<Long> ids;   // 삭제할 히스토리 ID 목록
    private String mode;      // 거래 모드 ("main" 또는 "sim")
}
