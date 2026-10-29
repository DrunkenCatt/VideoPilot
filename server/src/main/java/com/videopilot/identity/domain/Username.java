package com.videopilot.identity.domain;

import java.text.Normalizer;
import java.util.Locale;

public final class Username {

    private Username() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
