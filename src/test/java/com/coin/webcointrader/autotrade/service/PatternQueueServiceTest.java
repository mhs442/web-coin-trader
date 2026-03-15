package com.coin.webcointrader.autotrade.service;

import com.coin.webcointrader.autotrade.dto.AddPatternRequest;
import com.coin.webcointrader.autotrade.repository.PatternQueueRepository;
import com.coin.webcointrader.common.entity.*;
import com.coin.webcointrader.common.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class PatternQueueServiceTest {

    @InjectMocks
    private PatternQueueService patternQueueService;

    @Mock
    private PatternQueueRepository patternQueueRepository;

    // ─────────────────────────────────────────────
    // addQueue 성공
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("addQueue: 유효한 요청이면 PatternQueue와 하위 엔티티를 모두 저장한다")
    void addQueue_success() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeFullRequest("BTCUSDT");

        given(patternQueueRepository.countByUserIdAndSymbol(userId, "BTCUSDT")).willReturn(0L);
        given(patternQueueRepository.save(any(PatternQueue.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        PatternQueue result = patternQueueService.addQueue(userId, request);

        // then
        // 큐 기본 정보 검증
        assertThat(result.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getTriggerSeconds()).isEqualTo(60);
        assertThat(result.getTriggerRate()).isEqualByComparingTo(new BigDecimal("1.0"));
        assertThat(result.isActive()).isFalse();

        // 단계 검증
        assertThat(result.getSteps()).hasSize(1);
        PatternStep step = result.getSteps().get(0);
        assertThat(step.getStepLevel()).isEqualTo(1);

        // 패턴 검증
        assertThat(step.getPatterns()).hasSize(1);
        Pattern pattern = step.getPatterns().get(0);
        assertThat(pattern.getAmount()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(pattern.getLeverage()).isEqualTo(5);
        assertThat(pattern.getStopLossRate()).isEqualByComparingTo(new BigDecimal("1.0"));
        assertThat(pattern.getTakeProfitRate()).isEqualByComparingTo(new BigDecimal("5.0"));
        assertThat(pattern.getPatternOrder()).isEqualTo(1);

        // 블록 검증 (조건 블록 1개 + 리프 블록 1개)
        assertThat(pattern.getBlocks()).hasSize(2);
        // 조건 블록
        PatternBlock conditionBlock = pattern.getBlocks().get(0);
        assertThat(conditionBlock.getSide()).isEqualTo(Side.LONG);
        assertThat(conditionBlock.getBlockOrder()).isEqualTo(1);
        assertThat(conditionBlock.isLeaf()).isFalse();
        // 리프 블록
        PatternBlock leafBlock = pattern.getBlocks().get(1);
        assertThat(leafBlock.getSide()).isEqualTo(Side.LONG);
        assertThat(leafBlock.getBlockOrder()).isEqualTo(2);
        assertThat(leafBlock.isLeaf()).isTrue();

        then(patternQueueRepository).should().save(any(PatternQueue.class));
    }

    @Test
    @DisplayName("addQueue: 2단계 + 패턴 2개 구조도 정상 저장된다")
    void addQueue_multiStepMultiPattern_success() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeMultiStepRequest("BTCUSDT");

        given(patternQueueRepository.countByUserIdAndSymbol(userId, "BTCUSDT")).willReturn(0L);
        given(patternQueueRepository.save(any(PatternQueue.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        PatternQueue result = patternQueueService.addQueue(userId, request);

        // then
        assertThat(result.getSteps()).hasSize(2);
        // 1단계: 패턴 2개
        assertThat(result.getSteps().get(0).getPatterns()).hasSize(2);
        // 2단계: 패턴 1개
        assertThat(result.getSteps().get(1).getPatterns()).hasSize(1);
    }

    // ─────────────────────────────────────────────
    // addQueue 검증 실패
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("addQueue: 단계가 없으면 CustomException 발생")
    void addQueue_emptySteps() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeFullRequest("BTCUSDT");
        request.setSteps(List.of());

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(userId, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("1개 이상");
    }

    @Test
    @DisplayName("addQueue: 단계가 20개를 초과하면 CustomException 발생")
    void addQueue_tooManySteps() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeFullRequest("BTCUSDT");

        // 21개 단계 생성
        List<AddPatternRequest.StepRequest> steps = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            steps.add(makeStepRequest(i, List.of(makePatternRequest())));
        }
        request.setSteps(steps);

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(userId, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("20");
    }

    @Test
    @DisplayName("addQueue: 한 단계에 패턴이 3개 이상이면 CustomException 발생")
    void addQueue_tooManyPatterns() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeFullRequest("BTCUSDT");

        // 패턴 3개인 단계
        List<AddPatternRequest.PatternRequest> threePatterns = List.of(
                makePatternRequest(), makePatternRequest(), makePatternRequest()
        );
        request.setSteps(List.of(makeStepRequest(1, threePatterns)));

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(userId, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("2개");
    }

    @Test
    @DisplayName("addQueue: 조건 블록이 6개 이상이면 CustomException 발생")
    void addQueue_tooManyConditionBlocks() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeFullRequest("BTCUSDT");

        // 조건 블록 6개인 패턴
        AddPatternRequest.PatternRequest pattern = makePatternRequest();
        List<AddPatternRequest.BlockRequest> sixBlocks = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            sixBlocks.add(makeBlockRequest("LONG", i, false));
        }
        pattern.setConditionBlocks(sixBlocks);
        request.setSteps(List.of(makeStepRequest(1, List.of(pattern))));

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(userId, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("5개");
    }

    @Test
    @DisplayName("addQueue: 금액이 0 이하면 CustomException 발생")
    void addQueue_zeroAmount() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeFullRequest("BTCUSDT");

        AddPatternRequest.PatternRequest pattern = makePatternRequest();
        pattern.setAmount(BigDecimal.ZERO);
        request.setSteps(List.of(makeStepRequest(1, List.of(pattern))));

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(userId, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("금액");
    }

    @Test
    @DisplayName("addQueue: 레버리지가 0 이하면 CustomException 발생")
    void addQueue_zeroLeverage() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeFullRequest("BTCUSDT");

        AddPatternRequest.PatternRequest pattern = makePatternRequest();
        pattern.setLeverage(0);
        request.setSteps(List.of(makeStepRequest(1, List.of(pattern))));

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(userId, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("레버리지");
    }

    @Test
    @DisplayName("addQueue: 트리거 시간이 0 이하면 CustomException 발생")
    void addQueue_invalidTriggerSeconds() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeFullRequest("BTCUSDT");
        request.setTriggerSeconds(0);

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(userId, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("트리거 시간");
    }

    @Test
    @DisplayName("addQueue: 트리거 비율이 0 이하면 CustomException 발생")
    void addQueue_invalidTriggerRate() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeFullRequest("BTCUSDT");
        request.setTriggerRate(BigDecimal.ZERO);

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(userId, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("트리거 비율");
    }

    @Test
    @DisplayName("addQueue: 잘못된 side 값이면 CustomException 발생")
    void addQueue_invalidSide() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeFullRequest("BTCUSDT");

        AddPatternRequest.PatternRequest pattern = makePatternRequest();
        pattern.setConditionBlocks(List.of(makeBlockRequest("INVALID", 1, false)));
        request.setSteps(List.of(makeStepRequest(1, List.of(pattern))));

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(userId, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("LONG 또는 SHORT");
    }

    @Test
    @DisplayName("addQueue: 리프 블록이 없으면 CustomException 발생")
    void addQueue_missingLeafBlock() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeFullRequest("BTCUSDT");

        AddPatternRequest.PatternRequest pattern = makePatternRequest();
        pattern.setLeafBlock(null);
        request.setSteps(List.of(makeStepRequest(1, List.of(pattern))));

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(userId, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("리프 블록");
    }

    @Test
    @DisplayName("addQueue: 큐가 20개 이상이면 CustomException 발생")
    void addQueue_maxQueueLimit() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeFullRequest("BTCUSDT");

        given(patternQueueRepository.countByUserIdAndSymbol(userId, "BTCUSDT")).willReturn(20L);

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(userId, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("20개");
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────

    /**
     * 기본 요청 생성 (1단계, 1패턴, 조건블록 1개 + 리프블록 1개)
     */
    private AddPatternRequest makeFullRequest(String symbol) {
        AddPatternRequest request = new AddPatternRequest();
        request.setSymbol(symbol);
        request.setTriggerSeconds(60);
        request.setTriggerRate(new BigDecimal("1.0"));
        request.setSteps(List.of(
                makeStepRequest(1, List.of(makePatternRequest()))
        ));
        return request;
    }

    /**
     * 다단계 요청 생성 (2단계, 1단계에 패턴 2개, 2단계에 패턴 1개)
     */
    private AddPatternRequest makeMultiStepRequest(String symbol) {
        AddPatternRequest request = new AddPatternRequest();
        request.setSymbol(symbol);
        request.setTriggerSeconds(60);
        request.setTriggerRate(new BigDecimal("1.0"));

        // 1단계: 패턴 2개 (L:L, S:S)
        AddPatternRequest.PatternRequest pattern1 = makePatternRequest();
        AddPatternRequest.PatternRequest pattern2 = makePatternRequest();
        pattern2.setConditionBlocks(List.of(makeBlockRequest("SHORT", 1, false)));
        pattern2.setLeafBlock(makeBlockRequest("SHORT", 2, true));

        // 2단계: 패턴 1개
        AddPatternRequest.PatternRequest pattern3 = makePatternRequest();
        pattern3.setAmount(new BigDecimal("20"));

        request.setSteps(List.of(
                makeStepRequest(1, List.of(pattern1, pattern2)),
                makeStepRequest(2, List.of(pattern3))
        ));
        return request;
    }

    private AddPatternRequest.StepRequest makeStepRequest(int stepOrder,
                                                           List<AddPatternRequest.PatternRequest> patterns) {
        AddPatternRequest.StepRequest step = new AddPatternRequest.StepRequest();
        step.setStepOrder(stepOrder);
        step.setPatterns(patterns);
        return step;
    }

    /**
     * 기본 패턴 생성 (L:L 구조, 10달러, 레버리지 5배, 손절 1%, 익절 5%)
     */
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
