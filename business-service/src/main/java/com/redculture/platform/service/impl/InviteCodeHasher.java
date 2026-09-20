package com.redculture.platform.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class InviteCodeHasher {
    private InviteCodeHasher() { }

    static String hash(String code) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(code.trim().toUpperCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : bytes) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
