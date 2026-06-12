package com.liskovsoft.smartyoutubetv2.mobile.update;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and compares SmarterTube release identifiers.
 *
 * <p>New scheme (see {@code docs/VERSIONING.md}):
 * <pre>v&lt;major&gt;.&lt;minor&gt;.&lt;patch&gt;[-&lt;channel&gt;.&lt;n&gt;]+st&lt;upstream&gt;</pre>
 * e.g. {@code v0.4.0-beta.1+st31.93}, {@code v1.0.0+st32.10}.
 *
 * <p>Legacy scheme (pre beta-reset):
 * <pre>&lt;upstream&gt;-mobile-&lt;suffix&gt;</pre>
 * e.g. {@code 31.93-mobile-1.4}, {@code 31.77-mobile-beta1}.
 *
 * <p>The SmarterTube product version (major/minor/patch + channel) is the ordering value.
 * The upstream SmartTube base ({@code +st...}) is metadata for display/diagnostics only and
 * never affects precedence. Legacy releases always sort older than any new-scheme release.
 *
 * <p>{@link #parse(String)} returns {@code null} for anything that is not a SmarterTube release
 * (e.g. an upstream SmartTube TV tag such as {@code 31.93}) or is malformed, so callers can
 * safely ignore it rather than crash.
 *
 * <p>Pure Java, no Android dependencies — unit-testable in isolation.
 */
public final class SmarterTubeVersion implements Comparable<SmarterTubeVersion> {
    /** Release channel, ordered least to most stable: alpha &lt; beta &lt; rc &lt; stable. */
    public enum Channel {
        ALPHA(0, "alpha"),
        BETA(1, "beta"),
        RC(2, "rc"),
        STABLE(3, "stable");

        public final int rank;
        public final String id;

        Channel(int rank, String id) {
            this.rank = rank;
            this.id = id;
        }

        public static Channel fromId(String id) {
            if (id != null) {
                for (Channel c : values()) {
                    if (c.id.equalsIgnoreCase(id)) {
                        return c;
                    }
                }
            }
            return STABLE;
        }
    }

    // v0.4.0-beta.1+st31.93  — channel suffix optional (absent => stable); upstream may carry a -beta tail.
    private static final Pattern NEW_SCHEME = Pattern.compile(
            "^v(\\d+)\\.(\\d+)\\.(\\d+)(?:-(alpha|beta|rc)\\.(\\d+))?\\+st(\\d+\\.\\d+(?:-[A-Za-z0-9.-]+)?)$");
    // 31.93-mobile-1.4 / 31.77-mobile-beta1
    private static final Pattern LEGACY_SCHEME = Pattern.compile(
            "^(\\d+\\.\\d+)-mobile-(.+)$");

    private final int major;
    private final int minor;
    private final int patch;
    private final Channel channel;
    private final int channelNumber; // N in "beta.N"; 0 for stable / legacy
    private final String upstreamBase; // e.g. "31.93" — metadata only, never compared
    private final boolean legacy;
    private final String raw;

    private SmarterTubeVersion(int major, int minor, int patch, Channel channel, int channelNumber,
                              String upstreamBase, boolean legacy, String raw) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.channel = channel;
        this.channelNumber = channelNumber;
        this.upstreamBase = upstreamBase;
        this.legacy = legacy;
        this.raw = raw;
    }

    /**
     * @return a parsed version, or {@code null} if {@code tag} is not a recognisable SmarterTube
     *         release (upstream-only or malformed tags return null and must be ignored).
     */
    public static SmarterTubeVersion parse(String tag) {
        if (tag == null) {
            return null;
        }
        String t = tag.trim();
        if (t.isEmpty()) {
            return null;
        }

        Matcher m = NEW_SCHEME.matcher(t);
        if (m.matches()) {
            int major = Integer.parseInt(m.group(1));
            int minor = Integer.parseInt(m.group(2));
            int patch = Integer.parseInt(m.group(3));
            String channelId = m.group(4); // null => stable
            Channel channel = channelId == null ? Channel.STABLE : Channel.fromId(channelId);
            int channelNumber = channelId == null ? 0 : Integer.parseInt(m.group(5));
            String upstream = m.group(6);
            return new SmarterTubeVersion(major, minor, patch, channel, channelNumber, upstream, false, t);
        }

        Matcher lm = LEGACY_SCHEME.matcher(t);
        if (lm.matches()) {
            String upstream = lm.group(1); // e.g. "31.93"
            // Every legacy "<x>-mobile-*" release was beta-quality regardless of any 1.x label.
            return new SmarterTubeVersion(0, 0, 0, Channel.BETA, 0, upstream, true, t);
        }

        return null; // not a SmarterTube release / malformed -> ignore safely
    }

    public int getMajor() {
        return major;
    }

    public int getMinor() {
        return minor;
    }

    public int getPatch() {
        return patch;
    }

    public Channel getChannel() {
        return channel;
    }

    public int getChannelNumber() {
        return channelNumber;
    }

    /** Upstream SmartTube base, e.g. {@code "31.93"}. Metadata only — for display/diagnostics. */
    public String getUpstreamBase() {
        return upstreamBase;
    }

    public boolean isLegacy() {
        return legacy;
    }

    public String getRaw() {
        return raw;
    }

    /** Product version without channel/upstream, e.g. {@code "0.4.0"}. */
    public String getProductVersion() {
        return major + "." + minor + "." + patch;
    }

    /**
     * Channel visibility: a user on {@code userChannel} sees this release if it is at least as
     * stable as their channel allows.
     * <ul>
     *   <li>stable: stable only</li>
     *   <li>beta: beta, rc, stable</li>
     *   <li>rc: rc, stable</li>
     *   <li>alpha: everything</li>
     * </ul>
     */
    public boolean isVisibleTo(Channel userChannel) {
        if (userChannel == null) {
            return false;
        }
        switch (userChannel) {
            case STABLE:
                return channel == Channel.STABLE;
            case RC:
                return channel.rank >= Channel.RC.rank;
            case BETA:
                return channel.rank >= Channel.BETA.rank;
            case ALPHA:
            default:
                return true;
        }
    }

    @Override
    public int compareTo(SmarterTubeVersion o) {
        // Legacy releases are always older than any new-scheme release.
        if (this.legacy != o.legacy) {
            return this.legacy ? -1 : 1;
        }
        if (this.legacy) {
            // Both legacy: best-effort ordering by upstream base then raw suffix. Not load-bearing
            // (versionCode drives Android install ordering); only needs legacy < new-scheme.
            int c = compareUpstream(this.upstreamBase, o.upstreamBase);
            return c != 0 ? c : this.raw.compareTo(o.raw);
        }
        // Both new-scheme: compare SmarterTube product version only. Upstream base (+st..) is
        // build metadata and MUST NOT affect precedence.
        int c = Integer.compare(major, o.major);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(minor, o.minor);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(patch, o.patch);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(channel.rank, o.channel.rank);
        if (c != 0) {
            return c;
        }
        return Integer.compare(channelNumber, o.channelNumber);
    }

    private static int compareUpstream(String a, String b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        String[] pa = a.split("[.-]");
        String[] pb = b.split("[.-]");
        int n = Math.min(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            try {
                int c = Integer.compare(Integer.parseInt(pa[i]), Integer.parseInt(pb[i]));
                if (c != 0) {
                    return c;
                }
            } catch (NumberFormatException e) {
                int c = pa[i].compareTo(pb[i]);
                if (c != 0) {
                    return c;
                }
            }
        }
        return Integer.compare(pa.length, pb.length);
    }

    @Override
    public String toString() {
        return raw;
    }
}
