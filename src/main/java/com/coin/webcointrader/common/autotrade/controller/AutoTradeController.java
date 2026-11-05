package com.coin.webcointrader.common.autotrade.controller;

import com.coin.webcointrader.common.autotrade.entity.PatternQueue;
import com.coin.webcointrader.common.autotrade.dto.*;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.autotrade.service.AutoTradeService;
import com.coin.webcointrader.common.autotrade.service.PatternQueueService;
import com.coin.webcointrader.common.login.dto.UserDTO;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.TradeMode;
import com.coin.webcointrader.common.autotrade.entity.Pattern;
import com.coin.webcointrader.common.enums.Side;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 자동매매 REST API 컨트롤러.
 * 패턴 CRUD 및 자동매매 상태조회 엔드포인트를 제공한다.
 */
@RestController
@RequestMapping("/api/autotrade")
@RequiredArgsConstructor
public class AutoTradeController {
    private final PatternQueueService patternQueueService;
    private final AutoTradeService autoTradeService;

    /**
     * 큐 목록 조회
     *
     * @param symbol   코인 심볼
     * @param mode     거래 모드 ("main" 또는 "sim", 기본값 "main")
     * @param exchange 거래소 (필수, 대소문자 무시 — 미전달 시 EXCHANGE_TYPE_REQUIRED)
     * @param user     로그인 사용자
     * @return 패턴 큐 목록
     */
    @GetMapping("/patterns")
    public List<PatternQueueResponse> getPatterns(@RequestParam String symbol,
                                                  @RequestParam(defaultValue = "main") String mode,
                                                  @RequestParam(required = false) String exchange,
                                                  @AuthenticationPrincipal UserDTO user) {
        TradeMode tradeMode = TradeMode.SIM.getMode().equalsIgnoreCase(mode) ? TradeMode.SIM : TradeMode.MAIN;
        return patternQueueService.getQueues(user.getId(), ExchangeType.from(exchange), symbol, tradeMode).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 큐 추가
     *
     * @param request 패턴 큐 추가 요청
     * @param user    로그인 사용자
     * @return 저장된 패턴 큐 응답
     */
    @PostMapping("/patterns")
    public PatternQueueResponse addPattern(@RequestBody AddPatternRequest request,
                                           @AuthenticationPrincipal UserDTO user) {
        PatternQueue saved = patternQueueService.addQueue(user.getId(), request);
        return toResponse(saved);
    }

    /**
     * 큐 삭제 (삭제 후 자동매매 세션 동기화)
     *
     * @param id       큐 ID
     * @param symbol   코인 심볼
     * @param mode     거래 모드 ("main" 또는 "sim", 기본값 "main")
     * @param exchange 거래소 (필수, 대소문자 무시 — 미전달 시 EXCHANGE_TYPE_REQUIRED)
     * @param user     로그인 사용자
     * @return 삭제 결과
     */
    @DeleteMapping("/patterns/{id}")
    public Map<String, String> deletePattern(@PathVariable Long id,
                                             @RequestParam String symbol,
                                             @RequestParam(defaultValue = "main") String mode,
                                             @RequestParam(required = false) String exchange,
                                             @AuthenticationPrincipal UserDTO user) {
        TradeMode tradeMode = "sim".equalsIgnoreCase(mode) ? TradeMode.SIM : TradeMode.MAIN;
        ExchangeType exchangeType = ExchangeType.from(exchange);
        // [사용자 정책 2026-10-30] 활성 큐는 삭제 불가 — 청산보다 먼저 검사 (활성 큐가 청산만 되는 일 방지)
        patternQueueService.validateDeletable(user.getId(), id);
        // [사용자 정책 2026-10-29] 삭제 전 보유 포지션 청산 (실패 시 예외 → 삭제하지 않고 큐 유지)
        autoTradeService.closeQueuePosition(user.getId(), id);
        patternQueueService.deleteQueue(user.getId(), id);
        // 삭제 후 해당 거래소 세션 동기화
        autoTradeService.syncSession(user.getId(), exchangeType, symbol, tradeMode);
        return Map.of("status", "ok");
    }

    /**
     * 큐를 요청한 상태로 켜거나 끈다. (변경 시 자동매매 세션 동기화)
     * 이미 그 상태면 청산 없이(켜기는 세션 동기화만 다시 수행) 현재 상태를 반환한다.
     *
     * @param id      큐 ID
     * @param request 원하는 큐 상태
     * @param user    로그인 사용자
     * @return 패턴 큐 응답
     */
    @PatchMapping("/patterns/{id}/toggle")
    public PatternQueueResponse togglePattern(@PathVariable Long id,
                                              @RequestBody(required = false) TogglePatternRequest request,
                                              @AuthenticationPrincipal UserDTO user) {
        // 상태 미지정 요청은 거부 (기본값을 두면 실수로 끄기·청산이 실행될 수 있음)
        if (request == null || request.getActive() == null) {
            throw new CustomException(ExceptionMessage.TOGGLE_ACTIVE_REQUIRED);
        }
        boolean turnOn = request.getActive();
        PatternQueue queue = patternQueueService.getQueue(user.getId(), id);
        // 오래된 화면의 요청 등 이미 원하는 상태 → 청산 없이 현재 상태 반환 (켜기는 세션만 다시 동기화)
        if (queue.isActive() == turnOn) {
            if (!turnOn) {
                return toResponse(queue);
            }
            // DB만 켜지고 세션 생성에 실패한 경우를 복구 (동기화 자체는 주문을 내지 않음)
            // 단, 세션이 없어 새로 만들면 1단계부터 다시 돌고, 엔진이 저장 실패로 끈 큐는 재가동하지 않음
            autoTradeService.syncSession(user.getId(), queue.getExchangeType(), queue.getSymbol(), queue.getTradeMode());
            // 엔진이 끈 큐는 꺼진 상태로 응답 (레인 스레드가 끈 값은 요청의 엔티티에 반영되지 않아 DB에서 직접 확인)
            return toResponse(queue, patternQueueService.isQueueActive(id));
        }
        // [사용자 정책 2026-10-29] 끄기 전 보유 포지션 청산 (실패 시 예외 → 끄지 않고 큐 유지)
        if (!turnOn) {
            autoTradeService.closeQueuePosition(user.getId(), id);
        }
        PatternQueue toggled = patternQueueService.setActive(user.getId(), id, turnOn);
        autoTradeService.syncSession(user.getId(), toggled.getExchangeType(), toggled.getSymbol(), toggled.getTradeMode());
        return toResponse(toggled);
    }

    /**
     * 전체 큐 목록 조회 (심볼 무관 - 큐 가져오기 모달용)
     *
     * @param mode     거래 모드 ("main" 또는 "sim", 기본값 "main")
     * @param exchange 거래소 (대소문자 무시, 미전달 시 모든 거래소 — 조회 전용이라 기본 거래소 정책과 무관)
     * @param user     로그인 사용자
     * @return 전체 패턴 큐 목록
     */
    @GetMapping("/patterns/all")
    public List<PatternQueueResponse> getAllPatterns(@RequestParam(defaultValue = "main") String mode,
                                                     @RequestParam(required = false) String exchange,
                                                     @AuthenticationPrincipal UserDTO user) {
        TradeMode tradeMode = "sim".equalsIgnoreCase(mode) ? TradeMode.SIM : TradeMode.MAIN;
        // 큐 가져오기: 거래소 미지정이면 전체 거래소 조회 (조회 전용 — 등록·복사·주문은 거래소 필수 유지)
        ExchangeType exchangeType = (exchange == null || exchange.isBlank()) ? null : ExchangeType.from(exchange);
        return patternQueueService.getAllQueues(user.getId(), exchangeType, tradeMode).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 큐 수정 (triggerRate + steps 교체, 수정 후 자동매매 세션 동기화)
     *
     * @param id      큐 ID
     * @param request 수정 요청 (triggerRate, steps)
     * @param user    로그인 사용자
     * @return 수정된 패턴 큐 응답
     */
    @PutMapping("/patterns/{id}")
    public PatternQueueResponse updatePattern(@PathVariable Long id,
                                              @RequestBody UpdatePatternRequest request,
                                              @AuthenticationPrincipal UserDTO user) {
        PatternQueue updated = patternQueueService.updateQueue(user.getId(), id, request);
        autoTradeService.syncSession(user.getId(), updated.getExchangeType(), updated.getSymbol(), updated.getTradeMode());
        return toResponse(updated);
    }

    /**
     * 큐 복사 (원본 큐 구조를 대상 심볼로 복제)
     *
     * @param queueId 복사할 원본 큐 ID
     * @param request 복사 요청 (대상 심볼, 거래 모드, 대상 거래소)
     * @param user    로그인 사용자
     * @return 복사된 패턴 큐 응답
     */
    @PostMapping("/patterns/{queueId}/copy")
    public PatternQueueResponse copyPattern(@PathVariable Long queueId,
                                            @RequestBody CopyQueueRequest request,
                                            @AuthenticationPrincipal UserDTO user) {
        TradeMode tradeMode = "sim".equalsIgnoreCase(request.getMode()) ? TradeMode.SIM : TradeMode.MAIN;
        PatternQueue copied = patternQueueService.copyQueue(user.getId(), queueId, request.getSymbol(),
                request.getExchangeType(), tradeMode);
        return toResponse(copied);
    }

    /**
     * 자동매매 상태 조회
     *
     * @param symbol   코인 심볼
     * @param mode     거래 모드 ("main" 또는 "sim", 기본값 "main")
     * @param exchange 거래소 (필수, 대소문자 무시 — 미전달 시 EXCHANGE_TYPE_REQUIRED)
     * @param user     로그인 사용자
     * @return 자동매매 상태 응답
     */
    @GetMapping("/status")
    public AutoTradeStatusResponse getStatus(@RequestParam String symbol,
                                             @RequestParam(defaultValue = "main") String mode,
                                             @RequestParam(required = false) String exchange,
                                             @AuthenticationPrincipal UserDTO user) {
        TradeMode tradeMode = "sim".equalsIgnoreCase(mode) ? TradeMode.SIM : TradeMode.MAIN;
        return autoTradeService.getStatusResponse(user.getId(), ExchangeType.from(exchange), symbol, tradeMode);
    }

    // ─────────────────────────────────────────────
    // PatternQueue → PatternQueueResponse 변환
    // ─────────────────────────────────────────────

    private PatternQueueResponse toResponse(PatternQueue queue) {
        return toResponse(queue, queue.isActive());
    }

    private PatternQueueResponse toResponse(PatternQueue queue, boolean active) {
        // 패턴 순서는 엔티티 @OrderBy(stepLevel, patternOrder)를 그대로 따른다
        List<PatternQueueResponse.PatternResponse> patternResponses = queue.getPatterns().stream()
                .map(this::toPatternResponse)
                .toList();

        return PatternQueueResponse.builder()
                .id(queue.getId())
                .symbol(queue.getSymbol())
                .exchangeType(queue.getExchangeType().name())
                .active(active)
                .cycle(queue.isCycle())
                .volumeStopEnabled(queue.isVolumeStopEnabled())
                .volumeStopReserved(queue.getVolumeStopReason() != null)
                .triggerRate(queue.getTriggerRate())
                .patterns(patternResponses)
                .build();
    }

    private PatternQueueResponse.PatternResponse toPatternResponse(Pattern pattern) {
        // 블록 목록의 마지막 원소가 리프 블록, 나머지가 조건 블록
        List<String> sides = pattern.getBlocks().stream().map(Side::name).toList();

        return PatternQueueResponse.PatternResponse.builder()
                .id(pattern.getId())
                .stepLevel(pattern.getStepLevel())
                .patternOrder(pattern.getPatternOrder())
                .amount(pattern.getAmount())
                .leverage(pattern.getLeverage())
                .stopLossRate(pattern.getStopLossRate())
                .takeProfitRate(pattern.getTakeProfitRate())
                .conditionBlocks(sides.subList(0, sides.size() - 1))
                .leafBlock(sides.get(sides.size() - 1))
                .build();
    }
}
