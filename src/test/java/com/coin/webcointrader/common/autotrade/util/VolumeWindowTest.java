package com.coin.webcointrader.common.autotrade.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class VolumeWindowTest {

    // 집계 시작 시각 (epoch ms, 초 경계)
    private static final long START = 1_700_000_000_000L;

    @Test
    @DisplayName("최근 60초 안의 체결량을 합산한다")
    void sum_addsTradesWithinWindow() {
        VolumeWindow window = new VolumeWindow(START);
        window.add(START, 1.5);
        window.add(START + 500, 0.5);       // 같은 초 버킷
        window.add(START + 30_000, 2.0);

        assertThat(window.sum(START + 30_000)).isCloseTo(4.0, within(1e-9));
    }

    @Test
    @DisplayName("60초가 지난 체결은 합계에서 빠진다")
    void sum_excludesTradesOlderThanWindow() {
        VolumeWindow window = new VolumeWindow(START);
        window.add(START, 1.0);              // 0초
        window.add(START + 10_000, 2.0);     // 10초

        // 60초 시점: 0초 버킷은 창(1~60초) 밖
        assertThat(window.sum(START + 60_000)).isCloseTo(2.0, within(1e-9));
        // 70초 시점: 10초 버킷도 창(11~70초) 밖
        assertThat(window.sum(START + 70_000)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    @DisplayName("같은 슬롯을 재사용하는 60초 뒤 체결은 이전 값을 덮어쓰고 새로 집계한다")
    void add_reusesSlotAfterWindow() {
        VolumeWindow window = new VolumeWindow(START);
        window.add(START, 5.0);
        window.add(START + 60_000, 1.0);     // 같은 슬롯(초 % 60) — 이전 5.0은 버려져야 함

        assertThat(window.sum(START + 60_000)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("현재 시각보다 60초 이상 늦게 도착한 체결은 무시한다")
    void add_ignoresStaleTrade() {
        VolumeWindow window = new VolumeWindow(START);
        window.add(START + 100_000, 1.0);
        window.add(START + 30_000, 9.0);     // 이미 창 밖 — 최신 슬롯을 오염시키면 안 됨

        assertThat(window.sum(START + 100_000)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("집계 시작 후 60초가 지나야 창이 다 찬 것으로 본다")
    void isFull_afterSixtySeconds() {
        VolumeWindow window = new VolumeWindow(START);

        assertThat(window.isFull(START + 59_999)).isFalse();
        assertThat(window.isFull(START + 60_000)).isTrue();
    }
}
