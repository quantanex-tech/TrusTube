// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.core;

import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PinHash {
    private static final int ITERATIONS = 120000;
    private PinHash() {}
    public static String create(String pin) {
        if (pin == null || !pin.matches("[0-9]{6}"))
            throw new IllegalArgumentException("Choose a six-digit PIN.");
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        // SHA-1 PBKDF2 is available on the entire API-23 target; SHA-256 is preferred when present.
        String algorithm;
        try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256"); algorithm = "PBKDF2WithHmacSHA256"; }
        catch (Exception e) { algorithm = "PBKDF2WithHmacSHA1"; }
        return algorithm + ":" + hex(salt) + ":" + hex(derive(pin, salt, algorithm));
    }
    public static boolean verify(String pin, String encoded) {
        if (pin == null || !pin.matches("[0-9]{6}")) return false;
        try {
            String[] parts = encoded.split(":");
            if (parts.length != 3 || !(parts[0].equals("PBKDF2WithHmacSHA256")
                    || parts[0].equals("PBKDF2WithHmacSHA1"))) return false;
            return MessageDigest.isEqual(unhex(parts[2]), derive(pin, unhex(parts[1]), parts[0]));
        } catch (RuntimeException e) { return false; }
    }
    private static byte[] derive(String pin, byte[] salt, String algorithm) {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance(algorithm).generateSecret(spec).getEncoded(); }
        catch (Exception e) { throw new IllegalStateException("PIN storage is unavailable.", e); }
        finally { spec.clearPassword(); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    private static byte[] unhex(String text) {
        if ((text.length() & 1) != 0) throw new IllegalArgumentException();
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int hi = Character.digit(text.charAt(i * 2), 16), lo = Character.digit(text.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException();
            bytes[i] = (byte) (hi * 16 + lo);
        }
        return bytes;
    }
}
