package com.coin.webcointrader.autotrade.controller;

import com.coin.webcointrader.autotrade.dto.AddPatternRequest;
import com.coin.webcointrader.autotrade.dto.AutoTradeSessionDTO;
import com.coin.webcointrader.autotrade.dto.AutoTradeStatusResponse;
import com.coin.webcointrader.autotrade.dto.PatternQueueResponse;
import com.coin.webcointrader.autotrade.service.AutoTradeService;
import com.coin.webcointrader.autotrade.service.PatternQueueService;
import com.coin.webcointrader.common.dto.UserDTO;
import com.coin.webcointrader.common.entity.Queue;
import com.coin.webcointrader.common.entity.QueueStep;
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

    // 큐 목록 조회
    @GetMapping("/patterns")
    public List<PatternQueueResponse> getPatterns(@RequestParam String symbol,
                                                  @AuthenticationPrincipal UserDTO user) {
        return patternQueueService.getQueues(user.getId(), symbol).stream()
                .map(this::toResponse)
                .toList();
    }

    // 큐 추가
    @PostMapping("/patterns")
    public PatternQueueResponse addPattern(@RequestBody AddPatternRequest request,
                                           @AuthenticationPrincipal UserDTO user) {
        Queue saved = patternQueueService.addQueue(user.getId(), request);
        return toResponse(saved);
    }

    // 큐 삭제 (삭제 후 자동매매 세션 동기화)
    @DeleteMapping("/patterns/{id}")
    public Map<String, String> deletePattern(@PathVariable Long id,
                                             @RequestParam String symbol,
                                             @AuthenticationPrincipal UserDTO user) {
        patternQueueService.deleteQueue(user.getId(), id);
        autoTradeService.syncSession(user.getId(), symbol);
        return Map.of("status", "ok");
    }

    // 큐 활성화/비활성화 토글 (토글 후 자동매매 세션 동기화)
    @PatchMapping("/patterns/{id}/toggle")
    public PatternQueueResponse togglePattern(@PathVariable Long id,
                                              @AuthenticationPrincipal UserDTO user) {
        Queue toggled = patternQueueService.toggleActive(user.getId(), id);
        autoTradeService.syncSession(user.getId(), toggled.getSymbol());
        return toResponse(toggled);
    }

    // 자동매매 상태 조회
    @GetMapping("/status")
    public AutoTradeStatusResponse getStatus(@RequestParam String symbol,
                                             @AuthenticationPrincipal UserDTO user) {
        boolean active = autoTradeService.isActive(user.getId(), symbol);

        if (!active) {
            return AutoTradeStatusResponse.builder()
                    .active(false)
                    .build();
        }

        AutoTradeSessionDTO session = autoTradeService.getSession(user.getId(), symbol);
        Queue currentQueue = session.getQueues().get(session.getCurrentQueueIndex());

        return AutoTradeStatusResponse.builder()
                .active(true)
                .currentPattern(currentQueue.getSteps().stream()
                        .map(s -> s.getSide().name())
                        .reduce((a, b) -> a + "," + b)
                        .orElse("-"))
                .currentStep(session.getCurrentStepIndex())
                .previousPrice(session.getPreviousPrice())
                .recentLog(session.getTradeLog())
                .build();
    }

    // Queue → PatternQueueResponse 변환
    private PatternQueueResponse toResponse(Queue queue) {
        List<PatternQueueResponse.StepResponse> stepResponses = queue.getSteps().stream()
                .map(s -> PatternQueueResponse.StepResponse.builder()
                        .id(s.getId())
                        .stepOrder(s.getStepOrder())
                        .side(s.getSide().name())
                        .quantity(s.getQuantity().stripTrailingZeros().toPlainString())
                        .build())
                .toList();

        return PatternQueueResponse.builder()
                .id(queue.getId())
                .sortOrder(queue.getSortOrder())
                .useYn(queue.getUseYn())
                .steps(stepResponses)
                .build();
    }
}
