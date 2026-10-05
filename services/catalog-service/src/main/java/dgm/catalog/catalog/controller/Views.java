package dgm.catalog.catalog.controller;

import java.util.LinkedHashMap;
import java.util.Map;

/** Общие фрагменты ответов каталога по components.yaml. */
final class Views {

    private Views() {
    }

    /** {@code Money}: сумма в копейках и код валюты. */
    static Map<String, Object> money(long amount, String currency) {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amount", amount);
        money.put("currency", currency);
        return money;
    }
}
