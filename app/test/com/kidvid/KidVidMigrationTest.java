package com.kidvid;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class KidVidMigrationTest {

    private final List<File> toDelete = new ArrayList<>();
    private File tmpRoot;

    @Before
    public void setUp() throws IOException {
        tmpRoot = Files.createTempDirectory("kidvid-mig-").toFile();
        toDelete.add(tmpRoot);
    }

    @After
    public void tearDown() {
        deleteRecursively(tmpRoot);
    }

    @Test
    public void samePathIsSameFileAndMustNotBeDeleted() throws IOException {
        File video = writeFile(new File(tmpRoot, "clip.mp4"), "video-bytes");
        assertTrue(VideoMigration.isSameFile(video, video));
        assertTrue(VideoMigration.isSameFile(video, new File(video.getPath())));
        assertFalse(VideoMigration.shouldDeleteSourceAsDuplicate(video, video));
        assertTrue(video.exists());
        // The migrate loop must skip, not delete, when dest is the source.
        if (VideoMigration.shouldDeleteSourceAsDuplicate(video, video)) {
            video.delete();
        }
        assertTrue("library file must survive a self-duplicate check", video.exists());
    }

    @Test
    public void symlinkAliasToSameFileMustNotBeDeleted() throws IOException {
        File realDir = new File(tmpRoot, "emulated");
        File aliasDir = new File(tmpRoot, "sdcard");
        assertTrue(realDir.mkdirs());
        Files.createSymbolicLink(aliasDir.toPath(), realDir.toPath());
        File real = writeFile(new File(realDir, "clip.mp4"), "same-video");
        File alias = new File(aliasDir, "clip.mp4");
        assertTrue(alias.exists());
        assertTrue(VideoMigration.isSameFile(real, alias));
        assertFalse(VideoMigration.shouldDeleteSourceAsDuplicate(real, alias));
        assertFalse(VideoMigration.shouldDeleteSourceAsDuplicate(alias, real));
        assertTrue(real.exists());
    }

    @Test
    public void sameDirectoryViaAliasIsDetected() throws IOException {
        File realDir = new File(tmpRoot, "emulated/videos");
        File aliasDir = new File(tmpRoot, "sdcard");
        assertTrue(realDir.mkdirs());
        Files.createSymbolicLink(aliasDir.toPath(), realDir.getParentFile().toPath());
        File aliasVideos = new File(aliasDir, "videos");
        assertTrue(VideoMigration.isSameFile(realDir, aliasVideos));
    }

    @Test
    public void realDuplicateAtDifferentPathMayBeDeleted() throws IOException {
        File srcDir = new File(tmpRoot, "sdcard/videos");
        File destDir = new File(tmpRoot, "expand/videos");
        assertTrue(srcDir.mkdirs());
        assertTrue(destDir.mkdirs());
        File source = writeFile(new File(srcDir, "clip.mp4"), "copied-video");
        File dest = writeFile(new File(destDir, "clip.mp4"), "copied-video");
        assertFalse(VideoMigration.isSameFile(source, dest));
        assertTrue(VideoMigration.shouldDeleteSourceAsDuplicate(source, dest));
        if (VideoMigration.shouldDeleteSourceAsDuplicate(source, dest)) {
            assertTrue(source.delete());
        }
        assertFalse(source.exists());
        assertTrue("dest copy must remain", dest.exists());
        assertEquals("copied-video", readAscii(dest));
    }

    @Test
    public void differentLengthIsNotADuplicate() throws IOException {
        File source = writeFile(new File(tmpRoot, "a.mp4"), "short");
        File dest = writeFile(new File(tmpRoot, "b.mp4"), "a-much-longer-copy");
        assertFalse(VideoMigration.shouldDeleteSourceAsDuplicate(source, dest));
        assertTrue(source.exists());
    }

    @Test
    public void missingDestIsNotADuplicate() throws IOException {
        File source = writeFile(new File(tmpRoot, "a.mp4"), "video");
        File dest = new File(tmpRoot, "missing.mp4");
        assertFalse(VideoMigration.shouldDeleteSourceAsDuplicate(source, dest));
    }

    private static File writeFile(File file, String contents) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            assertTrue(parent.mkdirs());
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(contents.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private static String readAscii(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] kids = file.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursively(k);
            }
        }
        file.delete();
    }
}
