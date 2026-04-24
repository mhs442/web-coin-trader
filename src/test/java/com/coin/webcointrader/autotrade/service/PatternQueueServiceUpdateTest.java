package com.coin.webcointrader.autotrade.service;

import com.coin.webcointrader.autotrade.dto.AddPatternRequest;
import com.coin.webcointrader.autotrade.dto.UpdatePatternRequest;
import com.coin.webcointrader.autotrade.repository.PatternQueueRepository;
import com.coin.webcointrader.common.entity.*;
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
        // 기존 단계 1개 추가
        PatternStep oldStep = new PatternStep();
        oldStep.setStepLevel(1);
        existing.getSteps().add(oldStep);

        UpdatePatternRequest request = makeUpdateRequest(new BigDecimal("2.5"), 2);

        given(patternQueueRepository.findById(queueId)).willReturn(Optional.of(existing));
        given(patternQueueRepository.save(any(PatternQueue.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        PatternQueue result = patternQueueService.updateQueue(userId, queueId, request);

        // then
        assertThat(result.getTriggerRate()).isEqualByComparingTo(new BigDecimal("2.5"));
        // 기존 단계가 교체되어 2단계로 변경됨
        assertThat(result.getSteps()).hasSize(2);
        assertThat(result.getSteps().get(0).getStepLevel()).isEqualTo(1);
        assertThat(result.getSteps().get(0).getPatterns()).hasSize(1);
        // 블록 구조 검증 (조건 블록 1개 + 리프 블록 1개)
        assertThat(result.getSteps().get(0).getPatterns().get(0).getBlocks()).hasSize(2);
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
            PatternStep s = new PatternStep();
            s.setStepLevel(i);
            existing.getSteps().add(s);
        }

        UpdatePatternRequest request = makeUpdateRequest(new BigDecimal("1.5"), 1);

        given(patternQueueRepository.findById(queueId)).willReturn(Optional.of(existing));
        given(patternQueueRepository.save(any(PatternQueue.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        PatternQueue result = patternQueueService.updateQueue(userId, queueId, request);

        // then
        assertThat(result.getSteps()).hasSize(1);
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
        request.setSteps(List.of());

        assertThatThrownBy(() -> patternQueueService.updateQueue(1L, 10L, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.EMPTY_STEPS.getMessage());
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    /** 비활성 PatternQueue 생성 */
    private PatternQueue makeInactiveQueue(Long id, Long userId, String symbol, BigDecimal triggerRate) {
        PatternQueue queue = new PatternQueue();
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

        List<AddPatternRequest.StepRequest> steps = new ArrayList<>();
        for (int i = 1; i <= stepCount; i++) {
            steps.add(makeStepRequest(i));
        }
        request.setSteps(steps);
        return request;
    }

    private AddPatternRequest.StepRequest makeStepRequest(int stepOrder) {
        AddPatternRequest.StepRequest step = new AddPatternRequest.StepRequest();
        step.setStepOrder(stepOrder);
        step.setPatterns(List.of(makePatternRequest()));
        return step;
    }

    private AddPatternRequest.PatternRequest makePatternRequest() {
        AddPatternRequest.PatternRequest pattern = new AddPatternRequest.PatternRequest();
        pattern.setAmount(new BigDecimal("10"));
        pattern.setLeverage(5);
        pattern.setStopLossRate(new BigDecimal("1.0"));
        pattern.setTakeProfitRate(new BigDecimal("5.0"));
        pattern.setConditionBlocks(List.of(makeBlockRequest("LONG", 1, false)));
        pattern.setLeafBlock(makeBlockRequest("LONG", 2, true));
        return pattern;
    }

    private AddPatternRequest.BlockRequest makeBlockRequest(String side, int blockOrder, boolean isLeaf) {
        AddPatternRequest.BlockRequest block = new AddPatternRequest.BlockRequest();
        block.setSide(side);
        block.setBlockOrder(blockOrder);
        block.setIsLeaf(isLeaf);
        return block;
    }
}
