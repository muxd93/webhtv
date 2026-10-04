package com.fongmi.android.tv.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** scheme 路由回归：file 单斜杠是历史坏数据的主要形态,任意形态都必须落到本机 /file/ 服务。 */
public class UrlSchemesTest {

    private static final String ASSETS = "http://127.0.0.1:9978/";
    private static final String FILE = "http://127.0.0.1:9978/file/";
    private static final String PROXY = "http://127.0.0.1:9978/proxy?";

    @Test
    public void singleSlashFileConverts() {
        assertEquals(FILE + "/TV/x.txt", UrlSchemes.convert("file:/TV/x.txt", ASSETS, FILE, PROXY));
    }

    @Test
    public void tripleSlashFileConverts() {
        assertEquals(FILE + "/storage/emulated/0/TV/x.txt", UrlSchemes.convert("file:///storage/emulated/0/TV/x.txt", ASSETS, FILE, PROXY));
    }

    @Test
    public void localhostHostIsStripped() {
        assertEquals(FILE + "/TVBox/bc.json", UrlSchemes.convert("file://localhost/TVBox/bc.json", ASSETS, FILE, PROXY));
        assertEquals(FILE + "/TVBox/bc.json", UrlSchemes.convert("file://LOCALHOST/TVBox/bc.json", ASSETS, FILE, PROXY));
    }

    @Test
    public void doubleSlashFileConverts() {
        assertEquals(FILE + "/TV/x.txt", UrlSchemes.convert("file://TV/x.txt", ASSETS, FILE, PROXY));
    }

    @Test
    public void schemeIsCaseInsensitive() {
        assertEquals(FILE + "/TV/x.txt", UrlSchemes.convert("FILE:/TV/x.txt", ASSETS, FILE, PROXY));
    }

    @Test
    public void assetsAndProxyKeepOriginalContract() {
        assertEquals(ASSETS + "live.txt", UrlSchemes.convert("assets://live.txt", ASSETS, FILE, PROXY));
        assertEquals(PROXY + "do=ck&url=x", UrlSchemes.convert("proxy://do=ck&url=x", ASSETS, FILE, PROXY));
    }

    @Test
    public void httpAndRelativePassThrough() {
        assertEquals("http://example.com/x.txt", UrlSchemes.convert("http://example.com/x.txt", ASSETS, FILE, PROXY));
        assertEquals("./live.txt", UrlSchemes.convert("./live.txt", ASSETS, FILE, PROXY));
    }

    @Test
    public void nullAndEmptySafe() {
        assertEquals("", UrlSchemes.convert(null, ASSETS, FILE, PROXY));
        assertEquals("", UrlSchemes.convert("  ", ASSETS, FILE, PROXY));
    }
}
