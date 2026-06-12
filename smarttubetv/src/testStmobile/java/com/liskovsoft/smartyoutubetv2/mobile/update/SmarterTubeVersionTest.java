package com.liskovsoft.smartyoutubetv2.mobile.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.smartyoutubetv2.mobile.update.SmarterTubeVersion.Channel;

import org.junit.Test;

/**
 * Unit tests for {@link SmarterTubeVersion} — the parsing/comparison contract from
 * {@code docs/UPDATER_COMPATIBILITY.md}. Pure JUnit, no Android needed.
 *
 * Run with: gradlew :smarttubetv:testStmobileDebugUnitTest
 */
public class SmarterTubeVersionTest {

    // ---- Parsing of the new scheme ----

    @Test
    public void parsesNewSchemeBeta() {
        SmarterTubeVersion v = SmarterTubeVersion.parse("v0.4.0-beta.1+st31.93");
        assertEquals(0, v.getMajor());
        assertEquals(4, v.getMinor());
        assertEquals(0, v.getPatch());
        assertEquals(Channel.BETA, v.getChannel());
        assertEquals(1, v.getChannelNumber());
        assertEquals("31.93", v.getUpstreamBase());
        assertFalse(v.isLegacy());
    }

    @Test
    public void parsesNewSchemeBeta2() {
        SmarterTubeVersion v = SmarterTubeVersion.parse("v0.4.0-beta.2+st31.93");
        assertEquals(2, v.getChannelNumber());
        assertEquals(Channel.BETA, v.getChannel());
    }

    @Test
    public void parsesNewSchemeDifferentUpstream() {
        SmarterTubeVersion v = SmarterTubeVersion.parse("v0.4.1-beta.1+st31.94");
        assertEquals("0.4.1", v.getProductVersion());
        assertEquals("31.94", v.getUpstreamBase());
    }

    @Test
    public void parsesReleaseCandidate() {
        SmarterTubeVersion v = SmarterTubeVersion.parse("v1.0.0-rc.1+st32.10");
        assertEquals(Channel.RC, v.getChannel());
        assertEquals(1, v.getChannelNumber());
        assertEquals("32.10", v.getUpstreamBase());
    }

    @Test
    public void parsesStableWithNoChannelSuffix() {
        SmarterTubeVersion v = SmarterTubeVersion.parse("v1.0.0+st32.10");
        assertEquals(Channel.STABLE, v.getChannel());
        assertEquals(0, v.getChannelNumber());
        assertFalse(v.isLegacy());
    }

    @Test
    public void parsesUpstreamBetaTail() {
        SmarterTubeVersion v = SmarterTubeVersion.parse("v0.4.3-beta.1+st31.94-beta");
        assertEquals("31.94-beta", v.getUpstreamBase());
    }

    // ---- Ordering ----

    @Test
    public void alphaIsOlderThanBeta() {
        assertTrue(lt("v0.5.0-alpha.1+st31.96", "v0.5.0-beta.1+st31.96"));
    }

    @Test
    public void betaIsOlderThanRc() {
        assertTrue(lt("v1.0.0-beta.1+st32.10", "v1.0.0-rc.1+st32.10"));
    }

    @Test
    public void rcIsOlderThanStable() {
        assertTrue(lt("v1.0.0-rc.1+st32.10", "v1.0.0+st32.10"));
    }

    @Test
    public void beta2IsNewerThanBeta1() {
        assertTrue(lt("v0.4.0-beta.1+st31.93", "v0.4.0-beta.2+st31.93"));
    }

    @Test
    public void patchBumpIsNewer() {
        assertTrue(lt("v0.4.0-beta.1+st31.93", "v0.4.1-beta.1+st31.93"));
    }

    @Test
    public void buildMetadataDoesNotAffectPrecedence() {
        // Same product version, different upstream base -> equal precedence.
        SmarterTubeVersion a = SmarterTubeVersion.parse("v0.4.0-beta.1+st31.93");
        SmarterTubeVersion b = SmarterTubeVersion.parse("v0.4.0-beta.1+st31.94");
        assertEquals(0, a.compareTo(b));
    }

    @Test
    public void newerUpstreamDoesNotBeatOlderProductVersion() {
        // 0.4.1-beta.1 (older upstream) must still be newer than 0.4.0-beta.9 (newer upstream).
        assertTrue(lt("v0.4.0-beta.9+st31.94", "v0.4.1-beta.1+st31.93"));
    }

    // ---- Legacy migration ----

    @Test
    public void legacyTagIsRecognised() {
        SmarterTubeVersion v = SmarterTubeVersion.parse("31.93-mobile-1.4");
        assertTrue(v.isLegacy());
        assertEquals("31.93", v.getUpstreamBase());
        assertEquals(Channel.BETA, v.getChannel());
    }

    @Test
    public void legacyBeta1Recognised() {
        SmarterTubeVersion v = SmarterTubeVersion.parse("31.77-mobile-beta1");
        assertTrue(v.isLegacy());
        assertEquals("31.77", v.getUpstreamBase());
    }

    @Test
    public void legacyIsOlderThanBetaReset() {
        assertTrue(lt("31.93-mobile-1.4", "v0.4.0-beta.1+st31.93"));
    }

    @Test
    public void legacyDespite10LabelStillOlderThanBetaReset() {
        assertTrue(lt("31.88-mobile-1.0", "v0.4.0-beta.1+st31.93"));
    }

    // ---- Safe handling of unrelated tags ----

    @Test
    public void upstreamTvTagIsIgnored() {
        // Plain upstream SmartTube version, not a SmarterTube release.
        assertNull(SmarterTubeVersion.parse("31.93"));
        assertNull(SmarterTubeVersion.parse("v31.93-beta"));
    }

    @Test
    public void malformedTagsAreIgnored() {
        assertNull(SmarterTubeVersion.parse(null));
        assertNull(SmarterTubeVersion.parse(""));
        assertNull(SmarterTubeVersion.parse("   "));
        assertNull(SmarterTubeVersion.parse("not-a-version"));
        assertNull(SmarterTubeVersion.parse("v0.4-beta.1+st31.93")); // missing patch
        assertNull(SmarterTubeVersion.parse("v0.4.0-beta.1"));       // missing +st upstream
        assertNull(SmarterTubeVersion.parse("0.4.0-beta.1+st31.93")); // missing leading v
    }

    // ---- Channel visibility ----

    @Test
    public void stableChannelSeesStableOnly() {
        assertTrue(visible("v1.0.0+st32.10", Channel.STABLE));
        assertFalse(visible("v1.0.0-rc.1+st32.10", Channel.STABLE));
        assertFalse(visible("v0.4.0-beta.1+st31.93", Channel.STABLE));
        assertFalse(visible("v0.5.0-alpha.1+st31.96", Channel.STABLE));
    }

    @Test
    public void betaChannelSeesBetaRcStable() {
        assertTrue(visible("v0.4.0-beta.1+st31.93", Channel.BETA));
        assertTrue(visible("v1.0.0-rc.1+st32.10", Channel.BETA));
        assertTrue(visible("v1.0.0+st32.10", Channel.BETA));
        assertFalse(visible("v0.5.0-alpha.1+st31.96", Channel.BETA));
    }

    @Test
    public void alphaChannelSeesEverything() {
        assertTrue(visible("v0.5.0-alpha.1+st31.96", Channel.ALPHA));
        assertTrue(visible("v0.4.0-beta.1+st31.93", Channel.ALPHA));
        assertTrue(visible("v1.0.0-rc.1+st32.10", Channel.ALPHA));
        assertTrue(visible("v1.0.0+st32.10", Channel.ALPHA));
    }

    @Test
    public void legacyVisibleToBetaAndAlphaButNotStable() {
        assertTrue(visible("31.93-mobile-1.4", Channel.BETA));
        assertTrue(visible("31.93-mobile-1.4", Channel.ALPHA));
        assertFalse(visible("31.93-mobile-1.4", Channel.STABLE));
    }

    // ---- helpers ----

    private static boolean lt(String older, String newer) {
        return SmarterTubeVersion.parse(older).compareTo(SmarterTubeVersion.parse(newer)) < 0;
    }

    private static boolean visible(String tag, Channel userChannel) {
        return SmarterTubeVersion.parse(tag).isVisibleTo(userChannel);
    }
}
