package net.kyum.woori;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Apple design tokens — DESIGN_Apple_Consumer_Tech.md (oh-my-design.kr, verified 2026-07-11).
 * Light = apple.com light surfaces, Dark = Apple dark-surface variants (#0A84FF/#2997FF family).
 */
public final class Theme {
    public static boolean dark;

    public static int PRIMARY;    // filled action
    public static int LINK;       // outline action / link
    public static int CANVAS;     // panel background
    public static int SURFACE;    // white/content surfaces
    public static int FOREGROUND; // principal text
    public static int MUTED;      // secondary text
    public static int HAIRLINE;   // subtle border
    public static final int ON_PRIMARY = 0xFFFFFFFF;
    public static final int DANGER = 0xFFFF3B30; // Apple system red (exit)

    public static final float R_CONTROL = 8f;
    public static final float R_CARD = 18f;
    public static final float R_PILL = 999f;

    public static final int S_COMPACT = 8;
    public static final int S_CONTROL = 15;
    public static final int S_CONTENT = 20;

    public static final int H_LARGE = 44;
    public static final int H_COMPACT = 36;

    /** prefs에서 다크 여부 로드 후 팔레트를 교체한다. 호출 후 패널/버블을 다시 그리면 반영. */
    public static void load(Context c) {
        dark = c.getSharedPreferences(Rates.PREFS, 0).getBoolean("dark", false);
        if (dark) {
            PRIMARY = 0xFF0A84FF; LINK = 0xFF2997FF;
            CANVAS = 0xFF1C1C1E; SURFACE = 0xFF2C2C2E;
            FOREGROUND = 0xFFF5F5F7; MUTED = 0xFF98989D; HAIRLINE = 0xFF3A3A3C;
        } else {
            PRIMARY = 0xFF0071E3; LINK = 0xFF0066CC;
            CANVAS = 0xFFF5F5F7; SURFACE = 0xFFFFFFFF;
            FOREGROUND = 0xFF1D1D1F; MUTED = 0xFF6E6E73; HAIRLINE = 0xFFD2D2D7;
        }
    }

    public static void saveDark(Context c, boolean v) {
        c.getSharedPreferences(Rates.PREFS, 0).edit().putBoolean("dark", v).apply();
    }

    private Theme() {}
}
