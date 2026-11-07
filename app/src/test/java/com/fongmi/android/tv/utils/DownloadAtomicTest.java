package com.fongmi.android.tv.utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 验证 jar 下载的原子性契约：目标文件只在下载成功后才被替换，
 * 任何失败路径都不得删除或截断已有的目标文件（否则线上 jar 一旦下载失败，
 * 本地唯一可用副本会被毁掉，站点直接不可用）。
 */
public class DownloadAtomicTest {

    private File dir;
    private File target;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("download-atomic").toFile();
        target = new File(dir, "spider.jar");
        write(target, "OLD-JAR");
    }

    @After
    public void tearDown() {
        delete(dir);
    }

    @Test
    public void successReplacesTargetAtomically() throws IOException {
        File tmp = Download.tmp(target);
        write(tmp, "NEW-JAR");

        assertTrue(Download.install(tmp, target));

        assertEquals("NEW-JAR", read(target));
        assertFalse(tmp.exists());
    }

    @Test
    public void failureKeepsTargetUntouched() throws IOException {
        // 模拟下载失败：临时文件不存在（HTTP 404 时根本不会创建或立即被 discard）
        File tmp = Download.tmp(target);

        assertFalse(Download.install(tmp, target));
        assertEquals("OLD-JAR", read(target));
    }

    @Test
    public void incompleteDownloadKeepsTargetUntouched() throws IOException {
        // 模拟写了一半：临时文件存在但为空/不完整，install 必须拒绝替换
        File tmp = Download.tmp(target);
        assertTrue(tmp.createNewFile());

        assertFalse(Download.install(tmp, target));
        assertEquals("OLD-JAR", read(target));
    }

    @Test
    public void discardOnlyRemovesTmpFile() throws IOException {
        File tmp = Download.tmp(target);
        write(tmp, "PARTIAL");

        Download.discard(tmp);

        assertFalse(tmp.exists());
        assertEquals("OLD-JAR", read(target));
    }

    @Test
    public void tmpFileLivesNextToTarget() {
        // 同目录才能保证 rename 是同分区原子替换
        assertEquals(target.getAbsoluteFile().getParentFile(), Download.tmp(target).getParentFile());
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void delete(File file) {
        if (file == null) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
