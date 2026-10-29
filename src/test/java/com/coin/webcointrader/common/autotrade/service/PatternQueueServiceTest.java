package com.coin.webcointrader.common.autotrade.service;

import com.coin.webcointrader.common.autotrade.entity.Pattern;
import com.coin.webcointrader.common.autotrade.entity.PatternQueue;
import com.coin.webcointrader.common.enums.ExceptionMessage;
import com.coin.webcointrader.common.enums.ExchangeType;
import com.coin.webcointrader.common.enums.Side;
import com.coin.webcointrader.common.autotrade.dto.AddPatternRequest;
import com.coin.webcointrader.common.autotrade.dto.PatternQueueDTO;
import com.coin.webcointrader.common.autotrade.repository.PatternQueueRepository;
import com.coin.webcointrader.common.enums.TradeMode;
import com.coin.webcointrader.common.exception.CustomException;
import com.coin.webcointrader.common.market.service.MarketService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PatternQueueServiceTest {

    @InjectMocks
    private PatternQueueService patternQueueService;

    @Mock
    private PatternQueueRepository patternQueueRepository;

    @Mock
    private MarketService marketService;

    @BeforeEach
    void setUp() {
        // 기본: 모든 종목 거래 가능 (검증 이전 단계에서 실패하는 테스트도 있어 lenient)
        lenient().when(marketService.isTradable(any(ExchangeType.class), any())).thenReturn(true);
    }

    // ─────────────────────────────────────────────
    // addQueue 성공
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("addQueue: 유효한 요청이면 PatternQueue와 하위 엔티티를 모두 저장한다")
    void addQueue_success() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeFullRequest("BTCUSDT");

        given(patternQueueRepository.countByUserIdAndSymbolAndTradeModeAndExchangeType(userId, "BTCUSDT", TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(0L);
        given(patternQueueRepository.save(any(PatternQueue.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        PatternQueue result = patternQueueService.addQueue(userId, request);

        // then
        // 큐 기본 정보 검증
        assertThat(result.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getTriggerRate()).isEqualByComparingTo(new BigDecimal("1.0"));
        assertThat(result.isActive()).isFalse();

        // 패턴 검증 (1단계, 양방향 보장 — LONG 시작 + SHORT 시작)
        assertThat(result.getPatterns()).hasSize(2);
        Pattern pattern = result.getPatterns().get(0);
        assertThat(pattern.getQueue()).isSameAs(result);
        assertThat(pattern.getStepLevel()).isEqualTo(1);
        assertThat(pattern.getAmount()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(pattern.getLeverage()).isEqualTo(5);
        assertThat(pattern.getStopLossRate()).isEqualByComparingTo(new BigDecimal("1.0"));
        assertThat(pattern.getTakeProfitRate()).isEqualByComparingTo(new BigDecimal("5.0"));
        assertThat(pattern.getPatternOrder()).isEqualTo(1);
        assertThat(result.getPatterns().get(1).getPatternOrder()).isEqualTo(2);

        // 블록 검증 (조건 블록 1개 + 리프 블록 1개, 마지막 원소가 리프)
        assertThat(pattern.getBlocks()).containsExactly(Side.LONG, Side.LONG);
        assertThat(result.getPatterns().get(1).getBlocks()).containsExactly(Side.SHORT, Side.SHORT);

        then(patternQueueRepository).should().save(any(PatternQueue.class));
    }

    @Test
    @DisplayName("addQueue: 거래량 급변 중지 on/off 설정을 저장하고, 중지 예약은 비어 있다")
    void addQueue_savesVolumeStopEnabled() {
        Long userId = 1L;
        AddPatternRequest request = makeFullRequest("BTCUSDT");
        request.setVolumeStopEnabled(true);
        given(patternQueueRepository.countByUserIdAndSymbolAndTradeModeAndExchangeType(userId, "BTCUSDT", TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(0L);
        given(patternQueueRepository.save(any(PatternQueue.class))).willAnswer(inv -> inv.getArgument(0));

        PatternQueue result = patternQueueService.addQueue(userId, request);

        assertThat(result.isVolumeStopEnabled()).isTrue();
        assertThat(result.getVolumeStopReason()).isNull();
    }

    @Test
    @DisplayName("addQueue: 2단계 + 각 단계마다 양방향 패턴 2개 구조도 정상 저장된다")
    void addQueue_multiStepMultiPattern_success() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeMultiStepRequest("BTCUSDT");

        given(patternQueueRepository.countByUserIdAndSymbolAndTradeModeAndExchangeType(userId, "BTCUSDT", TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(0L);
        given(patternQueueRepository.save(any(PatternQueue.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        PatternQueue result = patternQueueService.addQueue(userId, request);

        // then
        // 1단계 양방향 패턴 2개 + 2단계 양방향 패턴 2개
        assertThat(result.getPatterns())
                .extracting(Pattern::getStepLevel, Pattern::getPatternOrder)
                .containsExactly(tuple(1, 1), tuple(1, 2), tuple(2, 1), tuple(2, 2));
        assertThat(result.getPatterns().get(2).getAmount()).isEqualByComparingTo(new BigDecimal("20"));
    }

    @Test
    @DisplayName("addQueue: 조건 블록은 배열 순서 그대로 저장되고 리프 블록이 마지막에 붙는다")
    void addQueue_blocksKeepArrayOrderWithLeafLast() {
        // given - 조건 블록 순서 = 배열 순서 (시작 블록은 LONG 유지 — 같은 단계 SHORT 시작 패턴과 겹치지 않도록)
        Long userId = 1L;
        AddPatternRequest request = makeFullRequest("BTCUSDT");
        AddPatternRequest.PatternRequest longPattern = request.getPatterns().get(0);
        longPattern.setConditionBlocks(List.of("LONG", "SHORT", "SHORT"));
        longPattern.setLeafBlock("SHORT");

        given(patternQueueRepository.countByUserIdAndSymbolAndTradeModeAndExchangeType(userId, "BTCUSDT", TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(0L);
        given(patternQueueRepository.save(any(PatternQueue.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        PatternQueue result = patternQueueService.addQueue(userId, request);

        // then
        assertThat(result.getPatterns().get(0).getBlocks())
                .containsExactly(Side.LONG, Side.SHORT, Side.SHORT, Side.SHORT);
    }

    @Test
    @DisplayName("addQueue: 패턴 순번은 같은 단계 안에서 요청 순서대로 1부터 부여된다")
    void addQueue_patternOrderAssignedPerStep() {
        // given - 단계가 섞인 순서로 전달 (2단계, 1단계, 2단계, 1단계)
        Long userId = 1L;
        AddPatternRequest request = makeFullRequest("BTCUSDT");
        request.setPatterns(List.of(
                makePatternRequest(2), makeShortPatternRequest(1),
                makeShortPatternRequest(2), makePatternRequest(1)));

        given(patternQueueRepository.countByUserIdAndSymbolAndTradeModeAndExchangeType(userId, "BTCUSDT", TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(0L);
        given(patternQueueRepository.save(any(PatternQueue.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        PatternQueue result = patternQueueService.addQueue(userId, request);

        // then
        assertThat(result.getPatterns())
                .extracting(Pattern::getStepLevel, Pattern::getPatternOrder)
                .containsExactly(tuple(2, 1), tuple(1, 1), tuple(2, 2), tuple(1, 2));
    }

    @Test
    @DisplayName("addQueue: 조건 블록이 없으면(null) CustomException 발생 — 엔진은 조건 블록 없는 패턴을 선택할 수 없음")
    void addQueue_nullConditionBlocks() {
        // given
        AddPatternRequest request = makeFullRequest("BTCUSDT");
        request.getPatterns().get(0).setConditionBlocks(null);

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(1L, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.EMPTY_CONDITION_BLOCKS.getMessage());
    }

    @Test
    @DisplayName("addQueue: 조건 블록이 빈 배열이면 CustomException 발생")
    void addQueue_emptyConditionBlocks() {
        // given
        AddPatternRequest request = makeFullRequest("BTCUSDT");
        request.getPatterns().get(0).setConditionBlocks(List.of());

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(1L, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.EMPTY_CONDITION_BLOCKS.getMessage());
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
        request.setPatterns(List.of());

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
        List<AddPatternRequest.PatternRequest> patterns = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            patterns.add(makePatternRequest(i));
        }
        request.setPatterns(patterns);

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(userId, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("20");
    }

    @Test
    @DisplayName("addQueue: 단계 번호(stepLevel)가 없으면 CustomException 발생")
    void addQueue_nullStepLevel() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeFullRequest("BTCUSDT");
        request.getPatterns().get(0).setStepLevel(null);

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(userId, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("단계 번호");
    }

    @Test
    @DisplayName("addQueue: 단계 번호(stepLevel)가 1 미만이면 CustomException 발생")
    void addQueue_zeroStepLevel() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeFullRequest("BTCUSDT");
        request.getPatterns().get(0).setStepLevel(0);

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(userId, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("단계 번호");
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
        request.setPatterns(threePatterns);

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
        List<String> sixBlocks = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            sixBlocks.add("LONG");
        }
        pattern.setConditionBlocks(sixBlocks);
        request.setPatterns(List.of(pattern, makeShortPatternRequest()));

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
        request.setPatterns(List.of(pattern, makeShortPatternRequest()));

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
        request.setPatterns(List.of(pattern, makeShortPatternRequest()));

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(userId, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("레버리지");
    }

    // ─────────────────────────────────────────────
    // 손절률 강제청산 한도 (손절률 ≤ 100 - 1.5 × 레버리지)
    // ─────────────────────────────────────────────

    /** LONG 패턴의 레버리지/손절률만 바꾼 요청 생성 */
    private AddPatternRequest makeRequestWithLeverageAndStopLoss(int leverage, String stopLossRate) {
        AddPatternRequest request = makeFullRequest("BTCUSDT");
        AddPatternRequest.PatternRequest pattern = makePatternRequest();
        pattern.setLeverage(leverage);
        pattern.setStopLossRate(stopLossRate != null ? new BigDecimal(stopLossRate) : null);
        request.setPatterns(List.of(pattern, makeShortPatternRequest()));
        return request;
    }

    @Test
    @DisplayName("addQueue: 손절률이 한도와 같으면 통과한다 (10배 → 최대 85%)")
    void addQueue_stopLossAtLimit() {
        AddPatternRequest request = makeRequestWithLeverageAndStopLoss(10, "85");
        given(patternQueueRepository.countByUserIdAndSymbolAndTradeModeAndExchangeType(1L, "BTCUSDT", TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(0L);
        given(patternQueueRepository.save(any(PatternQueue.class))).willAnswer(inv -> inv.getArgument(0));

        assertThat(patternQueueService.addQueue(1L, request)).isNotNull();
    }

    @Test
    @DisplayName("addQueue: 손절률이 강제청산 한도를 넘으면 CustomException 발생 (10배, 86%)")
    void addQueue_stopLossOverLimit() {
        AddPatternRequest request = makeRequestWithLeverageAndStopLoss(10, "86");

        assertThatThrownBy(() -> patternQueueService.addQueue(1L, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("강제청산");
    }

    @Test
    @DisplayName("addQueue: 67배 이상은 손절률을 아무리 낮춰도 CustomException 발생")
    void addQueue_leverageTooHighForAnyStopLoss() {
        AddPatternRequest request = makeRequestWithLeverageAndStopLoss(67, "0.5");

        assertThatThrownBy(() -> patternQueueService.addQueue(1L, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("강제청산");
    }

    @Test
    @DisplayName("addQueue: 손절률 미입력 + 10배는 기본값(가격 8%)으로 통과한다")
    void addQueue_nullStopLossWithinLimit() {
        AddPatternRequest request = makeRequestWithLeverageAndStopLoss(10, null);
        given(patternQueueRepository.countByUserIdAndSymbolAndTradeModeAndExchangeType(1L, "BTCUSDT", TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(0L);
        given(patternQueueRepository.save(any(PatternQueue.class))).willAnswer(inv -> inv.getArgument(0));

        assertThat(patternQueueService.addQueue(1L, request)).isNotNull();
    }

    @Test
    @DisplayName("addQueue: 손절률 미입력 + 11배 이상이면 손절률 입력을 요구하는 CustomException 발생")
    void addQueue_nullStopLossOverLimit() {
        AddPatternRequest request = makeRequestWithLeverageAndStopLoss(11, null);

        assertThatThrownBy(() -> patternQueueService.addQueue(1L, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("손절률을 입력");
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
        pattern.setConditionBlocks(List.of("INVALID"));
        request.setPatterns(List.of(pattern, makeShortPatternRequest()));

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
        request.setPatterns(List.of(pattern, makeShortPatternRequest()));

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(userId, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("리프 블록");
    }

    @Test
    @DisplayName("addQueue: 조건 블록에 null이 섞여 있으면 NPE가 아닌 INVALID_SIDE 예외")
    void addQueue_nullSideInConditionBlocks() {
        // given - List.of는 null을 허용하지 않아 Arrays.asList 사용
        AddPatternRequest request = makeFullRequest("BTCUSDT");
        request.getPatterns().get(0).setConditionBlocks(Arrays.asList("LONG", null));

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(1L, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.INVALID_SIDE.getMessage());
    }

    @Test
    @DisplayName("addQueue: 같은 단계 두 패턴의 시작 블록이 같으면 CustomException 발생")
    void addQueue_duplicateStartBlock() {
        // given - L:L 과 L:S → 둘 다 LONG 시작 (SHORT 신호 시 선택할 패턴 없음)
        AddPatternRequest request = makeFullRequest("BTCUSDT");
        AddPatternRequest.PatternRequest secondLong = makePatternRequest();
        secondLong.setLeafBlock("SHORT");
        request.setPatterns(List.of(makePatternRequest(), secondLong));

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(1L, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.DUPLICATE_START_BLOCK.getMessage());
    }

    @Test
    @DisplayName("addQueue: 단계 번호 중간이 비면({1, 3}) CustomException 발생")
    void addQueue_stepLevelGap() {
        // given
        AddPatternRequest request = makeFullRequest("BTCUSDT");
        request.setPatterns(List.of(
                makePatternRequest(1), makeShortPatternRequest(1),
                makePatternRequest(3), makeShortPatternRequest(3)));

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(1L, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.NON_CONSECUTIVE_STEP_LEVEL.getMessage());
    }

    @Test
    @DisplayName("addQueue: 1단계가 없으면({2, 3}) CustomException 발생")
    void addQueue_stepLevelMissingFirst() {
        // given
        AddPatternRequest request = makeFullRequest("BTCUSDT");
        request.setPatterns(List.of(
                makePatternRequest(2), makeShortPatternRequest(2),
                makePatternRequest(3), makeShortPatternRequest(3)));

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(1L, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.NON_CONSECUTIVE_STEP_LEVEL.getMessage());
    }

    @Test
    @DisplayName("addQueue: 동시 요청으로 유니크 제약에 걸리면 500이 아닌 EXCEED_MAX_QUEUES 예외")
    void addQueue_uniqueConstraintConflict() {
        // given - 개수 검사는 통과했지만 저장 시점에 다른 요청이 먼저 저장한 상황
        Long userId = 1L;
        AddPatternRequest request = makeFullRequest("BTCUSDT");
        given(patternQueueRepository.countByUserIdAndSymbolAndTradeModeAndExchangeType(userId, "BTCUSDT", TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(0L);
        given(patternQueueRepository.save(any(PatternQueue.class))).willThrow(new DataIntegrityViolationException("uk_pattern_queue_user_exchange_symbol_mode"));

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(userId, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.EXCEED_MAX_QUEUES.getMessage());
    }

    @Test
    @DisplayName("addQueue: 심볼에 큐가 이미 1개 이상 존재하면 CustomException 발생")
    void addQueue_maxQueueLimit() {
        // given
        Long userId = 1L;
        AddPatternRequest request = makeFullRequest("BTCUSDT");

        given(patternQueueRepository.countByUserIdAndSymbolAndTradeModeAndExchangeType(userId, "BTCUSDT", TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(1L);

        // when & then
        assertThatThrownBy(() -> patternQueueService.addQueue(userId, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("1개만");
    }

    @Test
    @DisplayName("addQueue: 선택 거래소에서 거래할 수 없는 종목이면 저장하지 않고 CustomException 발생")
    void addQueue_symbolNotTradable() {
        AddPatternRequest request = makeFullRequest("NOTLISTEDUSDT");
        given(marketService.isTradable(ExchangeType.BYBIT, "NOTLISTEDUSDT")).willReturn(false);

        assertThatThrownBy(() -> patternQueueService.addQueue(1L, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("거래할 수 없는 종목");
        then(patternQueueRepository).should(org.mockito.Mockito.never()).save(any(PatternQueue.class));
    }

    // ─────────────────────────────────────────────
    // getAllQueues
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getAllQueues: 심볼 무관 전체 큐 목록을 반환한다")
    void getAllQueues_returnAll() {
        Long userId = 1L;
        PatternQueue btc = makePatternQueue(1L, userId, "BTCUSDT");
        PatternQueue eth = makePatternQueue(2L, userId, "ETHUSDT");
        given(patternQueueRepository.findByUserIdAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(userId, TradeMode.MAIN, ExchangeType.BYBIT))
                .willReturn(List.of(btc, eth));

        List<PatternQueue> result = patternQueueService.getAllQueues(userId, ExchangeType.BYBIT, TradeMode.MAIN);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(PatternQueue::getSymbol)
                .containsExactlyInAnyOrder("BTCUSDT", "ETHUSDT");
    }

    @Test
    @DisplayName("getAllQueues: 거래소 미지정(null)이면 모든 거래소의 큐 목록을 반환한다")
    void getAllQueues_withoutExchange_returnAllExchanges() {
        Long userId = 1L;
        PatternQueue btc = makePatternQueue(1L, userId, "BTCUSDT");
        PatternQueue eth = makePatternQueue(2L, userId, "ETHUSDT");
        eth.setExchangeType(ExchangeType.BINANCE);
        given(patternQueueRepository.findByUserIdAndTradeModeOrderByCreatedAtAsc(userId, TradeMode.MAIN))
                .willReturn(List.of(btc, eth));

        List<PatternQueue> result = patternQueueService.getAllQueues(userId, null, TradeMode.MAIN);

        assertThat(result).extracting(PatternQueue::getExchangeType)
                .containsExactly(ExchangeType.BYBIT, ExchangeType.BINANCE);
        then(patternQueueRepository).should(never())
                .findByUserIdAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(any(), any(), any());
    }

    // ─────────────────────────────────────────────
    // copyQueue
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("copyQueue: 큐 구조(단계/패턴/블록)를 대상 심볼로 복사한다")
    void copyQueue_success() {
        Long userId = 1L;
        PatternQueue origin = makePatternQueueWithSteps(1L, userId, "ETHUSDT");
        given(patternQueueRepository.findById(1L)).willReturn(java.util.Optional.of(origin));
        given(patternQueueRepository.countByUserIdAndSymbolAndTradeModeAndExchangeType(userId, "BTCUSDT", TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(0L);
        given(patternQueueRepository.save(any(PatternQueue.class))).willAnswer(inv -> inv.getArgument(0));

        PatternQueue result = patternQueueService.copyQueue(userId, 1L, "BTCUSDT", null, TradeMode.MAIN);

        // 심볼이 대상으로 변경되어야 함
        assertThat(result.getSymbol()).isEqualTo("BTCUSDT");
        // 트리거 비율 유지
        assertThat(result.getTriggerRate()).isEqualByComparingTo(new BigDecimal("1.0"));
        // 새 큐는 비활성 상태로 생성
        assertThat(result.isActive()).isFalse();
        // 패턴(단계/블록 포함) 구조 복제 검증
        assertThat(result.getPatterns()).hasSize(1);
        Pattern copied = result.getPatterns().get(0);
        assertThat(copied).isNotSameAs(origin.getPatterns().get(0));
        assertThat(copied.getQueue()).isSameAs(result);
        assertThat(copied.getStepLevel()).isEqualTo(1);
        assertThat(copied.getAmount()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(copied.getBlocks()).containsExactly(Side.LONG, Side.LONG);
        // 블록 목록은 원본과 별도 인스턴스여야 한다 (원본 수정이 복사본에 전파되지 않음)
        assertThat(copied.getBlocks()).isNotSameAs(origin.getPatterns().get(0).getBlocks());
    }

    @Test
    @DisplayName("copyQueue: 원본의 사이클(cycle) 설정을 복사한다")
    void copyQueue_copiesCycle() {
        Long userId = 1L;
        PatternQueue origin = makePatternQueueWithSteps(1L, userId, "ETHUSDT");
        origin.setCycle(true);
        given(patternQueueRepository.findById(1L)).willReturn(java.util.Optional.of(origin));
        given(patternQueueRepository.countByUserIdAndSymbolAndTradeModeAndExchangeType(userId, "BTCUSDT", TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(0L);
        given(patternQueueRepository.save(any(PatternQueue.class))).willAnswer(inv -> inv.getArgument(0));

        PatternQueue result = patternQueueService.copyQueue(userId, 1L, "BTCUSDT", null, TradeMode.MAIN);

        assertThat(result.isCycle()).isTrue();
    }

    @Test
    @DisplayName("setActive: 사용자가 큐를 끄거나 켜면 거래량 급변 중지 예약을 해제한다 (STOP 이력 없음)")
    void setActive_clearsVolumeStopReservation() {
        Long userId = 1L;
        PatternQueue queue = makePatternQueueWithSteps(1L, userId, "ETHUSDT");
        queue.setVolumeStopEnabled(true);
        queue.setVolumeStopReason("[거래량 급감 중지] 예약");
        given(patternQueueRepository.findById(1L)).willReturn(java.util.Optional.of(queue));
        given(patternQueueRepository.save(any(PatternQueue.class))).willAnswer(inv -> inv.getArgument(0));

        PatternQueue result = patternQueueService.setActive(userId, 1L, false);

        assertThat(result.isActive()).isFalse();
        assertThat(result.getVolumeStopReason()).isNull();
        assertThat(result.isVolumeStopEnabled()).isTrue();
    }

    @Test
    @DisplayName("copyQueue: 거래량 급변 중지 on/off는 복사하고, 중지 예약은 복사하지 않는다")
    void copyQueue_copiesVolumeStopEnabledButNotReservation() {
        Long userId = 1L;
        PatternQueue origin = makePatternQueueWithSteps(1L, userId, "ETHUSDT");
        origin.setVolumeStopEnabled(true);
        origin.setVolumeStopReason("[거래량 급변 중지] 예약");
        given(patternQueueRepository.findById(1L)).willReturn(java.util.Optional.of(origin));
        given(patternQueueRepository.countByUserIdAndSymbolAndTradeModeAndExchangeType(userId, "BTCUSDT", TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(0L);
        given(patternQueueRepository.save(any(PatternQueue.class))).willAnswer(inv -> inv.getArgument(0));

        PatternQueue result = patternQueueService.copyQueue(userId, 1L, "BTCUSDT", null, TradeMode.MAIN);

        assertThat(result.isVolumeStopEnabled()).isTrue();
        assertThat(result.getVolumeStopReason()).isNull();
    }

    @Test
    @DisplayName("copyQueue: 동시 요청으로 유니크 제약에 걸리면 500이 아닌 EXCEED_MAX_QUEUES 예외")
    void copyQueue_uniqueConstraintConflict() {
        PatternQueue origin = makePatternQueueWithSteps(1L, 1L, "ETHUSDT");
        given(patternQueueRepository.findById(1L)).willReturn(java.util.Optional.of(origin));
        given(patternQueueRepository.countByUserIdAndSymbolAndTradeModeAndExchangeType(1L, "BTCUSDT", TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(0L);
        given(patternQueueRepository.save(any(PatternQueue.class))).willThrow(new DataIntegrityViolationException("uk_pattern_queue_user_exchange_symbol_mode"));

        assertThatThrownBy(() -> patternQueueService.copyQueue(1L, 1L, "BTCUSDT", null, TradeMode.MAIN))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ExceptionMessage.EXCEED_MAX_QUEUES.getMessage());
    }

    @Test
    @DisplayName("copyQueue: 존재하지 않는 큐면 CustomException 발생")
    void copyQueue_notFound() {
        given(patternQueueRepository.findById(999L)).willReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> patternQueueService.copyQueue(1L, 999L, "BTCUSDT", null, TradeMode.MAIN))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("찾을 수 없습니다");
    }

    @Test
    @DisplayName("copyQueue: 다른 사용자의 큐면 CustomException 발생")
    void copyQueue_unauthorized() {
        PatternQueue origin = makePatternQueue(1L, 99L, "ETHUSDT"); // userId=99
        given(patternQueueRepository.findById(1L)).willReturn(java.util.Optional.of(origin));

        assertThatThrownBy(() -> patternQueueService.copyQueue(1L, 1L, "BTCUSDT", null, TradeMode.MAIN))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("권한");
    }

    @Test
    @DisplayName("copyQueue: 대상 심볼에 큐가 이미 1개 이상 존재하면 CustomException 발생")
    void copyQueue_exceedLimit() {
        PatternQueue origin = makePatternQueue(1L, 1L, "ETHUSDT");
        given(patternQueueRepository.findById(1L)).willReturn(java.util.Optional.of(origin));
        given(patternQueueRepository.countByUserIdAndSymbolAndTradeModeAndExchangeType(1L, "BTCUSDT", TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(1L);

        assertThatThrownBy(() -> patternQueueService.copyQueue(1L, 1L, "BTCUSDT", null, TradeMode.MAIN))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("1개만");
    }

    @Test
    @DisplayName("copyQueue: 대상 거래소에서 거래할 수 없는 종목이면 저장하지 않고 CustomException 발생")
    void copyQueue_symbolNotTradable() {
        PatternQueue origin = makePatternQueue(1L, 1L, "ETHUSDT");
        given(patternQueueRepository.findById(1L)).willReturn(java.util.Optional.of(origin));
        given(marketService.isTradable(ExchangeType.BINANCE, "NOTLISTEDUSDT")).willReturn(false);

        assertThatThrownBy(() -> patternQueueService.copyQueue(1L, 1L, "NOTLISTEDUSDT", "BINANCE", TradeMode.MAIN))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("거래할 수 없는 종목");
        then(patternQueueRepository).should(org.mockito.Mockito.never()).save(any(PatternQueue.class));
    }

    // ─────────────────────────────────────────────
    // 멀티 거래소 구분 (exchangeType)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("addQueue: 요청의 거래소(대소문자 무시)를 큐에 저장하고, 개수 제한도 해당 거래소 기준으로 확인한다")
    void addQueue_savesRequestedExchangeType() {
        Long userId = 1L;
        AddPatternRequest request = makeFullRequest("BTCUSDT");
        request.setExchangeType("bybit");

        given(patternQueueRepository.countByUserIdAndSymbolAndTradeModeAndExchangeType(
                userId, "BTCUSDT", TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(0L);
        given(patternQueueRepository.save(any(PatternQueue.class))).willAnswer(inv -> inv.getArgument(0));

        PatternQueue result = patternQueueService.addQueue(userId, request);

        assertThat(result.getExchangeType()).isEqualTo(ExchangeType.BYBIT);
    }

    @Test
    @DisplayName("addQueue: 지원하지 않는 거래소면 BYBIT로 대체하지 않고 CustomException 발생")
    void addQueue_invalidExchangeType() {
        AddPatternRequest request = makeFullRequest("BTCUSDT");
        request.setExchangeType("UNKNOWN");

        assertThatThrownBy(() -> patternQueueService.addQueue(1L, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("지원하지 않는 거래소");
    }

    @Test
    @DisplayName("getQueues: 거래소 조건을 포함해 조회한다")
    void getQueues_filtersByExchangeType() {
        Long userId = 1L;
        PatternQueue btc = makePatternQueue(1L, userId, "BTCUSDT");
        given(patternQueueRepository.findByUserIdAndSymbolAndTradeModeAndExchangeTypeOrderByCreatedAtAsc(
                userId, "BTCUSDT", TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(List.of(btc));

        List<PatternQueue> result = patternQueueService.getQueues(userId, ExchangeType.BYBIT, "BTCUSDT", TradeMode.MAIN);

        assertThat(result).containsExactly(btc);
    }

    @Test
    @DisplayName("copyQueue: 대상 거래소를 지정하면 그 거래소로 복사하고 개수 제한도 대상 거래소 기준으로 확인한다")
    void copyQueue_targetExchangeSpecified() {
        Long userId = 1L;
        PatternQueue origin = makePatternQueueWithSteps(1L, userId, "ETHUSDT");
        given(patternQueueRepository.findById(1L)).willReturn(java.util.Optional.of(origin));
        given(patternQueueRepository.countByUserIdAndSymbolAndTradeModeAndExchangeType(
                userId, "BTCUSDT", TradeMode.MAIN, ExchangeType.BYBIT)).willReturn(0L);
        given(patternQueueRepository.save(any(PatternQueue.class))).willAnswer(inv -> inv.getArgument(0));

        PatternQueue result = patternQueueService.copyQueue(userId, 1L, "BTCUSDT", "bybit", TradeMode.MAIN);

        assertThat(result.getExchangeType()).isEqualTo(ExchangeType.BYBIT);
    }

    @Test
    @DisplayName("copyQueue: 대상 거래소가 지원하지 않는 값이면 CustomException 발생")
    void copyQueue_invalidTargetExchange() {
        PatternQueue origin = makePatternQueue(1L, 1L, "ETHUSDT");
        given(patternQueueRepository.findById(1L)).willReturn(java.util.Optional.of(origin));

        assertThatThrownBy(() -> patternQueueService.copyQueue(1L, 1L, "BTCUSDT", "UNKNOWN", TradeMode.MAIN))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("지원하지 않는 거래소");
    }

    // ─────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────
    // findQueuePage (마이페이지 등 타 도메인 조회용 DTO 페이지)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("findQueuePage: 심볼 키워드가 없으면 날짜 범위만으로 조회하고 DTO로 변환한다")
    void findQueuePage_withoutSymbol() {
        Pageable pageable = PageRequest.of(0, 10);
        given(patternQueueRepository.findByUserIdAndTradeModeAndExchangeTypeAndCreatedAtBetween(
                eq(1L), eq(TradeMode.MAIN), eq(ExchangeType.BYBIT), any(), any(), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(makePatternQueue(1L, 1L, "BTCUSDT"))));

        Page<PatternQueueDTO> result = patternQueueService.findQueuePage(
                1L, null, TradeMode.MAIN, ExchangeType.BYBIT, java.time.LocalDateTime.MIN, java.time.LocalDateTime.MAX, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getSymbol()).isEqualTo("BTCUSDT");
        assertThat(result.getContent().get(0).getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("findQueuePage: 심볼 키워드가 있으면 LIKE 필터 쿼리로 조회한다")
    void findQueuePage_withSymbol() {
        Pageable pageable = PageRequest.of(0, 10);
        given(patternQueueRepository.findByUserIdAndSymbolContainingIgnoreCaseAndTradeModeAndExchangeTypeAndCreatedAtBetween(
                eq(1L), eq("BTC"), eq(TradeMode.MAIN), eq(ExchangeType.BYBIT), any(), any(), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(makePatternQueue(1L, 1L, "BTCUSDT"))));

        Page<PatternQueueDTO> result = patternQueueService.findQueuePage(
                1L, "BTC", TradeMode.MAIN, ExchangeType.BYBIT, java.time.LocalDateTime.MIN, java.time.LocalDateTime.MAX, pageable);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("findQueuePage: 패턴 목록이 단계 번호·조건 블록·리프 블록과 함께 DTO에 포함된다")
    void findQueuePage_includesHierarchy() {
        Pageable pageable = PageRequest.of(0, 10);
        given(patternQueueRepository.findByUserIdAndTradeModeAndExchangeTypeAndCreatedAtBetween(
                eq(1L), eq(TradeMode.SIM), eq(ExchangeType.BYBIT), any(), any(), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(makePatternQueueWithSteps(1L, 1L, "BTCUSDT"))));

        PatternQueueDTO dto = patternQueueService.findQueuePage(
                1L, "", TradeMode.SIM, ExchangeType.BYBIT, java.time.LocalDateTime.MIN, java.time.LocalDateTime.MAX, pageable)
                .getContent().get(0);

        assertThat(dto.getPatterns()).hasSize(1);
        PatternQueueDTO.PatternDTO pattern = dto.getPatterns().get(0);
        assertThat(pattern.getStepLevel()).isEqualTo(1);
        assertThat(pattern.getPatternOrder()).isEqualTo(1);
        assertThat(pattern.getAmount()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(pattern.getLeverage()).isEqualTo(5);
        assertThat(pattern.getConditionBlocks()).containsExactly(Side.LONG);
        assertThat(pattern.getLeafBlock()).isEqualTo(Side.LONG);
    }

    // ─────────────────────────────────────────────

    /**
     * 기본 요청 생성 (1단계, 양방향 패턴 2개 — LONG 시작 + SHORT 시작)
     * 단계당 패턴 2개 강제 검증을 통과하기 위해 양방향 보장 구조로 생성.
     */
    private AddPatternRequest makeFullRequest(String symbol) {
        AddPatternRequest request = new AddPatternRequest();
        request.setSymbol(symbol);
        request.setExchangeType("BYBIT");

        request.setTriggerRate(new BigDecimal("1.0"));
        request.setPatterns(List.of(makePatternRequest(), makeShortPatternRequest()));
        return request;
    }

    /**
     * 다단계 요청 생성 (2단계, 각 단계마다 양방향 패턴 2개씩)
     */
    private AddPatternRequest makeMultiStepRequest(String symbol) {
        AddPatternRequest request = new AddPatternRequest();
        request.setSymbol(symbol);
        request.setExchangeType("BYBIT");

        request.setTriggerRate(new BigDecimal("1.0"));

        // 1단계: LONG/SHORT 양쪽
        AddPatternRequest.PatternRequest pattern1 = makePatternRequest();
        AddPatternRequest.PatternRequest pattern2 = makeShortPatternRequest();

        // 2단계: LONG/SHORT 양쪽 (amount 다르게)
        AddPatternRequest.PatternRequest pattern3 = makePatternRequest(2);
        pattern3.setAmount(new BigDecimal("20"));
        AddPatternRequest.PatternRequest pattern4 = makeShortPatternRequest(2);
        pattern4.setAmount(new BigDecimal("20"));

        request.setPatterns(List.of(pattern1, pattern2, pattern3, pattern4));
        return request;
    }

    /**
     * 기본 패턴 생성 (1단계, L:L 구조, 10달러, 레버리지 5배, 손절 1%, 익절 5%)
     */
    private AddPatternRequest.PatternRequest makePatternRequest() {
        return makePatternRequest(1);
    }

    /**
     * 지정 단계의 L:L 패턴 생성
     */
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

    /**
     * SHORT 시작 패턴 생성 (S:S 구조). 단계당 양방향 보장 검증 통과용 페어 패턴.
     */
    private AddPatternRequest.PatternRequest makeShortPatternRequest() {
        return makeShortPatternRequest(1);
    }

    /**
     * 지정 단계의 S:S 패턴 생성
     */
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
     * 기본 PatternQueue 생성 (단계 없음, copyQueue 테스트용)
     */
    private PatternQueue makePatternQueue(Long id, Long userId, String symbol) {
        PatternQueue queue = new PatternQueue();
        queue.setExchangeType(ExchangeType.BYBIT);
        queue.setId(id);
        queue.setUserId(userId);
        queue.setSymbol(symbol);
        queue.setActive(false);
        queue.setTriggerRate(new BigDecimal("1.0"));
        queue.setTradeMode(TradeMode.MAIN);
        ReflectionTestUtils.setField(queue, "createdAt", java.time.LocalDateTime.now());
        return queue;
    }

    /**
     * 1단계 1패턴 2블록(L:L) 구조를 가진 PatternQueue 생성 (copyQueue 구조 복제 검증용)
     */
    private PatternQueue makePatternQueueWithSteps(Long id, Long userId, String symbol) {
        PatternQueue queue = makePatternQueue(id, userId, symbol);

        Pattern pattern = new Pattern();
        pattern.setQueue(queue);
        pattern.setStepLevel(1);
        pattern.setPatternOrder(1);
        pattern.setAmount(new BigDecimal("10"));
        pattern.setLeverage(5);
        pattern.setStopLossRate(new BigDecimal("1.0"));
        pattern.setTakeProfitRate(new BigDecimal("5.0"));
        pattern.setBlocks(new ArrayList<>(List.of(Side.LONG, Side.LONG)));  // 조건 LONG + 리프 LONG

        queue.getPatterns().add(pattern);
        return queue;
    }
}
