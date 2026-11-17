package com.marketradar.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ThemePaletteTest {
    @Test public void explicitDarkOverridesLightSystem(){
        ThemePalette p=ThemePalette.resolve(ThemePalette.DARK,false);
        assertTrue(p.dark);
        assertNotEquals(p.background,p.surface);
        assertNotEquals(p.background,p.foreground);
    }
    @Test public void explicitLightOverridesDarkSystem(){
        ThemePalette p=ThemePalette.resolve(ThemePalette.LIGHT,true);
        assertFalse(p.dark);
        assertNotEquals(p.background,p.surface);
        assertNotEquals(p.background,p.foreground);
    }
    @Test public void systemTracksBothModes(){
        assertFalse(ThemePalette.resolve(ThemePalette.SYSTEM,false).dark);
        assertTrue(ThemePalette.resolve(ThemePalette.SYSTEM,true).dark);
    }
    @Test public void invalidSavedPreferenceDefaultsToSystem(){
        assertEquals(ThemePalette.SYSTEM,ThemePalette.normalize(null));
        assertEquals(ThemePalette.SYSTEM,ThemePalette.normalize("darkish"));
        assertTrue(ThemePalette.resolve("unexpected",true).dark);
    }
    @Test public void paletteHasReadableContrast(){
        for(boolean systemNight:new boolean[]{false,true}){
            ThemePalette p=ThemePalette.resolve(ThemePalette.SYSTEM,systemNight);
            assertTrue(contrast(p.foreground,p.background)>=7.0);
            assertTrue(contrast(p.muted,p.background)>=4.5);
            assertTrue(contrast(p.foreground,p.surface)>=7.0);
        }
    }
    static double brightness(int color){
        double[] channels={((color>>16)&255)/255.0,
            ((color>>8)&255)/255.0,(color&255)/255.0};
        for(int i=0;i<channels.length;i++)
            channels[i]=channels[i]<=0.04045?
                channels[i]/12.92:Math.pow((channels[i]+0.055)/1.055,2.4);
        return 0.2126*channels[0]+0.7152*channels[1]+0.0722*channels[2];
    }
    static double contrast(int a,int b){
        double l1=brightness(a),l2=brightness(b);
        return (Math.max(l1,l2)+0.05)/(Math.min(l1,l2)+0.05);
    }
}
