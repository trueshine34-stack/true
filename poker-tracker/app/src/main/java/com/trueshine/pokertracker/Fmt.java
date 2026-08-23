package com.trueshine.pokertracker;

import java.util.Locale;

public final class Fmt {

    private Fmt() {}

    /** ЧЧ:ММ:СС */
    public static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    /** ММ:СС */
    public static String mmss(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    /** "2 ч 15 мин" */
    public static String human(long ms) {
        long m = Math.max(0, ms) / 60000;
        if (m < 60) return m + " мин";
        return (m / 60) + " ч " + (m % 60) + " мин";
    }

    /** Со знаком, без лишних нулей: +12,5 / -3 / 0 */
    public static String bb(double v) {
        double r = Math.round(v * 10.0) / 10.0;
        String num = (r == Math.rint(r))
                ? String.format(Locale.US, "%.0f", r)
                : String.format(Locale.US, "%.1f", r).replace('.', ',');
        if (r > 0) return "+" + num;
        return num;
    }

    public static String bbUnit(double v) { return bb(v) + " BB"; }
}
