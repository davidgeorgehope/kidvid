package com.kidvid;

import java.io.File;
import java.io.IOException;

/**
 * Helpers for {@link SyncService#migrateVideosOntoPreferredDir}.
 * Never treat a file as a "duplicate source" of itself (including via
 * /sdcard vs /storage/emulated/0 or other aliases), or the library is deleted.
 */
final class VideoMigration {
    private VideoMigration() {}

    static boolean isSameFile(File a, File b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (IOException e) {
            return a.getAbsoluteFile().equals(b.getAbsoluteFile());
        }
    }

    /**
     * True when {@code dest} already holds a copy of {@code source} at a
     * different path, so the source may be deleted after a migrate.
     * False when dest is missing, a different size, or the same file.
     */
    static boolean shouldDeleteSourceAsDuplicate(File source, File dest) {
        if (source == null || dest == null) {
            return false;
        }
        if (isSameFile(source, dest)) {
            return false;
        }
        return source.isFile() && dest.isFile() && dest.length() == source.length();
    }
}
