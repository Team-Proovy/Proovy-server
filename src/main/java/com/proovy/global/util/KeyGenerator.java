package com.proovy.global.util;

import java.util.UUID;

public class KeyGenerator {

    private KeyGenerator() {
        // Utility class
    }

    public static String generateKey() {
        return UUID.randomUUID().toString();
    }
}

