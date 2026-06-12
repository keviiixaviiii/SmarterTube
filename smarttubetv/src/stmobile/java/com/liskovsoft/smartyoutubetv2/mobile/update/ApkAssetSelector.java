package com.liskovsoft.smartyoutubetv2.mobile.update;

import java.util.List;
import java.util.Locale;

/**
 * Picks the best-matching APK asset for the device ABI from a release's asset list.
 *
 * <p>Priority (see {@code docs/UPDATER_COMPATIBILITY.md}):
 * <ol>
 *   <li>Exact ABI match (e.g. {@code arm64-v8a})</li>
 *   <li>Universal APK</li>
 *   <li>None — caller must surface a clear "no compatible asset" state, never crash and never
 *       download a foreign/random asset.</li>
 * </ol>
 *
 * <p>Recognises the documented naming {@code SmarterTube-<ver>-st<up>-<abi>.apk} but matches on
 * the ABI token appearing anywhere in the asset name, so it tolerates minor naming drift.
 *
 * <p>Pure Java, no Android dependencies — unit-testable in isolation.
 */
public final class ApkAssetSelector {
    private ApkAssetSelector() {
    }

    public static final String UNIVERSAL = "universal";

    // Most specific first so "x86_64" wins over "x86".
    private static final String[] KNOWN_ABIS = {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"};

    /**
     * @param assetNames the {@code .apk} asset names available for a release
     * @param deviceAbi  the device's primary ABI (e.g. {@code arm64-v8a}); may be null/empty
     * @return the chosen asset name, or {@code null} if no compatible asset exists
     */
    public static String select(List<String> assetNames, String deviceAbi) {
        if (assetNames == null || assetNames.isEmpty()) {
            return null;
        }

        // 1. exact ABI match
        if (deviceAbi != null && !deviceAbi.isEmpty()) {
            String abi = deviceAbi.toLowerCase(Locale.ROOT);
            for (String name : assetNames) {
                if (isApk(name) && abi.equals(abiOf(name))) {
                    return name;
                }
            }
        }

        // 2. universal fallback
        for (String name : assetNames) {
            if (isApk(name) && UNIVERSAL.equals(abiOf(name))) {
                return name;
            }
        }

        // 3. nothing compatible
        return null;
    }

    private static boolean isApk(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".apk");
    }

    /**
     * Extracts the ABI token from an asset name: a known ABI, {@code "universal"}, or {@code ""}
     * when neither is present.
     */
    static String abiOf(String name) {
        if (name == null) {
            return "";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String abi : KNOWN_ABIS) {
            if (lower.contains(abi)) {
                return abi;
            }
        }
        if (lower.contains(UNIVERSAL)) {
            return UNIVERSAL;
        }
        return "";
    }
}
