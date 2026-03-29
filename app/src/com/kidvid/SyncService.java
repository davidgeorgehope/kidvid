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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

/**
 * Background sync service for KidVid.
 * Syncs videos from remote HTTPS server (primary) or local mDNS (fallback):
 * - Downloads new videos from the server
 * - Sends DELETE after successful download (server-side cleanup)
 * Runs every 15 minutes via AlarmManager (exact + allow-while-idle to survive Doze).
 * Uses a foreground notification + wake lock to ensure downloads complete.
 */
public class SyncService extends Service {
    private static final String TAG = "KidVid.Sync";
    private static final String SERVICE_TYPE = "_kidvid._tcp.";
    private static final long SYNC_INTERVAL_MS = 15 * 60 * 1000; // 15 minutes
    private static final String CHANNEL_ID = "kidvid_sync";

    // Remote HTTPS server (Cloudflare tunnel to Hetzner)
    private static final String REMOTE_SERVER_URL = "https://files.signal.observer";

    // Where videos live on the SD card
    private static final String[] VIDEO_DIRS = {
        "/storage/AE60-81BC/kidvid/videos/",
        "/sdcard/kidvid/videos/"
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

        // Try remote HTTPS server first
        String baseUrl = null;
        boolean isRemote = false;

        Log.i(TAG, "Trying remote server: " + REMOTE_SERVER_URL);
        String remoteJson = httpGet(REMOTE_SERVER_URL + "/videos");
        if (remoteJson != null) {
            baseUrl = REMOTE_SERVER_URL;
            isRemote = true;
            Log.i(TAG, "Connected to remote server");
        } else {
            // Fall back to mDNS discovery
            Log.i(TAG, "Remote server unavailable, trying mDNS...");
            if (discoverServer()) {
                baseUrl = "http://" + serverHost + ":" + serverPort;
                Log.i(TAG, "Found local server at " + baseUrl);
                remoteJson = httpGet(baseUrl + "/videos");
            }
        }

        if (baseUrl == null || remoteJson == null) {
            Log.w(TAG, "No server available (remote or local), skipping sync");
            return;
        }

        try {
            JSONArray videos = new JSONArray(remoteJson);
            Set<String> serverFiles = new HashSet<>();

            // Download new videos
            for (int i = 0; i < videos.length(); i++) {
                JSONObject v = videos.getJSONObject(i);
                String name = v.getString("name");
                String url = v.getString("url");
                long size = v.getLong("size");
                serverFiles.add(name);

                // Build full URL (server returns relative paths)
                String fullUrl = baseUrl + url;

                File localFile = new File(videoDir, name);
                if (localFile.exists() && localFile.length() == size) {
                    Log.d(TAG, "Already have: " + name);
                    // If remote, delete from server since we already have it
                    if (isRemote) {
                        deleteFromServer(baseUrl + "/videos/" + name);
                    }
                    continue;
                }

                Log.i(TAG, "Downloading: " + name + " (" + (size / 1024 / 1024) + " MB)");
                boolean downloaded = downloadFile(fullUrl, localFile);

                // After successful download from remote, delete from server
                if (downloaded && isRemote) {
                    deleteFromServer(baseUrl + "/videos/" + name);
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
     * Send DELETE request to remove a video from the remote server.
     */
    private void deleteFromServer(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("DELETE");

            int code = conn.getResponseCode();
            if (code == 200) {
                Log.i(TAG, "Deleted from server: " + urlStr);
            } else {
                Log.w(TAG, "DELETE returned " + code + " for " + urlStr);
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "DELETE failed: " + urlStr, e);
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
     */
    private String findVideoDir() {
        for (String path : VIDEO_DIRS) {
            File dir = new File(path);
            if (dir.exists() && dir.canWrite()) {
                return path;
            }
        }
        for (String path : VIDEO_DIRS) {
            File dir = new File(path);
            if (dir.mkdirs() || dir.exists()) {
                return path;
            }
        }
        File storage = new File("/storage/");
        if (storage.exists()) {
            File[] mounts = storage.listFiles();
            if (mounts != null) {
                for (File mount : mounts) {
                    if (mount.getName().equals("emulated") || mount.getName().equals("self")) continue;
                    File vidDir = new File(mount, "kidvid/videos/");
                    if (vidDir.exists() || vidDir.mkdirs()) {
                        return vidDir.getAbsolutePath() + "/";
                    }
                }
            }
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
