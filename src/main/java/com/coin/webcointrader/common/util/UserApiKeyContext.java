package com.coin.webcointrader.common.util;

public class UserApiKeyContext {

    private static final ThreadLocal<String> API_KEY = new ThreadLocal<>();
    private static final ThreadLocal<String> API_SECRET = new ThreadLocal<>();

    public static void set(String apiKey, String apiSecret) {
        API_KEY.set(apiKey);
        API_SECRET.set(apiSecret);
    }

    public static String getApiKey() {
        return API_KEY.get();
    }

    public static String getApiSecret() {
        return API_SECRET.get();
    }

    public static void clear() {
        API_KEY.remove();
        API_SECRET.remove();
    }
}
