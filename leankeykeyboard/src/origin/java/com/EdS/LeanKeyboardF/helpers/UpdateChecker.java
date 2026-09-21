package com.EdS.LeanKeyboardF.helpers;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.EdS.LeanKeyboardF.BuildConfig;
import com.EdS.LeanKeyboardF.R;
import com.EdS.LeanKeyboardF.utils.LeanKeyPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * "Check for update" action on the About screen - origin flavor only (see
 * AboutFragment). Never wired into the playstore flavor: apps distributed
 * through Play must be updated through Play's own mechanism, not by
 * downloading an APK from GitHub - see build.gradle's SUPPORTS_SELF_UPDATE.
 *
 * Full flow, in order, entirely in-app (no browser handoff):
 *   1. Check GitHub for a newer release than BuildConfig.VERSION_NAME.
 *   2. If newer: check the "install unknown apps" permission for this app
 *      (API 26+). If not granted, open the system settings screen for it
 *      and wait for the user to come back.
 *   3. Re-check the permission after returning from settings - only
 *      proceed if it's now actually granted.
 *   4. Download the matching APK asset fully to our own cache dir.
 *   5. Once the download is completely finished, launch the system
 *      installer on it via FileProvider.
 *
 * The only case this still can't do entirely in-app is if the release has
 * no asset matching this exact locale flavor (see
 * BuildConfig.UPDATE_ASSET_LOCALE_TAG) - there's nothing to download then,
 * so it falls back to opening the releases page for the user to sort out
 * manually rather than dead-ending.
 */
public final class UpdateChecker {
    private static final String TAG = "UpdateChecker";
    private static final String REPO = "AmakerGame/LeanKeyboardF";
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String RELEASES_LIST_API =
            "https://api.github.com/repos/" + REPO + "/releases";
    private static final String RELEASES_PAGE =
            "https://github.com/" + REPO + "/releases/latest";
    private static final int REQUEST_CODE_UNKNOWN_SOURCES = 4173;

    // Holds the one update we found while the user is off in system
    // settings granting the install permission (step 2-3 above). The
    // fragment/activity stay alive the whole time (Settings is a separate
    // task on top, ours is merely paused), so a static field is enough -
    // this never needs to survive process death.
    private static String sPendingDownloadUrl;
    private static String sPendingVersion;

    private UpdateChecker() {
    }

    public static void checkForUpdate(Fragment fragment) {
        Context appContext = fragment.requireContext().getApplicationContext();
        MessageHelpers.showMessage(appContext, R.string.update_checking_toast);

        boolean includePrerelease = LeanKeyPreferences.instance(appContext).isUnstableUpdatesEnabled();

        new Thread(() -> {
            try {
                JSONObject release = fetchLatestRelease(includePrerelease);
                String tagName = release.optString("tag_name", "");
                String latestVersion = (tagName.startsWith("v") || tagName.startsWith("V"))
                        ? tagName.substring(1)
                        : tagName;

                if (latestVersion.isEmpty()
                        || compareVersions(latestVersion, BuildConfig.VERSION_NAME) <= 0) {
                    Helpers.postOnUiThread(() ->
                            MessageHelpers.showMessage(appContext, R.string.update_latest_toast));
                    return;
                }

                String assetUrl = findMatchingAssetUrl(release.optJSONArray("assets"));

                if (assetUrl == null) {
                    // Nothing we can download ourselves for this locale
                    // combo - only case where we still fall back to a
                    // browser, since there's no file to hand the
                    // downloader in the first place.
                    Helpers.postOnUiThread(() -> {
                        MessageHelpers.showMessage(appContext, R.string.update_no_asset_toast);
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        // No browser on many Android TV boxes: show the
                        // address instead of crashing.
                        if (!Helpers.startIntent(appContext, intent)) {
                            MessageHelpers.showLongMessage(appContext, RELEASES_PAGE);
                        }
                    });
                    return;
                }

                String foundVersion = latestVersion;
                Helpers.postOnUiThread(() -> {
                    MessageHelpers.showMessage(appContext,
                            appContext.getString(R.string.update_found_toast, foundVersion));
                    proceedWithPermissionCheck(fragment, assetUrl, foundVersion);
                });
            } catch (Exception e) {
                Log.w(TAG, "Update check failed", e);
                Helpers.postOnUiThread(() ->
                        MessageHelpers.showMessage(appContext, R.string.update_error_toast));
            }
        }).start();
    }

    /** Called from AboutFragment.onActivityResult once the user comes back
     *  from the "install unknown apps" settings screen. Ignores resultCode
     *  entirely - that screen doesn't reliably report whether the toggle
     *  was actually flipped, so the only trustworthy thing is to ask
     *  PackageManager again directly. */
    public static void onReturnedFromPermissionSettings(Activity activity) {
        String url = sPendingDownloadUrl;
        String version = sPendingVersion;
        sPendingDownloadUrl = null;
        sPendingVersion = null;

        if (url == null || activity == null) {
            return;
        }

        if (!canInstallPackages(activity)) {
            MessageHelpers.showMessage(activity, R.string.update_permission_denied_toast);
            return;
        }

        startDownloadAndInstall(activity, url, version);
    }

    private static void proceedWithPermissionCheck(Fragment fragment, String assetUrl, String version) {
        Activity activity = fragment.getActivity();
        if (activity == null) {
            return;
        }

        if (!canInstallPackages(activity)) {
            sPendingDownloadUrl = assetUrl;
            sPendingVersion = version;
            MessageHelpers.showMessage(activity, R.string.update_permission_needed_toast);

            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            fragment.startActivityForResult(intent, REQUEST_CODE_UNKNOWN_SOURCES);
            return;
        }

        startDownloadAndInstall(activity, assetUrl, version);
    }

    /** Pre-O there's no per-app "install unknown apps" grant to check -
     *  the legacy system-wide "Unknown sources" toggle is handled by the
     *  installer UI itself if needed, not something this app can query or
     *  set. */
    private static boolean canInstallPackages(Activity activity) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || activity.getPackageManager().canRequestPackageInstalls();
    }

    private static void startDownloadAndInstall(Activity activity, String assetUrl, String version) {
        Context appContext = activity.getApplicationContext();
        MessageHelpers.showMessage(appContext, R.string.update_downloading_toast);

        new Thread(() -> {
            File apkFile = new File(appContext.getCacheDir(), "update.apk");
            try {
                downloadToFile(assetUrl, apkFile);
                Helpers.postOnUiThread(() -> installApk(appContext, apkFile));
            } catch (IOException e) {
                Log.w(TAG, "Update download failed", e);
                //noinspection ResultOfMethodCallIgnored
                apkFile.delete();
                Helpers.postOnUiThread(() ->
                        MessageHelpers.showMessage(appContext, R.string.update_download_error_toast));
            }
        }).start();
    }

    private static void downloadToFile(String urlStr, File dest) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);
        conn.setInstanceFollowRedirects(true);
        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            conn.disconnect();
        }
    }

    /** Only called once downloadToFile() has returned successfully, i.e.
     *  the whole APK is already on disk - the installer never sees a
     *  partial file. */
    private static void installApk(Context appContext, File apkFile) {
        Uri apkUri = FileProvider.getUriForFile(
                appContext, appContext.getPackageName() + ".fileprovider", apkFile);

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        appContext.startActivity(intent);
    }

    private static JSONObject fetchLatestRelease(boolean includePrerelease) throws IOException, JSONException {
        if (!includePrerelease) {
            return new JSONObject(fetch(LATEST_RELEASE_API));
        }

        // The plain list endpoint (unlike /releases/latest) includes
        // pre-releases, ordered newest-published-first; it still never
        // returns drafts. First element is simply the most recent release
        // of any kind.
        JSONArray releases = new JSONArray(fetch(RELEASES_LIST_API));
        if (releases.length() == 0) {
            throw new IOException("No releases found for " + REPO);
        }
        return releases.getJSONObject(0);
    }

    /**
     * Finds this variant's own release asset by the exact naming scheme
     * build.gradle uses for APK output filenames (see
     * UPDATE_ASSET_LOCALE_TAG) - e.g. "LeanKeyboardF_EN+UA_v1.2.3_r.apk"
     * for the en+uk locale flavor. Returns null (caller falls back to the
     * releases page) if the naming ever drifts or this locale combo simply
     * wasn't part of that release.
     */
    private static String findMatchingAssetUrl(JSONArray assets) {
        if (assets == null) {
            return null;
        }

        String prefix = "LeanKeyboardF" + BuildConfig.UPDATE_ASSET_LOCALE_TAG + "_v";
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String name = asset.optString("name", "");
            if (name.startsWith(prefix) && name.endsWith(".apk")) {
                return asset.optString("browser_download_url", null);
            }
        }
        return null;
    }

    private static String fetch(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Compares dot-separated version numbers, tolerating a semver-style
     * pre-release suffix after "-" (e.g. "1.2.0-beta", "2.0.0-rc.1") and
     * ignoring any "+build" metadata entirely, per semver: the numeric
     * core is compared first segment by segment (so "1.10" is newer than
     * "1.9", unlike a plain string compare), and only if the cores are
     * equal does the suffix decide - a pre-release is OLDER than the same
     * version without one ("1.2.0-beta" &lt; "1.2.0"); if both have a
     * suffix, they're compared lexically as a reasonable approximation
     * without pulling in a full semver library.
     * Returns &gt;0 if a is newer than b, 0 if equal, &lt;0 if a is older.
     */
    static int compareVersions(String a, String b) {
        String[] coreAndSuffixA = splitCoreAndSuffix(a);
        String[] coreAndSuffixB = splitCoreAndSuffix(b);

        int coreCmp = compareCores(coreAndSuffixA[0], coreAndSuffixB[0]);
        if (coreCmp != 0) {
            return coreCmp;
        }

        String suffixA = coreAndSuffixA[1];
        String suffixB = coreAndSuffixB[1];
        if (suffixA.isEmpty() && suffixB.isEmpty()) {
            return 0;
        }
        if (suffixA.isEmpty()) {
            return 1; // a has no suffix, b is a pre-release of the same core -> a is newer
        }
        if (suffixB.isEmpty()) {
            return -1;
        }
        return suffixA.compareTo(suffixB);
    }

    /** Splits "1.2.0-beta.1+5" into {"1.2.0", "beta.1"}; drops any "+build"
     *  metadata entirely first, since semver says it must never affect
     *  precedence (a version's build metadata being different doesn't make
     *  it newer or older). {"1.2.0", ""} if there's no "-". */
    private static String[] splitCoreAndSuffix(String version) {
        int plus = version.indexOf('+');
        String withoutBuildMeta = plus < 0 ? version : version.substring(0, plus);

        int dash = withoutBuildMeta.indexOf('-');
        return dash < 0
                ? new String[]{withoutBuildMeta, ""}
                : new String[]{withoutBuildMeta.substring(0, dash), withoutBuildMeta.substring(dash + 1)};
    }

    private static int compareCores(String a, String b) {
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");
        int len = Math.max(partsA.length, partsB.length);

        for (int i = 0; i < len; i++) {
            String segA = i < partsA.length ? partsA[i] : "0";
            String segB = i < partsB.length ? partsB[i] : "0";

            int cmp;
            try {
                cmp = Integer.compare(Integer.parseInt(segA), Integer.parseInt(segB));
            } catch (NumberFormatException e) {
                cmp = segA.compareTo(segB);
            }

            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }
}
