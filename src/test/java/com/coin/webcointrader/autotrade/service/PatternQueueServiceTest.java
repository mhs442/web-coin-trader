package com.coin.webcointrader.autotrade.service;

import com.coin.webcointrader.autotrade.dto.AddPatternRequest;
import com.coin.webcointrader.autotrade.repository.QueueRepository;
import com.coin.webcointrader.common.entity.Queue;
import com.coin.webcointrader.common.entity.QueueStep;
import com.coin.webcointrader.common.entity.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class PatternQueueServiceTest {

    @InjectMocks
    private PatternQueueService patternQueueService;

    @Mock
    private QueueRepository queueRepository;

    // ─────────────────────────────────────────────
    // getQueues
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getQueues: 사용자/심볼에 해당하는 큐 목록을 반환한다")
    void getQueues_success() {
        // given
        Long userId = 1L;
        String symbol = "BTCUSDT";
        Queue q1 = makeQueue(1L, userId, symbol, 0);
        Queue q2 = makeQueue(2L, userId, symbol, 1);

        given(queueRepository.findByUserIdAndSymbolAndDelYnOrderBySortOrderAsc(userId, symbol, "N"))
                .willReturn(List.of(q1, q2));

        // when
        List<Queue> result = patternQueueService.getQueues(userId, symbol);

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getSortOrder()).isEqualTo(0);
        assertThat(result.get(1).getSortOrder()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────
    // addQueue
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("addQueue: 유효한 요청이면 큐를 저장하고 반환한다")
    void addQueue_success() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeRequest("BTCUSDT",
                List.of(makeStep("LONG", "0.01"), makeStep("SHORT", "0.01")));

        given(queueRepository.countByUserIdAndSymbolAndDelYn(userId, "BTCUSDT", "N")).willReturn(0L);
        given(queueRepository.save(any(Queue.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        Queue result = patternQueueService.addQueue(userId, request);

        // then
        assertThat(result.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(result.getSteps()).hasSize(2);
        assertThat(result.getSteps().get(0).getSide()).isEqualTo(Side.LONG);
        assertThat(result.getSteps().get(1).getSide()).isEqualTo(Side.SHORT);
    }

    @Test
    @DisplayName("addQueue: 단계가 없으면 IllegalArgumentException 발생")
    void addQueue_emptySteps() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeRequest("BTCUSDT", List.of());

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(userId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1개 이상");
    }

    @Test
    @DisplayName("addQueue: 잘못된 side 값이면 IllegalArgumentException 발생")
    void addQueue_invalidSide() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeRequest("BTCUSDT",
                List.of(makeStep("INVALID", "0.01")));

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(userId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LONG 또는 SHORT");
    }

    @Test
    @DisplayName("addQueue: 수량이 0이하면 IllegalArgumentException 발생")
    void addQueue_zeroQuantity() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeRequest("BTCUSDT",
                List.of(makeStep("LONG", "0")));

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(userId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0보다 커야");
    }

    @Test
    @DisplayName("addQueue: 큐가 20개 이상이면 IllegalArgumentException 발생")
    void addQueue_maxLimit() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeRequest("BTCUSDT",
                List.of(makeStep("LONG", "0.01")));

        given(queueRepository.countByUserIdAndSymbolAndDelYn(userId, "BTCUSDT", "N")).willReturn(20L);

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(userId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20개");
    }

    // ─────────────────────────────────────────────
    // deleteQueue
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("deleteQueue: 본인 큐면 소프트 삭제 후 sortOrder를 재정렬한다")
    void deleteQueue_success() {
        // given
        Long userId = 1L;
        Queue target = makeQueue(1L, userId, "BTCUSDT", 0);
        Queue remaining = makeQueue(2L, userId, "BTCUSDT", 1);

        given(queueRepository.findById(1L)).willReturn(Optional.of(target));
        given(queueRepository.findByUserIdAndSymbolAndDelYnOrderBySortOrderAsc(userId, "BTCUSDT", "N"))
                .willReturn(List.of(remaining));
        given(queueRepository.save(any(Queue.class))).willAnswer(inv -> inv.getArgument(0));
        given(queueRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        // when
        patternQueueService.deleteQueue(userId, 1L);

        // then
        assertThat(target.getDelYn()).isEqualTo("Y");
        assertThat(remaining.getSortOrder()).isEqualTo(0); // 재정렬됨
    }

    @Test
    @DisplayName("deleteQueue: 다른 사용자의 큐를 삭제하면 IllegalArgumentException 발생")
    void deleteQueue_unauthorized() {
        // given
        Long ownerId = 1L;
        Long attackerId = 2L;
        Queue queue = makeQueue(1L, ownerId, "BTCUSDT", 0);

        given(queueRepository.findById(1L)).willReturn(Optional.of(queue));

        // when & then
        assertThatThrownBy(() -> patternQueueService.deleteQueue(attackerId, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("권한");
    }

    // ─────────────────────────────────────────────
    // toggleActive
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("toggleActive: Y → N으로 토글된다")
    void toggleActive_yToN() {
        // given
        Long userId = 1L;
        Queue queue = makeQueue(1L, userId, "BTCUSDT", 0);
        queue.setUseYn("Y");

        given(queueRepository.findById(1L)).willReturn(Optional.of(queue));
        given(queueRepository.save(any(Queue.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        Queue result = patternQueueService.toggleActive(userId, 1L);

        // then
        assertThat(result.getUseYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("toggleActive: N → Y로 토글된다")
    void toggleActive_nToY() {
        // given
        Long userId = 1L;
        Queue queue = makeQueue(1L, userId, "BTCUSDT", 0);
        queue.setUseYn("N");

        given(queueRepository.findById(1L)).willReturn(Optional.of(queue));
        given(queueRepository.save(any(Queue.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        Queue result = patternQueueService.toggleActive(userId, 1L);

        // then
        assertThat(result.getUseYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("toggleActive: 다른 사용자의 큐를 토글하면 IllegalArgumentException 발생")
    void toggleActive_unauthorized() {
        // given
        Queue queue = makeQueue(1L, 1L, "BTCUSDT", 0);
        given(queueRepository.findById(1L)).willReturn(Optional.of(queue));

        // when & then
        assertThatThrownBy(() -> patternQueueService.toggleActive(99L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("권한");
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    private Queue makeQueue(Long id, Long userId, String symbol, int sortOrder) {
        Queue q = new Queue();
        q.setId(id);
        q.setUserId(userId);
        q.setSymbol(symbol);
        q.setSortOrder(sortOrder);
        q.setUseYn("Y");
        q.setDelYn("N");
        q.setCreatedAt(LocalDateTime.now());
        return q;
    }

    private AddPatternRequest makeRequest(String symbol, List<AddPatternRequest.StepRequest> steps) {
        AddPatternRequest req = new AddPatternRequest();
        req.setSymbol(symbol);
        req.setSteps(steps);
        return req;
    }

    private AddPatternRequest.StepRequest makeStep(String side, String quantity) {
        AddPatternRequest.StepRequest step = new AddPatternRequest.StepRequest();
        step.setSide(side);
        step.setQuantity(quantity);
        return step;
    }
}
