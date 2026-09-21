package com.juken5.autoafr;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Interoperability helper reconstructed from the user-provided Juken 5 APK 2.2.0
 * bytecode analysis.
 *
 * This class intentionally does not guess CRC/checksum bytes.
 * Transmission is kept separate so callers can implement a verification gate.
 */
public final class Juken52Protocol {
    private Juken52Protocol() {}

    public static String build2602(String argument, int tpsRow, double[] values61) {
        if (argument == null) throw new IllegalArgumentException("argument == null");
        if (values61 == null || values61.length != 61) {
            throw new IllegalArgumentException("2602 requires exactly 61 values");
        }
        if (tpsRow < 0) throw new IllegalArgumentException("tpsRow < 0");

        StringBuilder out = new StringBuilder(512);
        out.append("2602;")
           .append(argument)
           .append(';')
           .append(tpsRow)
           .append(';');

        for (int i = 0; i < values61.length; i++) {
            if (i > 0) out.append(';');
            out.append(String.format(Locale.US, "%.1f", values61[i]));
        }
        out.append("\r\n");
        return out.toString();
    }

    public static byte[] toAscii(String packet) {
        return packet.getBytes(StandardCharsets.US_ASCII);
    }
}
