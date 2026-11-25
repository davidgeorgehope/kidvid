package com.kidvid;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KidVidStickyReturnTest {

    @Test
    public void doesNotYankWhenScreenIsOff() {
        assertFalse(StickyReturnPolicy.shouldBringToFront(false, false, Boolean.FALSE));
    }

    @Test
    public void yanksWhenScreenIsOn() {
        assertTrue(StickyReturnPolicy.shouldBringToFront(false, false, Boolean.TRUE));
    }

    @Test
    public void yanksWhenInteractiveStateUnknown() {
        // Soft-pin dismiss on Fire can happen with the screen still on; if we
        // cannot read PowerManager, recover as before rather than staying away.
        assertTrue(StickyReturnPolicy.shouldBringToFront(false, false, null));
    }

    @Test
    public void neverYanksAfterParentExitOrFinish() {
        assertFalse(StickyReturnPolicy.shouldBringToFront(true, false, Boolean.TRUE));
        assertFalse(StickyReturnPolicy.shouldBringToFront(false, true, Boolean.TRUE));
        assertFalse(StickyReturnPolicy.shouldBringToFront(true, true, null));
        assertFalse(StickyReturnPolicy.shouldBringToFront(true, false, Boolean.FALSE));
    }
}
