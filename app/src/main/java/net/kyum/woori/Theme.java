package net.kyum.woori;

/**
 * Apple design tokens — DESIGN_Apple_Consumer_Tech.md (oh-my-design.kr, verified 2026-07-11).
 * Marketing web values used as inspiration for native surfaces per its agent guide.
 */
public final class Theme {
    // colors
    public static final int PRIMARY    = 0xFF0071E3; // filled action
    public static final int LINK       = 0xFF0066CC; // outline action / link on light
    public static final int CANVAS     = 0xFFF5F5F7; // fog canvas (panel background)
    public static final int SURFACE    = 0xFFFFFFFF; // white content surfaces
    public static final int FOREGROUND = 0xFF1D1D1F; // principal text
    public static final int MUTED      = 0xFF6E6E73; // secondary text
    public static final int ON_PRIMARY = 0xFFFFFFFF; // text on blue filled actions
    public static final int HAIRLINE   = 0xFFD2D2D7; // subtle divider (canvas family)

    // rounded: { control: 8, card: 18, pill: 980 → full circle }
    public static final float R_CONTROL = 8f;
    public static final float R_CARD = 18f;
    public static final float R_PILL = 999f;

    // spacing: { compact: 8, control-inline: 15, content: 20 }
    public static final int S_COMPACT = 8;
    public static final int S_CONTROL = 15;
    public static final int S_CONTENT = 20;

    // control heights
    public static final int H_LARGE = 44;   // marketing-primary 44px pill
    public static final int H_COMPACT = 36; // marketing-compact 36px pill

    private Theme() {}
}
