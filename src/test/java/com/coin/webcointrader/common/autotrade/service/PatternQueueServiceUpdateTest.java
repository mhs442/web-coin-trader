package com.coin.webcointrader.common.autotrade.service;

import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.autotrade.entity.PatternQueue;
import com.coin.webcointrader.common.autotrade.entity.Pattern;
import com.coin.webcointrader.common.autotrade.dto.AddPatternRequest;
import com.coin.webcointrader.common.autotrade.dto.UpdatePatternRequest;
import com.coin.webcointrader.common.autotrade.repository.PatternQueueRepository;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.TradeMode;
import com.coin.webcointrader.common.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class PatternQueueServiceUpdateTest {

    @InjectMocks
    private PatternQueueService patternQueueService;

    @Mock
    private PatternQueueRepository patternQueueRepository;

    // ─────────────────────────────────────────────
    // updateQueue 성공
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("updateQueue: 유효한 요청이면 triggerRate와 단계/패턴/블록을 교체한다")
    void updateQueue_success() {
        // given
        Long userId = 1L;
        Long queueId = 10L;

        PatternQueue existing = makeInactiveQueue(queueId, userId, "BTCUSDT", new BigDecimal("1.0"));
        // 기존 1단계 패턴 1개 추가
        existing.getPatterns().add(makeOldPattern(existing, 1));

        UpdatePatternRequest request = makeUpdateRequest(new BigDecimal("2.5"), 2);

        given(patternQueueRepository.findById(queueId)).willReturn(Optional.of(existing));
        given(patternQueueRepository.save(any(PatternQueue.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        PatternQueue result = patternQueueService.updateQueue(userId, queueId, request);

        // then
        assertThat(result.getTriggerRate()).isEqualByComparingTo(new BigDecimal("2.5"));
        // 기존 패턴이 교체되어 2단계 x 양방향 패턴 2개 (LONG 시작 + SHORT 시작)
        assertThat(result.getPatterns()).hasSize(4);
        assertThat(result.getPatterns()).allMatch(p -> p.getQueue() == result);
        assertThat(result.getPatterns().get(0).getStepLevel()).isEqualTo(1);
        assertThat(result.getPatterns().get(2).getStepLevel()).isEqualTo(2);
        // 블록 구조 검증 (조건 블록 1개 + 리프 블록 1개)
        assertThat(result.getPatterns().get(0).getBlocks()).hasSize(2);
    }

    @Test
    @DisplayName("updateQueue: 거래량 급변 중지 on/off 설정을 교체한다")
    void updateQueue_replacesVolumeStopEnabled() {
        Long userId = 1L;
        Long queueId = 10L;
        PatternQueue existing = makeInactiveQueue(queueId, userId, "BTCUSDT", new BigDecimal("1.0"));
        existing.getPatterns().add(makeOldPattern(existing, 1));
        UpdatePatternRequest request = makeUpdateRequest(new BigDecimal("2.5"), 1);
        request.setVolumeStopEnabled(true);
        given(patternQueueRepository.findById(queueId)).willReturn(Optional.of(existing));
        given(patternQueueRepository.save(any(PatternQueue.class))).willAnswer(inv -> inv.getArgument(0));

        PatternQueue result = patternQueueService.updateQueue(userId, queueId, request);

        assertThat(result.isVolumeStopEnabled()).isTrue();
    }

    @Test
    @DisplayName("updateQueue: 단계 1개짜리 요청으로 교체하면 단계가 1개로 줄어든다")
    void updateQueue_reduceSteps() {
        // given
        Long userId = 1L;
        Long queueId = 10L;

        PatternQueue existing = makeInactiveQueue(queueId, userId, "BTCUSDT", new BigDecimal("1.0"));
        // 기존 3단계 큐
        for (int i = 1; i <= 3; i++) {
            existing.getPatterns().add(makeOldPattern(existing, i));
        }

        UpdatePatternRequest request = makeUpdateRequest(new BigDecimal("1.5"), 1);

        given(patternQueueRepository.findById(queueId)).willReturn(Optional.of(existing));
        given(patternQueueRepository.save(any(PatternQueue.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        PatternQueue result = patternQueueService.updateQueue(userId, queueId, request);

        // then - 1단계 양방향 패턴 2개만 남는다
        assertThat(result.getPatterns()).hasSize(2);
        assertThat(result.getPatterns()).allMatch(p -> p.getStepLevel() == 1);
    }

    // ─────────────────────────────────────────────
    // updateQueue 실패
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("updateQueue: 존재하지 않는 큐면 QUEUE_NOT_FOUND 예외")
    void updateQueue_notFound() {
        given(patternQueueRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> patternQueueService.updateQueue(1L, 999L, makeUpdateRequest(new BigDecimal("1.0"), 1)))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.QUEUE_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("updateQueue: 다른 사용자의 큐면 QUEUE_UNAUTHORIZED 예외")
    void updateQueue_unauthorized() {
        PatternQueue other = makeInactiveQueue(10L, 99L, "BTCUSDT", new BigDecimal("1.0"));
        given(patternQueueRepository.findById(10L)).willReturn(Optional.of(other));

        assertThatThrownBy(() -> patternQueueService.updateQueue(1L, 10L, makeUpdateRequest(new BigDecimal("1.0"), 1)))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.QUEUE_UNAUTHORIZED.getMessage());
    }

    @Test
    @DisplayName("updateQueue: 활성화된 큐는 QUEUE_ACTIVE_CANNOT_UPDATE 예외")
    void updateQueue_activeQueue() {
        PatternQueue active = makeInactiveQueue(10L, 1L, "BTCUSDT", new BigDecimal("1.0"));
        active.setActive(true); // 활성화 상태

        given(patternQueueRepository.findById(10L)).willReturn(Optional.of(active));

        assertThatThrownBy(() -> patternQueueService.updateQueue(1L, 10L, makeUpdateRequest(new BigDecimal("1.0"), 1)))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.QUEUE_ACTIVE_CANNOT_UPDATE.getMessage());
    }

    @Test
    @DisplayName("updateQueue: 트리거 비율이 0 이하면 INVALID_TRIGGER_RATE 예외")
    void updateQueue_invalidTriggerRate() {
        PatternQueue existing = makeInactiveQueue(10L, 1L, "BTCUSDT", new BigDecimal("1.0"));
        given(patternQueueRepository.findById(10L)).willReturn(Optional.of(existing));

        UpdatePatternRequest request = makeUpdateRequest(BigDecimal.ZERO, 1);

        assertThatThrownBy(() -> patternQueueService.updateQueue(1L, 10L, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.INVALID_TRIGGER_RATE.getMessage());
    }

    @Test
    @DisplayName("updateQueue: 단계가 없으면 EMPTY_STEPS 예외")
    void updateQueue_emptySteps() {
        PatternQueue existing = makeInactiveQueue(10L, 1L, "BTCUSDT", new BigDecimal("1.0"));
        given(patternQueueRepository.findById(10L)).willReturn(Optional.of(existing));

        UpdatePatternRequest request = new UpdatePatternRequest();
        request.setTriggerRate(new BigDecimal("1.0"));
        request.setPatterns(List.of());

        assertThatThrownBy(() -> patternQueueService.updateQueue(1L, 10L, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.EMPTY_STEPS.getMessage());
    }

    @Test
    @DisplayName("updateQueue: 같은 단계 두 패턴의 시작 블록이 같으면 DUPLICATE_START_BLOCK 예외 (addQueue와 동일 검증)")
    void updateQueue_duplicateStartBlock() {
        PatternQueue existing = makeInactiveQueue(10L, 1L, "BTCUSDT", new BigDecimal("1.0"));
        given(patternQueueRepository.findById(10L)).willReturn(Optional.of(existing));

        // L:L 과 L:S → 둘 다 LONG 시작
        AddPatternRequest.PatternRequest secondLong = makePatternRequest(1);
        secondLong.setLeafBlock("SHORT");
        UpdatePatternRequest request = new UpdatePatternRequest();
        request.setTriggerRate(new BigDecimal("1.0"));
        request.setPatterns(List.of(makePatternRequest(1), secondLong));

        assertThatThrownBy(() -> patternQueueService.updateQueue(1L, 10L, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.DUPLICATE_START_BLOCK.getMessage());
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    /** 비활성 PatternQueue 생성 */
    private PatternQueue makeInactiveQueue(Long id, Long userId, String symbol, BigDecimal triggerRate) {
        PatternQueue queue = new PatternQueue();
        queue.setExchangeType(ExchangeType.BYBIT);
        queue.setId(id);
        queue.setUserId(userId);
        queue.setSymbol(symbol);
        queue.setTriggerRate(triggerRate);
        queue.setActive(false);
        queue.setTradeMode(TradeMode.MAIN);
        ReflectionTestUtils.setField(queue, "createdAt", java.time.LocalDateTime.now());
        return queue;
    }

    /** 지정한 단계 수만큼 단계를 가진 UpdatePatternRequest 생성 */
    private UpdatePatternRequest makeUpdateRequest(BigDecimal triggerRate, int stepCount) {
        UpdatePatternRequest request = new UpdatePatternRequest();
        request.setTriggerRate(triggerRate);

        List<AddPatternRequest.PatternRequest> patterns = new ArrayList<>();
        for (int i = 1; i <= stepCount; i++) {
            // 단계당 양방향 패턴 2개 강제 검증을 통과하기 위해 LONG/SHORT 두 패턴 생성
            patterns.add(makePatternRequest(i));
            patterns.add(makeShortPatternRequest(i));
        }
        request.setPatterns(patterns);
        return request;
    }

    private AddPatternRequest.PatternRequest makePatternRequest(int stepLevel) {
        AddPatternRequest.PatternRequest pattern = new AddPatternRequest.PatternRequest();
        pattern.setStepLevel(stepLevel);
        pattern.setAmount(new BigDecimal("10"));
        pattern.setLeverage(5);
        pattern.setStopLossRate(new BigDecimal("1.0"));
        pattern.setTakeProfitRate(new BigDecimal("5.0"));
        pattern.setConditionBlocks(List.of("LONG"));
        pattern.setLeafBlock("LONG");
        return pattern;
    }

    private AddPatternRequest.PatternRequest makeShortPatternRequest(int stepLevel) {
        AddPatternRequest.PatternRequest pattern = new AddPatternRequest.PatternRequest();
        pattern.setStepLevel(stepLevel);
        pattern.setAmount(new BigDecimal("10"));
        pattern.setLeverage(5);
        pattern.setStopLossRate(new BigDecimal("1.0"));
        pattern.setTakeProfitRate(new BigDecimal("5.0"));
        pattern.setConditionBlocks(List.of("SHORT"));
        pattern.setLeafBlock("SHORT");
        return pattern;
    }

    /**
     * 교체 대상이 될 기존 패턴 생성 (단계 레벨만 의미 있음)
     */
    private Pattern makeOldPattern(PatternQueue queue, int stepLevel) {
        Pattern pattern = new Pattern();
        pattern.setQueue(queue);
        pattern.setStepLevel(stepLevel);
        pattern.setPatternOrder(1);
        return pattern;
    }
}
