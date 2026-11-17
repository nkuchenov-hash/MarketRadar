package com.marketradar.mobile;

/** Pure theme policy for the application's native surfaces. Marketplace sites
 * are not recolored: they remain controlled by their respective providers. */
final class ThemePalette {
    static final String SYSTEM="system", LIGHT="light", DARK="dark";
    final boolean dark;
    final int background, surface, border, foreground, muted, accent;

    private ThemePalette(boolean dark){
        this.dark=dark;
        background=dark?0xFF0C1422:0xFFF6F9FE;
        surface=dark?0xFF192639:0xFFFFFFFF;
        border=dark?0xFF30415A:0xFFDCE6F4;
        foreground=dark?0xFFF1F6FF:0xFF152844;
        muted=dark?0xFFAFBFD5:0xFF576A83;
        accent=dark?0xFF80ADFF:0xFF2369E8;
    }

    static String normalize(String requested){
        if(LIGHT.equals(requested)||DARK.equals(requested))return requested;
        return SYSTEM;
    }

    static ThemePalette resolve(String mode,boolean systemDark){
        String value=normalize(mode);
        return new ThemePalette(DARK.equals(value)||
            (SYSTEM.equals(value)&&systemDark));
    }
}
