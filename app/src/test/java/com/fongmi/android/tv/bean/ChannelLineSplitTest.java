package com.fongmi.android.tv.bean;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

/** $线路名 后缀拆分：iptv-api 说明与源作者命名线路共用同一语法，拆分必须保名且不破坏 URL。 */
public class ChannelLineSplitTest {

    @Test
    public void plainUrlUntouched() {
        assertArrayEquals(new String[]{"http://a.m3u8", ""}, Channel.splitLine("http://a.m3u8"));
    }

    @Test
    public void lineNameExtracted() {
        assertArrayEquals(new String[]{"http://a.m3u8", "电信"}, Channel.splitLine("http://a.m3u8$电信"));
    }

    @Test
    public void dollarInUrlKept() {
        assertArrayEquals(new String[]{"http://a/?u=$http://b", ""}, Channel.splitLine("http://a/?u=$http://b"));
    }

    @Test
    public void dollarAtStartNotSplit() {
        assertArrayEquals(new String[]{"$name", ""}, Channel.splitLine("$name"));
    }

    @Test
    public void nullSafe() {
        assertArrayEquals(new String[]{"", ""}, Channel.splitLine(null));
    }
}
