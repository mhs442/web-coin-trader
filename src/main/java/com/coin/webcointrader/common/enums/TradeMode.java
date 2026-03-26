package com.coin.webcointrader.common.enums;

import lombok.Getter;

@Getter
public enum TradeMode {
    MAIN("main"),
    SIM("sim");

    private final String mode;

    TradeMode(String mode) {
        this.mode = mode;
    }
}
