package com.kidvid;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;
import android.Manifest;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private VideoView videoView;
    private FrameLayout rootLayout;
    private GridView browserGrid;
    private View browserOverlay;
    private MediaPlayer currentPlayer;
    private List<String> videoFiles = new ArrayList<>();
    private List<String> videoTitles = new ArrayList<>();
    private int currentIndex = 0;
    private GestureDetector gestureDetector;
    private boolean isPaused = false;
    private boolean browserVisible = false;

    private static final String KIDVID_DIR = "/sdcard/kidvid/";
    private static final String VIDEOS_DIR = KIDVID_DIR + "videos/";
    private static final String MANIFEST_FILE = KIDVID_DIR + "manifest.json";

    private static final String[] SD_PATHS = {
        "/storage/AE60-81BC/kidvid/",
    };

    private String activeVideosDir = VIDEOS_DIR;
    private String activeManifest = MANIFEST_FILE;
    private static final int PERM_REQUEST = 100;

    // Seek bar
    private SeekBar seekBar;
    private Handler seekHandler = new Handler(Looper.getMainLooper());
    private boolean userSeeking = false;

    // Thumbnail cache
    private Map<String, Bitmap> thumbnailCache = new HashMap<>();
    private ExecutorService thumbExecutor = Executors.newFixedThreadPool(2);

    // Browse button
    private Button browseButton;

    // --- Lockdown / parent exit ---
    // Soft mode (no Device Owner): screen-pin + sticky bring-to-front.
    // Hard mode (Device Owner): full lock-task policies + preferred HOME.
    private boolean parentExitRequested = false;
    private boolean deviceOwnerMode = false;
    private final Handler stickyHandler = new Handler(Looper.getMainLooper());
    private static final long STICKY_RETURN_DELAY_MS = 250;
    private static final int PARENT_EXIT_TAPS = 7;
    private static final long PARENT_EXIT_WINDOW_MS = 2500;
    private static final float PARENT_EXIT_CORNER_FRACTION = 0.18f;
    private int parentExitTapCount = 0;
    private long parentExitWindowStart = 0;
    private final Runnable stickyReturnRunnable = new Runnable() {
        @Override
        public void run() {
            if (parentExitRequested || isFinishing()) return;
            // Don't fight a screen-off pause; resume + pin when the display wakes.
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH
                        && !pm.isInteractive()) {
                    return;
                }
            } catch (Exception ignored) {}
            bringKidVidToFront();
            pinScreen();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        videoView = findViewById(R.id.videoView);
        rootLayout = (FrameLayout) videoView.getParent();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();
        enableLockTask();
        addBrowseButton();
        addSeekBar();

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            private static final int SWIPE_THRESHOLD = 100;
            private static final int SWIPE_VELOCITY = 100;

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                if (e1 == null || e2 == null) return false;
                float dy = e2.getY() - e1.getY();
                float dx = e2.getX() - e1.getX();
                float adx = Math.abs(dx);
                float ady = Math.abs(dy);

                if (ady > SWIPE_THRESHOLD && Math.abs(vY) > SWIPE_VELOCITY && ady > adx) {
                    if (dy < 0) nextVideo();
                    else prevVideo();
                    return true;
                }
                return false;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                // Long press in bottom-left corner = parent exit (deliberate).
                // Elsewhere = rewind 10 seconds (existing kid gesture).
                if (e != null && isInParentExitCorner(e.getRawX(), e.getRawY())) {
                    requestParentExit();
                    return;
                }
                seekRelative(-10000);
            }
        });

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERM_REQUEST);
        } else {
            loadAndPlay();
        }

        // Start background sync service
        SyncService.schedule(this);
    }

    /**
     * Soft pin always; Device Owner hardens lock-task features when available.
     * Fire tablets often cannot set Device Owner without a factory reset — soft
     * mode + sticky return is the default path that still works.
     */
    private void enableLockTask() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, KidVidDeviceAdmin.class);
        deviceOwnerMode = dpm != null && dpm.isDeviceOwnerApp(getPackageName());

        if (deviceOwnerMode) {
            try {
                dpm.setLockTaskPackages(admin, new String[]{getPackageName()});
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Hide Home / Recents / notifications while locked in.
                    dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
                }
                try {
                    dpm.setKeyguardDisabled(admin, true);
                } catch (Exception ignored) {}
                try {
                    dpm.setStatusBarDisabled(admin, true);
                } catch (Exception ignored) {}
                // Prefer KidVid as HOME so Amazon launcher is less sticky.
                try {
                    IntentFilter homeFilter = new IntentFilter(Intent.ACTION_MAIN);
                    homeFilter.addCategory(Intent.CATEGORY_HOME);
                    homeFilter.addCategory(Intent.CATEGORY_DEFAULT);
                    dpm.addPersistentPreferredActivity(admin, homeFilter,
                        new ComponentName(this, MainActivity.class));
                } catch (Exception ignored) {}
            } catch (Exception e) {
                // Device owner APIs can still fail on quirky Fire builds.
            }
        }

        pinScreen();
    }

    private void pinScreen() {
        if (parentExitRequested || isFinishing()) return;
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int state = am.getLockTaskModeState();
                if (state != ActivityManager.LOCK_TASK_MODE_NONE) {
                    return;
                }
            }
            startLockTask();
        } catch (Exception e) {
            // Soft pin may need a one-time confirm dialog on first use.
        }
    }

    private void scheduleStickyReturn() {
        if (parentExitRequested || isFinishing()) return;
        stickyHandler.removeCallbacks(stickyReturnRunnable);
        stickyHandler.postDelayed(stickyReturnRunnable, STICKY_RETURN_DELAY_MS);
    }

    private void bringKidVidToFront() {
        try {
            Intent launch = new Intent(this, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(launch);
        } catch (Exception ignored) {}
    }

    private boolean isInParentExitCorner(float rawX, float rawY) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        float cornerW = w * PARENT_EXIT_CORNER_FRACTION;
        float cornerH = h * PARENT_EXIT_CORNER_FRACTION;
        return rawX <= cornerW && rawY >= (h - cornerH);
    }

    private void noteParentExitTap(float rawX, float rawY) {
        if (!isInParentExitCorner(rawX, rawY)) {
            parentExitTapCount = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - parentExitWindowStart > PARENT_EXIT_WINDOW_MS) {
            parentExitTapCount = 0;
            parentExitWindowStart = now;
        }
        parentExitTapCount++;
        if (parentExitTapCount >= PARENT_EXIT_TAPS) {
            parentExitTapCount = 0;
            requestParentExit();
        }
    }

    /** Deliberate parent exit: unpin, stop sticky return, leave the app. */
    private void requestParentExit() {
        if (parentExitRequested) return;
        parentExitRequested = true;
        stickyHandler.removeCallbacks(stickyReturnRunnable);
        try {
            stopLockTask();
        } catch (Exception ignored) {}
        Toast.makeText(this, "Parent exit", Toast.LENGTH_SHORT).show();
        finish();
    }

    // --- Seek Bar ---

    private void addSeekBar() {
        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setProgress(0);
        seekBar.setMinimumHeight(60);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM;
        params.setMargins(16, 0, 16, 8);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && videoView != null) {
                    int duration = videoView.getDuration();
                    if (duration > 0) {
                        int newPos = (int) ((long) progress * duration / 1000);
                        videoView.seekTo(newPos);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
            }
        });

        rootLayout.addView(seekBar, params);
        startSeekBarUpdater();
    }

    private void startSeekBarUpdater() {
        seekHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (videoView != null && videoView.isPlaying() && !userSeeking) {
                    int duration = videoView.getDuration();
                    if (duration > 0) {
                        int pos = videoView.getCurrentPosition();
                        seekBar.setProgress((int) ((long) pos * 1000 / duration));
                    }
                }
                seekHandler.postDelayed(this, 250);
            }
        }, 250);
    }

    // --- Browse Button ---

    private void addBrowseButton() {
        browseButton = new Button(this);
        browseButton.setText("\uD83C\uDFAC");
        browseButton.setTextSize(32);
        browseButton.setBackgroundColor(Color.argb(160, 40, 40, 60));
        browseButton.setTextColor(Color.WHITE);
        browseButton.setPadding(24, 16, 24, 16);
        browseButton.setMinWidth(0);
        browseButton.setMinHeight(0);
        browseButton.setMinimumWidth(0);
        browseButton.setMinimumHeight(0);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.setMargins(0, 24, 24, 0);

        browseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showBrowser();
            }
        });

        rootLayout.addView(browseButton, params);
    }

    // --- Video Browser ---

    private void showBrowser() {
        if (browserVisible) {
            hideBrowser();
            return;
        }
        loadVideoList();
        browserVisible = true;
        videoView.pause();
        if (browseButton != null) browseButton.setVisibility(View.GONE);
        if (seekBar != null) seekBar.setVisibility(View.GONE);

        browserOverlay = new LinearLayout(this);
        ((LinearLayout) browserOverlay).setOrientation(LinearLayout.VERTICAL);
        browserOverlay.setBackgroundColor(Color.argb(240, 20, 20, 30));
        browserOverlay.setClickable(true);

        // Title bar with sync button
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(16, 40, 16, 20);

        TextView titleBar = new TextView(this);
        titleBar.setText("\uD83C\uDFAC  Pick a Video!");
        titleBar.setTextColor(Color.WHITE);
        titleBar.setTextSize(20);
        titleBar.setGravity(Gravity.CENTER);
        titleRow.addView(titleBar,
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button syncButton = new Button(this);
        syncButton.setText("\uD83D\uDD04");
        syncButton.setTextSize(24);
        syncButton.setBackgroundColor(Color.argb(160, 40, 40, 60));
        syncButton.setTextColor(Color.WHITE);
        syncButton.setPadding(24, 8, 24, 8);
        syncButton.setMinWidth(0);
        syncButton.setMinHeight(0);
        syncButton.setMinimumWidth(0);
        syncButton.setMinimumHeight(0);
        syncButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "Syncing...", Toast.LENGTH_SHORT).show();
                SyncService.schedule(MainActivity.this);
                // Refresh the grid after a delay to pick up new files
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (browserVisible && browserGrid != null) {
                            loadVideoList();
                            ((ThumbnailAdapter) browserGrid.getAdapter()).notifyDataSetChanged();
                            Toast.makeText(MainActivity.this,
                                videoFiles.size() + " videos", Toast.LENGTH_SHORT).show();
                        }
                    }
                }, 5000);
            }
        });
        titleRow.addView(syncButton,
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ((LinearLayout) browserOverlay).addView(titleRow,
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        browserGrid = new GridView(this);
        browserGrid.setNumColumns(2);
        browserGrid.setVerticalSpacing(16);
        browserGrid.setHorizontalSpacing(16);
        browserGrid.setPadding(16, 8, 16, 16);
        browserGrid.setAdapter(new ThumbnailAdapter());
        browserGrid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                hideBrowser();
                playVideo(position);
            }
        });

        ((LinearLayout) browserOverlay).addView(browserGrid,
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rootLayout.addView(browserOverlay,
            new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void hideBrowser() {
        if (browserOverlay != null) {
            rootLayout.removeView(browserOverlay);
            browserOverlay = null;
        }
        browserVisible = false;
        browserGrid = null;
        if (browseButton != null) browseButton.setVisibility(View.VISIBLE);
        if (seekBar != null) seekBar.setVisibility(View.VISIBLE);
        if (!isPaused) videoView.start();
        hideSystemUI();
    }

    private class ThumbnailAdapter extends BaseAdapter {
        @Override
        public int getCount() { return videoFiles.size(); }
        @Override
        public Object getItem(int position) { return videoFiles.get(position); }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout cell;
            ImageView thumb;
            TextView label;

            if (convertView == null) {
                cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(8, 8, 8, 8);

                thumb = new ImageView(MainActivity.this);
                thumb.setTag("thumb");
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                int thumbHeight = (int)(getResources().getDisplayMetrics().widthPixels / 2.5);
                cell.addView(thumb, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, thumbHeight));

                label = new TextView(MainActivity.this);
                label.setTag("label");
                label.setTextColor(Color.WHITE);
                label.setTextSize(12);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(2);
                label.setPadding(4, 8, 4, 4);
                cell.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                cell = (LinearLayout) convertView;
                thumb = (ImageView) cell.findViewWithTag("thumb");
                label = (TextView) cell.findViewWithTag("label");
            }

            String path = videoFiles.get(position);
            String title = position < videoTitles.size() ? videoTitles.get(position) : "Video " + (position + 1);
            label.setText(title);
            cell.setBackgroundColor(position == currentIndex ? Color.argb(100, 100, 100, 255) : Color.argb(60, 255, 255, 255));

            if (thumbnailCache.containsKey(path)) {
                Bitmap bmp = thumbnailCache.get(path);
                if (bmp != null) thumb.setImageBitmap(bmp);
                else thumb.setBackgroundColor(Color.DKGRAY);
            } else {
                thumb.setBackgroundColor(Color.DKGRAY);
                final ImageView thumbRef = thumb;
                final String thumbPath = path;
                thumbExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                            mmr.setDataSource(thumbPath);
                            final Bitmap bmp = mmr.getFrameAtTime(5000000);
                            mmr.release();
                            thumbnailCache.put(thumbPath, bmp);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (bmp != null) thumbRef.setImageBitmap(bmp);
                                }
                            });
                        } catch (Exception e) {
                            thumbnailCache.put(thumbPath, null);
                        }
                    }
                });
            }

            return cell;
        }
    }

    // --- Playback ---

    private void seekRelative(int ms) {
        if (videoView == null) return;
        int current = videoView.getCurrentPosition();
        int duration = videoView.getDuration();
        int target = Math.max(0, Math.min(current + ms, duration));
        videoView.seekTo(target);
        if (isPaused) {
            videoView.start();
            isPaused = false;
        }
    }

    private void playVideo(int index) {
        if (videoFiles.isEmpty()) return;
        currentIndex = index;
        isPaused = false;

        String path = videoFiles.get(currentIndex);
        videoView.setVideoURI(Uri.parse(path));
        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                currentPlayer = mp;
                mp.setLooping(true);
                mp.start();
            }
        });
        videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                nextVideo();
                return true;
            }
        });
        videoView.start();
    }

    private void nextVideo() {
        if (videoFiles.isEmpty()) return;
        currentIndex = (currentIndex + 1) % videoFiles.size();
        playVideo(currentIndex);
    }

    private void prevVideo() {
        if (videoFiles.isEmpty()) return;
        currentIndex = (currentIndex - 1 + videoFiles.size()) % videoFiles.size();
        playVideo(currentIndex);
    }

    // --- Touch handling ---
    // Simplified: swipe up/down = next/prev, long press = rewind 10s
    // Seek bar handles scrubbing. That's it.

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (browserVisible) {
            return super.onTouchEvent(event);
        }
        gestureDetector.onTouchEvent(event);
        return super.onTouchEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            noteParentExitTap(event.getRawX(), event.getRawY());
        }
        if (browserVisible) {
            return super.dispatchTouchEvent(event);
        }
        // Let the button and seekbar handle their own touches
        if (browseButton != null && browseButton.getVisibility() == View.VISIBLE) {
            int[] loc = new int[2];
            browseButton.getLocationOnScreen(loc);
            float x = event.getRawX();
            float y = event.getRawY();
            if (x >= loc[0] && x <= loc[0] + browseButton.getWidth()
                && y >= loc[1] && y <= loc[1] + browseButton.getHeight()) {
                return super.dispatchTouchEvent(event);
            }
        }
        if (seekBar != null && seekBar.getVisibility() == View.VISIBLE) {
            int[] loc = new int[2];
            seekBar.getLocationOnScreen(loc);
            float x = event.getRawX();
            float y = event.getRawY();
            // Give seekbar a bigger touch target (extra padding above)
            if (x >= loc[0] && x <= loc[0] + seekBar.getWidth()
                && y >= loc[1] - 40 && y <= loc[1] + seekBar.getHeight()) {
                return super.dispatchTouchEvent(event);
            }
        }
        return onTouchEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (browserVisible) {
            hideBrowser();
            return;
        }
        // Swallow Back so kids cannot casually leave. Parents use corner exit.
        if (!parentExitRequested) {
            pinScreen();
            hideSystemUI();
        }
    }

    // --- Storage detection ---

    private void detectStorage() {
        for (String sdPath : SD_PATHS) {
            File sdVids = new File(sdPath + "videos/");
            if (sdVids.exists() && sdVids.isDirectory()) {
                File[] files = sdVids.listFiles();
                if (files != null && files.length > 0) {
                    activeVideosDir = sdPath + "videos/";
                    activeManifest = sdPath + "manifest.json";
                    return;
                }
            }
        }
        File storage = new File("/storage/");
        if (storage.exists()) {
            File[] mounts = storage.listFiles();
            if (mounts != null) {
                for (File mount : mounts) {
                    if (mount.getName().equals("emulated") || mount.getName().equals("self")) continue;
                    File sdVids = new File(mount, "kidvid/videos/");
                    if (sdVids.exists() && sdVids.isDirectory()) {
                        File[] files = sdVids.listFiles();
                        if (files != null && files.length > 0) {
                            activeVideosDir = sdVids.getAbsolutePath() + "/";
                            activeManifest = new File(mount, "kidvid/manifest.json").getAbsolutePath();
                            return;
                        }
                    }
                }
            }
        }
        activeVideosDir = VIDEOS_DIR;
        activeManifest = MANIFEST_FILE;
    }

    private void loadVideoList() {
        videoFiles.clear();
        videoTitles.clear();

        // Build a title lookup from all manifest files
        Map<String, String> titleMap = new HashMap<>();
        List<String> videoDirs = new ArrayList<>();

        // Collect all video directories
        for (String sdPath : SD_PATHS) {
            File sdVids = new File(sdPath + "videos/");
            if (sdVids.exists() && sdVids.isDirectory()) {
                videoDirs.add(sdVids.getAbsolutePath());
                loadManifestTitles(new File(sdPath + "manifest.json"), titleMap);
            }
        }
        // Scan /storage/ for additional mounts
        File storage = new File("/storage/");
        if (storage.exists()) {
            File[] mounts = storage.listFiles();
            if (mounts != null) {
                for (File mount : mounts) {
                    if (mount.getName().equals("emulated") || mount.getName().equals("self")) continue;
                    File sdVids = new File(mount, "kidvid/videos/");
                    String path = sdVids.getAbsolutePath();
                    if (sdVids.exists() && sdVids.isDirectory() && !videoDirs.contains(path)) {
                        videoDirs.add(path);
                        loadManifestTitles(new File(mount, "kidvid/manifest.json"), titleMap);
                    }
                }
            }
        }
        // Always include internal storage
        File internalVids = new File(VIDEOS_DIR);
        if (internalVids.exists() && internalVids.isDirectory() && !videoDirs.contains(internalVids.getAbsolutePath())) {
            videoDirs.add(internalVids.getAbsolutePath());
            loadManifestTitles(new File(MANIFEST_FILE), titleMap);
        }

        // Also scan app-specific external storage (where SyncService downloads to)
        File appExtVids = new File(getExternalFilesDir(null), "videos");
        if (appExtVids.exists() && appExtVids.isDirectory() && !videoDirs.contains(appExtVids.getAbsolutePath())) {
            videoDirs.add(appExtVids.getAbsolutePath());
        }

        // Scan all directories for video files
        for (String dirPath : videoDirs) {
            File dir = new File(dirPath);
            File[] files = dir.listFiles();
            if (files != null) {
                java.util.Arrays.sort(files);
                for (File f : files) {
                    String name = f.getName().toLowerCase();
                    if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm") || name.endsWith(".3gp")) {
                        String absPath = f.getAbsolutePath();
                        if (!videoFiles.contains(absPath)) {
                            videoFiles.add(absPath);
                            String title = titleMap.get(f.getName());
                            if (title == null) {
                                title = f.getName().replaceAll("\\.[^.]+$", "").replace("_", " ").replace("-", " ");
                            }
                            videoTitles.add(title);
                        }
                    }
                }
            }
        }

        // Update activeVideosDir to first directory that has videos
        if (!videoDirs.isEmpty()) {
            activeVideosDir = videoDirs.get(0) + "/";
        }
    }

    private void loadManifestTitles(File manifest, Map<String, String> titleMap) {
        if (!manifest.exists()) return;
        try {
            FileInputStream fis = new FileInputStream(manifest);
            byte[] data = new byte[(int) manifest.length()];
            fis.read(data);
            fis.close();
            String content = new String(data, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) return;
            JSONArray videos;
            if (content.startsWith("[")) {
                videos = new JSONArray(content);
            } else {
                JSONObject json = new JSONObject(content);
                videos = json.getJSONArray("videos");
            }
            for (int i = 0; i < videos.length(); i++) {
                JSONObject v = videos.getJSONObject(i);
                String filename = v.getString("filename");
                String title = v.optString("title", null);
                if (title != null) {
                    titleMap.put(filename, title);
                }
            }
        } catch (Exception e) {
            // Ignore bad manifest - we'll generate titles from filenames
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERM_REQUEST && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadAndPlay();
        }
    }

    private void loadAndPlay() {
        loadVideoList();
        if (!videoFiles.isEmpty()) {
            playVideo(currentIndex);
        }
    }

    private void hideSystemUI() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
            if (!parentExitRequested) pinScreen();
        } else if (!parentExitRequested) {
            scheduleStickyReturn();
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        scheduleStickyReturn();
    }

    @Override
    protected void onPause() {
        super.onPause();
        scheduleStickyReturn();
    }

    @Override
    protected void onResume() {
        super.onResume();
        stickyHandler.removeCallbacks(stickyReturnRunnable);
        hideSystemUI();
        if (!parentExitRequested) {
            pinScreen();
        }
        if (videoView != null && !videoView.isPlaying() && !isPaused && !browserVisible) {
            videoView.start();
        }
    }

    @Override
    protected void onDestroy() {
        stickyHandler.removeCallbacks(stickyReturnRunnable);
        super.onDestroy();
        seekHandler.removeCallbacksAndMessages(null);
        thumbExecutor.shutdownNow();
    }
}
