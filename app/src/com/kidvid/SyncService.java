package com.kidvid;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.IBinder;
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

/**
 * Background sync service for KidVid.
 * Discovers the KidVid server via mDNS (NSD), then syncs videos:
 * - Downloads new videos from the server
 * - Deletes local videos no longer on the server
 * Runs every 15 minutes via AlarmManager.
 */
public class SyncService extends Service {
    private static final String TAG = "KidVid.Sync";
    private static final String SERVICE_TYPE = "_kidvid._tcp.";
    private static final long SYNC_INTERVAL_MS = 15 * 60 * 1000; // 15 minutes

    // Where videos live on the SD card
    private static final String[] VIDEO_DIRS = {
        "/storage/AE60-81BC/kidvid/videos/",
        "/sdcard/kidvid/videos/"
    };

    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private NsdManager nsdManager;
    private volatile String serverHost = null;
    private volatile int serverPort = 8642;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Sync service started");
        scheduleNextSync();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                doSync();
            }
        });
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    /**
     * Schedule the next sync via AlarmManager.
     */
    private void scheduleNextSync() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, SyncService.class);
        PendingIntent pi = PendingIntent.getService(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + SYNC_INTERVAL_MS, pi);
        Log.i(TAG, "Next sync scheduled in 15 minutes");
    }

    /**
     * Main sync logic: discover server, fetch list, download new, delete old.
     */
    private void doSync() {
        Log.i(TAG, "Starting sync...");

        // Step 1: Discover server via mDNS
        if (!discoverServer()) {
            Log.w(TAG, "Could not discover KidVid server, skipping sync");
            stopSelf();
            return;
        }

        String baseUrl = "http://" + serverHost + ":" + serverPort;
        Log.i(TAG, "Found server at " + baseUrl);

        // Step 2: Find writable video directory
        String videoDir = findVideoDir();
        if (videoDir == null) {
            Log.w(TAG, "No writable video directory found, skipping sync");
            stopSelf();
            return;
        }
        Log.i(TAG, "Using video dir: " + videoDir);

        try {
            // Step 3: Fetch video list from server
            String json = httpGet(baseUrl + "/videos");
            if (json == null) {
                Log.w(TAG, "Failed to fetch video list");
                stopSelf();
                return;
            }

            JSONArray videos = new JSONArray(json);
            Set<String> serverFiles = new HashSet<>();

            // Step 4: Download new videos
            for (int i = 0; i < videos.length(); i++) {
                JSONObject v = videos.getJSONObject(i);
                String name = v.getString("name");
                String url = v.getString("url");
                long size = v.getLong("size");
                serverFiles.add(name);

                File localFile = new File(videoDir, name);
                if (localFile.exists() && localFile.length() == size) {
                    Log.d(TAG, "Already have: " + name);
                    continue;
                }

                Log.i(TAG, "Downloading: " + name + " (" + (size / 1024 / 1024) + " MB)");
                downloadFile(url, localFile);
            }

            // Step 5: Delete videos no longer on server
            File dir = new File(videoDir);
            File[] localFiles = dir.listFiles();
            if (localFiles != null) {
                for (File f : localFiles) {
                    if (f.getName().toLowerCase().endsWith(".mp4") && !serverFiles.contains(f.getName())) {
                        Log.i(TAG, "Deleting removed video: " + f.getName());
                        f.delete();
                    }
                }
            }

            Log.i(TAG, "Sync complete. Server has " + videos.length() + " videos.");

        } catch (Exception e) {
            Log.e(TAG, "Sync failed", e);
        }

        stopSelf();
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
                // Resolve to get host/port
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
            // Stop discovery regardless
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
        // Try to create the first one
        for (String path : VIDEO_DIRS) {
            File dir = new File(path);
            if (dir.mkdirs() || dir.exists()) {
                return path;
            }
        }
        // Last resort: scan /storage for SD cards
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
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
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
     * Download a file from URL to local path. Uses a .tmp suffix during download.
     */
    private void downloadFile(String urlStr, File dest) {
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);

            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[1024 * 64]; // 64KB buffer
            int bytesRead;
            long total = 0;
            while ((bytesRead = in.read(buf)) != -1) {
                out.write(buf, 0, bytesRead);
                total += bytesRead;
            }
            out.close();
            in.close();
            conn.disconnect();

            // Atomic rename
            if (tmp.renameTo(dest)) {
                Log.i(TAG, "Downloaded: " + dest.getName() + " (" + (total / 1024 / 1024) + " MB)");
            } else {
                Log.e(TAG, "Failed to rename tmp file for: " + dest.getName());
                tmp.delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "Download failed: " + dest.getName(), e);
            tmp.delete();
        }
    }

    /**
     * Static helper to schedule the sync service from outside (BootReceiver, MainActivity).
     */
    public static void schedule(Context context) {
        Intent intent = new Intent(context, SyncService.class);
        context.startService(intent);
    }
}
