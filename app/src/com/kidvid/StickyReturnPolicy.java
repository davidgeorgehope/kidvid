package com.kidvid;

/**
 * When to yank KidVid back after a pause or lost focus.
 * Do not fight a screen-off pause: bringing the activity to front can
 * wake the display and block an idle APK install.
 */
final class StickyReturnPolicy {
    private StickyReturnPolicy() {}

    /**
     * @param screenInteractive {@code Boolean.FALSE} when the display is known
     *     off, {@code Boolean.TRUE} when on, {@code null} when unknown
     *     (still allow the sticky return so a soft-pin dismiss is recovered).
     */
    static boolean shouldBringToFront(
            boolean parentExitRequested,
            boolean finishing,
            Boolean screenInteractive) {
        if (parentExitRequested || finishing) {
            return false;
        }
        if (screenInteractive != null && !screenInteractive.booleanValue()) {
            return false;
        }
        return true;
    }
}
