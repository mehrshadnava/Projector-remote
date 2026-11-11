package com.projector.remote;

/** Maps a typed character to a US-layout HID keyboard usage. Run `java Keys.java` for the self-check. */
final class Keys {
    // Index i in each string is HID usage 0x04 + i; '\0' marks usages with no character.
    private static final String PLAIN = "abcdefghijklmnopqrstuvwxyz1234567890\n\u001b\b\t -=[]\\\0;'`,./";
    private static final String SHIFT = "ABCDEFGHIJKLMNOPQRSTUVWXYZ!@#$%^&*()\0\0\0\0\0_+{}|\0:\"~<>?";
    static final int SHIFT_MOD = 0x02;

    /** @return {modifier, usage}, or null if the character can't be typed. */
    static int[] forChar(char c) {
        if (c == 0) return null;
        int i = PLAIN.indexOf(c);
        if (i >= 0) return new int[] {0, 0x04 + i};
        i = SHIFT.indexOf(c);
        if (i >= 0) return new int[] {SHIFT_MOD, 0x04 + i};
        return null;
    }

    public static void main(String[] args) {
        check('a', 0, 0x04); check('z', 0, 0x1D); check('Z', 2, 0x1D);
        check('1', 0, 0x1E); check('0', 0, 0x27); check(')', 2, 0x27);
        check('\n', 0, 0x28); check(' ', 0, 0x2C); check('_', 2, 0x2D);
        check(';', 0, 0x33); check('"', 2, 0x34); check('/', 0, 0x38); check('?', 2, 0x38);
        if (PLAIN.length() != 53 || SHIFT.length() != 53) throw new AssertionError("table length");
        if (forChar('€') != null || forChar('\0') != null) throw new AssertionError("unmapped");
        System.out.println("Keys self-check OK");
    }

    private static void check(char c, int mod, int usage) {
        int[] k = forChar(c);
        if (k == null || k[0] != mod || k[1] != usage) throw new AssertionError("bad mapping for '" + c + "'");
    }
}
