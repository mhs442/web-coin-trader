package com.coin.webcointrader.common.enums;

import lombok.Getter;

@Getter
public enum Category {
    LINEAR("linear", "선물"),
    INVERSE("inverse", "역선물"),
    SPOT("spot", "현물"),
    OPTION("option", "옵션");

    private final String category;
    private final String explain;

    Category(String category, String explain){
        this.category = category;
        this.explain = explain;
    }
}
