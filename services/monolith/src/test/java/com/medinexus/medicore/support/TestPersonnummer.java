package com.medinexus.medicore.support;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Unique fictional personnummer for tests: birth dates in the 19th century (personnummer were introduced in 1947,
 * so no living person can have them), a random birth number and a deliberately <strong>wrong</strong> Luhn check
 * digit. MediCore does not validate the check digit, so every test that uses these numbers (account registration,
 * Patient registration, journal access matching and approval) also shows that the checksum plays no part. They
 * never collide with the Patients registered in tests, which use Skatteverket's published test personnummer.
 */
public final class TestPersonnummer {

    private static final Set<String> ISSUED = ConcurrentHashMap.newKeySet();
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private TestPersonnummer() {
    }

    /** A new personnummer, unique within this JVM. */
    public static String next() {
        while (true) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            LocalDate date = LocalDate.of(1800, 1, 1).plusDays(random.nextInt(36_000));
            String correct = withCheckDigit(date.format(DATE) + "%03d".formatted(random.nextInt(1000)));
            int wrongDigit = (correct.charAt(11) - '0' + 1 + random.nextInt(9)) % 10;
            String candidate = correct.substring(0, 11) + wrongDigit;
            if (ISSUED.add(candidate)) return candidate;
        }
    }

    /** True if the last digit is the correct Luhn check digit (never the case for {@link #next()}). */
    public static boolean hasCorrectCheckDigit(String twelveDigits) {
        return withCheckDigit(twelveDigits.substring(0, 11)).equals(twelveDigits);
    }

    /** Appends the Luhn check digit (over YYMMDDNNN) to an 11-digit YYYYMMDDNNN. */
    public static String withCheckDigit(String elevenDigits) {
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            int value = (elevenDigits.charAt(i + 2) - '0') * (i % 2 == 0 ? 2 : 1);
            sum += value > 9 ? value - 9 : value;
        }
        return elevenDigits + (10 - sum % 10) % 10;
    }
}
