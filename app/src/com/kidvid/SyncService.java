package com.kidvid;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Background sync service for KidVid.
 * Syncs videos from remote HTTPS server (primary) or local mDNS (fallback):
 * - Applies pending remote deletes (GET /deletes?device=...) then acks them
 * - Lists shared library via GET /videos?device=<id> (only unacked files)
 * - Downloads missing videos; PUT /acked/<name>?device=<id> (never DELETE library)
 * Runs every 15 minutes via AlarmManager (exact + allow-while-idle to survive Doze).
 * Uses a foreground notification + wake lock to ensure downloads complete.
 */
public class SyncService extends Service {
    private static final String TAG = "KidVid.Sync";
    private static final String SERVICE_TYPE = "_kidvid._tcp.";
    private static final long SYNC_INTERVAL_MS = 15 * 60 * 1000; // 15 minutes
    private static final String CHANNEL_ID = "kidvid_sync";
    private static final String PREFS_NAME = "kidvid";
    private static final String PREF_DEVICE_ID = "device_id";

    // Remote HTTPS server (Cloudflare tunnel to Hetzner)
    private static final String REMOTE_SERVER_URL = "https://files.signal.observer";

    // Where videos live — internal storage first (SD card has EPERM on Android 11)
    private static final String[] VIDEO_DIRS = {
        "/sdcard/kidvid/videos/",
        "/storage/AE60-81BC/kidvid/videos/"
    };

    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private NsdManager nsdManager;
    private volatile String serverHost = null;
    private volatile int serverPort = 8642;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Sync service started");

        // Start as foreground service so Android doesn't kill us mid-download
        Notification notification = buildNotification("Syncing videos...");
        startForeground(1, notification);

        // Acquire wake lock so CPU stays on during download
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kidvid:sync");
        wakeLock.acquire(10 * 60 * 1000L); // 10 min max

        scheduleNextSync();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    doSync();
                } finally {
                    if (wakeLock != null && wakeLock.isHeld()) {
                        wakeLock.release();
                    }
                    stopForeground(true);
                    stopSelf();
                }
            }
        });
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        executor.shutdownNow();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "KidVid Sync", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Video sync progress");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
            .setContentTitle("KidVid")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build();
    }

    /**
     * Schedule the next sync via AlarmManager.
     * Uses setExactAndAllowWhileIdle to survive Doze mode.
     * Routes through SyncAlarmReceiver so we can call startForegroundService()
     * (PendingIntent.getService() uses startService() which Android 11+ blocks).
     */
    private void scheduleNextSync() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, SyncAlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        long triggerAt = SystemClock.elapsedRealtime() + SYNC_INTERVAL_MS;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
        } else {
            alarmManager.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
        }
        Log.i(TAG, "Next sync scheduled in 15 minutes (exact, survives Doze)");
    }

    /**
     * Main sync logic: try remote HTTPS first, fall back to mDNS.
     */
    private void doSync() {
        Log.i(TAG, "Starting sync...");

        // Find writable video directory first
        String videoDir = findVideoDir();
        if (videoDir == null) {
            Log.w(TAG, "No writable video directory found, skipping sync");
            return;
        }
        Log.i(TAG, "Using video dir: " + videoDir);

        String device = deviceId(this);
        Log.i(TAG, "Device id: " + device);

        // Try remote HTTPS server first (health/list probe)
        String baseUrl = null;

        Log.i(TAG, "Trying remote server: " + REMOTE_SERVER_URL);
        String probe = httpGet(REMOTE_SERVER_URL + "/health");
        if (probe == null) {
            probe = httpGet(REMOTE_SERVER_URL + "/videos?device=" + device);
        }
        if (probe != null) {
            baseUrl = REMOTE_SERVER_URL;
            Log.i(TAG, "Connected to remote server");
        } else {
            // Fall back to mDNS discovery
            Log.i(TAG, "Remote server unavailable, trying mDNS...");
            if (discoverServer()) {
                baseUrl = "http://" + serverHost + ":" + serverPort;
                Log.i(TAG, "Found local server at " + baseUrl);
            }
        }

        if (baseUrl == null) {
            Log.w(TAG, "No server available (remote or local), skipping sync");
            return;
        }

        // Server-driven deletes for files already on device
        Set<String> pendingDeletes = applyPendingDeletes(baseUrl, videoDir);

        // Shared library list filtered by this device's acks
        String remoteJson = httpGet(baseUrl + "/videos?device=" + device);
        if (remoteJson == null) {
            Log.w(TAG, "Failed to list /videos?device=" + device);
            return;
        }

        try {
            JSONArray videos = new JSONArray(remoteJson);

            // Download new videos; ack (do not DELETE) so other devices still see them
            for (int i = 0; i < videos.length(); i++) {
                JSONObject v = videos.getJSONObject(i);
                String name = v.getString("name");
                String url = v.getString("url");
                long size = v.getLong("size");

                // Never re-download something marked for remote delete
                if (pendingDeletes.contains(name)) {
                    Log.i(TAG, "Skip download (pending delete): " + name);
                    continue;
                }

                // Build full URL (server returns relative paths)
                String fullUrl;
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    fullUrl = url;
                } else {
                    fullUrl = baseUrl + url;
                }

                File localFile = new File(videoDir, name);
                if (localFile.exists() && localFile.length() == size) {
                    Log.d(TAG, "Already have: " + name);
                    ackDownload(baseUrl, name, device);
                    continue;
                }

                Log.i(TAG, "Downloading: " + name + " (" + (size / 1024 / 1024) + " MB)");
                boolean downloaded = downloadFile(fullUrl, localFile);

                // After successful download, ack — library stays until 7-day age-out
                if (downloaded) {
                    ackDownload(baseUrl, name, device);
                }
            }

            Log.i(TAG, "Sync complete. Processed " + videos.length() + " videos.");

        } catch (Exception e) {
            Log.e(TAG, "Sync failed", e);
        }

        // Notify any listening activity that sync is done
        Intent done = new Intent("com.kidvid.SYNC_DONE");
        sendBroadcast(done);
    }

    /**
     * Fetch pending deletes for this device (and legacy phone/fire buckets),
     * remove matching local files, ack the server.
     * Missing /deletes endpoint (older servers) is a no-op.
     */
    private Set<String> applyPendingDeletes(String baseUrl, String primaryVideoDir) {
        Set<String> pending = new HashSet<>();
        Set<String> buckets = new HashSet<>();
        buckets.add(deviceId(this));
        String legacy = legacyDeleteBucket();
        if (legacy != null) buckets.add(legacy);
        // Always check legacy phone/fire so CoS scripts that tee those still work
        buckets.add("phone");
        buckets.add("fire");

        for (String device : buckets) {
            applyPendingDeletesForDevice(baseUrl, primaryVideoDir, device, pending);
        }
        return pending;
    }

    private void applyPendingDeletesForDevice(
            String baseUrl, String primaryVideoDir, String device, Set<String> pending) {
        String json = httpGet(baseUrl + "/deletes?device=" + device);
        if (json == null) return;

        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String name = arr.optString(i, null);
                if (name == null || name.isEmpty()) continue;
                if (name.contains("/") || name.contains("\\") || name.contains("..")) {
                    Log.w(TAG, "Ignoring unsafe pending delete name: " + name);
                    continue;
                }
                pending.add(name);
                deleteLocalCopies(name, primaryVideoDir);
                if (!localCopyExists(name, primaryVideoDir)) {
                    deleteFromServerUrl(baseUrl + "/deletes/" + name + "?device=" + device);
                } else {
                    Log.w(TAG, "Local delete incomplete for " + name + "; will retry next sync");
                }
            }
        } catch (Exception e) {
            try {
                JSONObject obj = new JSONObject(json);
                JSONArray arr = obj.optJSONArray(device);
                if (arr == null) return;
                for (int i = 0; i < arr.length(); i++) {
                    String name = arr.optString(i, null);
                    if (name == null || name.isEmpty()) continue;
                    if (name.contains("/") || name.contains("\\") || name.contains("..")) continue;
                    pending.add(name);
                    deleteLocalCopies(name, primaryVideoDir);
                    if (!localCopyExists(name, primaryVideoDir)) {
                        deleteFromServerUrl(baseUrl + "/deletes/" + name + "?device=" + device);
                    }
                }
            } catch (Exception e2) {
                Log.e(TAG, "Failed to parse /deletes for " + device, e);
            }
        }
    }

    /** Legacy delete bucket hint (phone vs fire) for CoS scripts that still tee those labels. */
    private static String legacyDeleteBucket() {
        String blob = ((Build.MANUFACTURER == null ? "" : Build.MANUFACTURER) + " "
                + (Build.MODEL == null ? "" : Build.MODEL) + " "
                + (Build.PRODUCT == null ? "" : Build.PRODUCT)).toLowerCase();
        if (blob.contains("amazon") || blob.contains("kf") || blob.contains("fire")) {
            return "fire";
        }
        return "phone";
    }

    /**
     * Delete filename from the sync dir and other known KidVid video locations.
     */
    private boolean deleteLocalCopies(String filename, String primaryVideoDir) {
        boolean any = false;
        for (String dir : videoSearchDirs(primaryVideoDir)) {
            File f = new File(dir, filename);
            if (f.exists()) {
                if (f.delete()) {
                    Log.i(TAG, "Deleted local: " + f.getAbsolutePath());
                    any = true;
                } else {
                    Log.w(TAG, "Failed to delete local: " + f.getAbsolutePath());
                }
            }
        }
        return any;
    }

    private boolean localCopyExists(String filename, String primaryVideoDir) {
        for (String dir : videoSearchDirs(primaryVideoDir)) {
            if (new File(dir, filename).exists()) return true;
        }
        return false;
    }

    private Set<String> videoSearchDirs(String primaryVideoDir) {
        Set<String> dirs = new HashSet<>();
        if (primaryVideoDir != null) dirs.add(primaryVideoDir);
        for (String p : VIDEO_DIRS) dirs.add(p);
        File appExt = getExternalFilesDir(null);
        if (appExt != null) dirs.add(new File(appExt, "videos").getAbsolutePath() + "/");
        dirs.add(new File(getFilesDir(), "videos").getAbsolutePath() + "/");
        dirs.add("/sdcard/kidvid/videos/");
        return dirs;
    }

    /**
     * Stable per-install device id for /videos?device= and /acked.
     * Prefer a friendly override in SharedPreferences ("pixel", "iphone-yellow");
     * otherwise generate once (fire → "fire-&lt;short&gt;", else "android-&lt;short&gt;").
     * Also used for pending /deletes?device=.
     */
    public static String deviceId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String existing = prefs.getString(PREF_DEVICE_ID, null);
        if (existing != null && !existing.trim().isEmpty()) {
            return existing.trim().toLowerCase();
        }
        String generated = suggestDeviceId();
        prefs.edit().putString(PREF_DEVICE_ID, generated).apply();
        return generated;
    }

    /** @deprecated Use {@link #deviceId(Context)}. */
    public static String deviceQueue() {
        return legacyDeleteBucket();
    }

    private static String suggestDeviceId() {
        String blob = ((Build.MANUFACTURER == null ? "" : Build.MANUFACTURER) + " "
                + (Build.MODEL == null ? "" : Build.MODEL) + " "
                + (Build.PRODUCT == null ? "" : Build.PRODUCT)).toLowerCase();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        if (blob.contains("amazon") || blob.contains("kf") || blob.contains("fire")) {
            return "fire-" + suffix;
        }
        if (blob.contains("pixel")) {
            return "pixel-" + suffix;
        }
        return "android-" + suffix;
    }

    /**
     * Parent-delete / CoS helper: DELETE library file on the server.
     * Requires ?parent=1 (Hetzner guard) so bare DELETEs from old sync clients are ignored.
     * Server also tees pending deletes for all known devices; we still tee
     * legacy phone+fire for older servers / CoS scripts.
     *
     * curl examples:
     *   curl -X DELETE "https://files.signal.observer/videos/SOME_FILE.mp4?parent=1"
     *   curl -X PUT "https://files.signal.observer/deletes/SOME_FILE.mp4"
     */
    public static boolean deleteRemoteVideo(String filename) {
        if (filename == null || filename.isEmpty()) return false;
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            Log.w(TAG, "Refusing DELETE with unsafe filename: " + filename);
            return false;
        }
        // Parent PIN only — sync path must never call this. ?parent=1 required by server guard.
        boolean libraryGone = deleteFromServerUrl(
                REMOTE_SERVER_URL + "/videos/" + filename + "?parent=1");
        // Extra tee for legacy buckets (no-op if server already covered them)
        boolean pendingPhone = queuePendingDelete(REMOTE_SERVER_URL, filename, "phone");
        boolean pendingFire = queuePendingDelete(REMOTE_SERVER_URL, filename, "fire");
        return libraryGone || pendingPhone || pendingFire;
    }

    /**
     * Record that this device has the file (download or size-match).
     * Does NOT delete the shared library copy.
     */
    private static boolean ackDownload(String baseUrl, String filename, String device) {
        if (filename == null || filename.isEmpty()) return false;
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) return false;
        String d = (device == null || device.isEmpty()) ? "android" : device;
        String urlStr = baseUrl + "/acked/" + filename + "?device=" + d;
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(0);
            int code = conn.getResponseCode();
            conn.disconnect();
            if (code == 200 || code == 201 || code == 204) {
                Log.i(TAG, "Acked download (HTTP " + code + "): " + urlStr);
                return true;
            }
            Log.w(TAG, "Ack returned " + code + " for " + urlStr);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Ack failed: " + urlStr, e);
            return false;
        }
    }

    /**
     * CoS / app: tee a durable pending delete on the server.
     */
    public static boolean queuePendingDelete(String baseUrl, String filename, String device) {
        if (filename == null || filename.isEmpty()) return false;
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) return false;
        String d = (device == null || device.isEmpty()) ? "phone" : device;
        String urlStr = baseUrl + "/deletes/" + filename + "?device=" + d;
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(0);
            int code = conn.getResponseCode();
            conn.disconnect();
            if (code == 200 || code == 201 || code == 204) {
                Log.i(TAG, "Queued pending delete (HTTP " + code + "): " + urlStr);
                return true;
            }
            // Older servers without /deletes — not fatal for parent PIN local delete
            Log.w(TAG, "Pending delete tee returned " + code + " for " + urlStr);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Pending delete tee failed: " + urlStr, e);
            return false;
        }
    }

    private static boolean deleteFromServerUrl(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("DELETE");

            int code = conn.getResponseCode();
            conn.disconnect();
            if (code == 200 || code == 404) {
                Log.i(TAG, "Deleted from server (HTTP " + code + "): " + urlStr);
                return true;
            }
            Log.w(TAG, "DELETE returned " + code + " for " + urlStr);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "DELETE failed: " + urlStr, e);
            return false;
        }
    }

    /**
     * Discover KidVid server via mDNS/NSD. Blocks up to 10 seconds.
     */
    private boolean discoverServer() {
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) return false;

        final CountDownLatch latch = new CountDownLatch(1);

        NsdManager.DiscoveryListener discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "mDNS discovery started");
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "mDNS found: " + serviceInfo.getServiceName());
                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo si, int errorCode) {
                        Log.w(TAG, "mDNS resolve failed: " + errorCode);
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo si) {
                        InetAddress host = si.getHost();
                        int port = si.getPort();
                        Log.i(TAG, "mDNS resolved: " + host.getHostAddress() + ":" + port);
                        serverHost = host.getHostAddress();
                        serverPort = port;
                        latch.countDown();
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "mDNS service lost");
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.d(TAG, "mDNS discovery stopped");
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "mDNS discovery start failed: " + errorCode);
                latch.countDown();
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "mDNS discovery stop failed: " + errorCode);
            }
        };

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);

        try {
            boolean found = latch.await(10, TimeUnit.SECONDS);
            try { nsdManager.stopServiceDiscovery(discoveryListener); } catch (Exception ignored) {}
            return found && serverHost != null;
        } catch (InterruptedException e) {
            try { nsdManager.stopServiceDiscovery(discoveryListener); } catch (Exception ignored) {}
            return false;
        }
    }

    /**
     * Find the first writable video directory.
     * Android 11 scoped storage blocks writes to shared/SD storage from services.
     * Use app-specific external storage as primary (no permissions needed).
     */
    private String findVideoDir() {
        // Try app-specific external storage first (no permissions needed, survives app updates)
        File appExtDir = new File(getExternalFilesDir(null), "videos");
        if (appExtDir.exists() || appExtDir.mkdirs()) {
            Log.i(TAG, "Using app-specific external storage: " + appExtDir.getAbsolutePath());
            return appExtDir.getAbsolutePath() + "/";
        }

        // Fallback to configured paths
        for (String path : VIDEO_DIRS) {
            File dir = new File(path);
            if (dir.exists() && dir.canWrite()) {
                // Test actual write
                try {
                    File test = new File(dir, ".write_test");
                    if (test.createNewFile()) {
                        test.delete();
                        return path;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Write test failed for " + path + ": " + e.getMessage());
                }
            }
        }

        // Last resort: app internal storage
        File intDir = new File(getFilesDir(), "videos");
        if (intDir.exists() || intDir.mkdirs()) {
            return intDir.getAbsolutePath() + "/";
        }

        return null;
    }

    /**
     * Simple HTTP GET returning response body as string.
     */
    private String httpGet(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() != 200) {
                Log.w(TAG, "HTTP " + conn.getResponseCode() + " from " + urlStr);
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "HTTP GET failed: " + urlStr, e);
            return null;
        }
    }

    /**
     * Download a file from URL to local path. Returns true on success.
     * Uses generous timeouts for large video files over mobile/WiFi.
     */
    private boolean downloadFile(String urlStr, File dest) {
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(5 * 60 * 1000); // 5 min read timeout for large files

            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[1024 * 64];
            int bytesRead;
            long total = 0;
            while ((bytesRead = in.read(buf)) != -1) {
                out.write(buf, 0, bytesRead);
                total += bytesRead;
            }
            out.close();
            in.close();
            conn.disconnect();

            if (tmp.renameTo(dest)) {
                Log.i(TAG, "Downloaded: " + dest.getName() + " (" + (total / 1024 / 1024) + " MB)");
                return true;
            } else {
                Log.e(TAG, "Failed to rename tmp file for: " + dest.getName());
                tmp.delete();
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Download failed: " + dest.getName(), e);
            tmp.delete();
            return false;
        }
    }

    /**
     * Static helper to schedule the sync service from outside (BootReceiver, MainActivity).
     */
    public static void schedule(Context context) {
        Intent intent = new Intent(context, SyncService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}
