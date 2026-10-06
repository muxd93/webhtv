package com.fongmi.android.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.github.catvod.utils.EpgNames;

import org.junit.Test;

/** EPG 兜底匹配键归一化:两侧(直播频道名/XMLTV id/display-name)用同一函数折叠后才可比。 */
public class EpgNamesTest {

    @Test
    public void caseAndSpacesCollapse() {
        assertEquals(EpgNames.normalize("CCTV-1"), EpgNames.normalize("cctv 1"));
        assertEquals(EpgNames.normalize("CCTV1"), EpgNames.normalize("cctv_1"));
    }

    @Test
    public void fullWidthFoldsToAscii() {
        assertEquals(EpgNames.normalize("CCTV1"), EpgNames.normalize("ＣＣＴＶ１"));
        assertEquals(EpgNames.normalize("CCTV1"), EpgNames.normalize("CCTV１"));
    }

    @Test
    public void separatorsAndBracketsRemoved() {
        assertEquals(EpgNames.normalize("CCTV1综合"), EpgNames.normalize("CCTV-1(综合)"));
        assertEquals(EpgNames.normalize("CCTV1综合"), EpgNames.normalize("【CCTV1】综合"));
        assertEquals(EpgNames.normalize("CCTV1综合"), EpgNames.normalize("CCTV1·综合"));
    }

    @Test
    public void traditionalFoldsToSimplifiedRegardlessOfLocale() {
        assertEquals(EpgNames.normalize("卫视"), EpgNames.normalize("衛視"));
    }

    @Test
    public void nullAndEmptyAndSeparatorOnlyGiveEmpty() {
        assertEquals("", EpgNames.normalize(null));
        assertEquals("", EpgNames.normalize(""));
        assertEquals("", EpgNames.normalize("---"));
        assertEquals("", EpgNames.normalize("  "));
    }

    @Test
    public void distinctNamesStayDistinct() {
        assertFalse(EpgNames.normalize("CCTV1").equals(EpgNames.normalize("CCTV2")));
        assertFalse(EpgNames.normalize("CCTV1").equals(EpgNames.normalize("CCTV1综合")));
    }
}
