package com.kidvid;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
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
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;
import android.widget.VideoView;
import android.Manifest;

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

    // SD card paths - try these first
    private static final String[] SD_PATHS = {
        "/storage/AE60-81BC/kidvid/",
    };

    private String activeVideosDir = VIDEOS_DIR;
    private String activeManifest = MANIFEST_FILE;
    private static final int PERM_REQUEST = 100;

    // A-B Loop
    private int loopStartMs = -1;
    private int loopEndMs = -1;
    private boolean loopActive = false;
    private Handler loopHandler = new Handler(Looper.getMainLooper());

    // Hard press detection
    private static final float HARD_PRESS_THRESHOLD = 0.9f;
    private long lastHardPressTime = 0;
    private static final long HARD_PRESS_COOLDOWN = 300;

    // Thumbnail cache
    private Map<String, Bitmap> thumbnailCache = new HashMap<>();
    private ExecutorService thumbExecutor = Executors.newFixedThreadPool(2);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        videoView = findViewById(R.id.videoView);
        rootLayout = (FrameLayout) videoView.getParent();
        hideSystemUI();
        enableLockTask();
        addBrowseButton();

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

                if (adx > SWIPE_THRESHOLD && Math.abs(vX) > SWIPE_VELOCITY && adx > ady) {
                    if (dx < 0) seekRelative(-5000);
                    else seekRelative(5000);
                    return true;
                }
                return false;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                int screenWidth = getWindow().getDecorView().getWidth();
                if (e.getX() < screenWidth / 2.0f) seekRelative(-5000);
                else seekRelative(5000);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                int screenWidth = getWindow().getDecorView().getWidth();
                if (e.getX() < screenWidth / 2.0f) seekRelative(-15000);
                else seekRelative(15000);
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                togglePause();
            }
        });

        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERM_REQUEST);
        } else {
            loadAndPlay();
        }
    }

    private void enableLockTask() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, KidVidDeviceAdmin.class);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                dpm.setLockTaskPackages(admin, new String[]{getPackageName()});
                startLockTask();
            }
        } catch (Exception e) {
            // Not device owner or already in lock task
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
        detectStorage();

        File manifest = new File(activeManifest);
        if (manifest.exists()) {
            try {
                FileInputStream fis = new FileInputStream(manifest);
                byte[] data = new byte[(int) manifest.length()];
                fis.read(data);
                fis.close();
                String content = new String(data, StandardCharsets.UTF_8).trim();
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
                    String title = v.optString("title", filename.replace(".mp4", "").replace("_", " "));
                    File f = new File(activeVideosDir + filename);
                    if (f.exists()) {
                        videoFiles.add(f.getAbsolutePath());
                        videoTitles.add(title);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (videoFiles.isEmpty()) {
            File dir = new File(activeVideosDir);
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    java.util.Arrays.sort(files);
                    for (File f : files) {
                        String name = f.getName().toLowerCase();
                        if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm") || name.endsWith(".3gp")) {
                            videoFiles.add(f.getAbsolutePath());
                            videoTitles.add(f.getName().replace(".mp4", "").replace("_", " "));
                        }
                    }
                }
            }
        }
    }

    // --- Browse Button ---

    private Button browseButton;

    private void addBrowseButton() {
        browseButton = new Button(this);
        browseButton.setText("\uD83C\uDFAC");
        browseButton.setTextSize(24);
        browseButton.setBackgroundColor(Color.argb(160, 40, 40, 60));
        browseButton.setTextColor(Color.WHITE);
        browseButton.setPadding(16, 8, 16, 8);
        browseButton.setMinWidth(0);
        browseButton.setMinHeight(0);
        browseButton.setMinimumWidth(0);
        browseButton.setMinimumHeight(0);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.setMargins(0, 0, 24, 24);

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
        // Reload video list to pick up any new content
        loadVideoList();
        browserVisible = true;
        videoView.pause();
        if (browseButton != null) browseButton.setVisibility(View.GONE);

        // Create overlay
        browserOverlay = new LinearLayout(this);
        ((LinearLayout) browserOverlay).setOrientation(LinearLayout.VERTICAL);
        browserOverlay.setBackgroundColor(Color.argb(240, 20, 20, 30));
        browserOverlay.setClickable(true);

        // Title bar
        TextView titleBar = new TextView(this);
        titleBar.setText("\uD83C\uDFAC  Pick a Video!");
        titleBar.setTextColor(Color.WHITE);
        titleBar.setTextSize(20);
        titleBar.setGravity(Gravity.CENTER);
        titleBar.setPadding(0, 40, 0, 20);
        ((LinearLayout) browserOverlay).addView(titleBar,
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Grid
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
                cell.setBackgroundColor(position == currentIndex ? Color.argb(100, 100, 100, 255) : Color.argb(60, 255, 255, 255));

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

            // Highlight currently playing
            cell.setBackgroundColor(position == currentIndex ? Color.argb(100, 100, 100, 255) : Color.argb(60, 255, 255, 255));

            // Load thumbnail
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
        clearLoop();

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

    private void togglePause() {
        if (isPaused) {
            videoView.start();
            isPaused = false;
        } else {
            videoView.pause();
            isPaused = true;
        }
    }

    // --- A-B Loop ---

    private void handleTwoFingerTap() {
        if (!loopActive) {
            if (loopStartMs < 0) {
                loopStartMs = videoView.getCurrentPosition();
            } else {
                loopEndMs = videoView.getCurrentPosition();
                if (loopEndMs <= loopStartMs) {
                    int tmp = loopStartMs;
                    loopStartMs = loopEndMs;
                    loopEndMs = tmp;
                }
                loopActive = true;
                startLoopChecker();
            }
        }
    }

    private void clearLoop() {
        loopActive = false;
        loopStartMs = -1;
        loopEndMs = -1;
        loopHandler.removeCallbacksAndMessages(null);
    }

    private void startLoopChecker() {
        loopHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (loopActive && videoView != null && videoView.isPlaying()) {
                    int pos = videoView.getCurrentPosition();
                    if (pos >= loopEndMs || pos < loopStartMs) {
                        videoView.seekTo(loopStartMs);
                    }
                }
                if (loopActive) loopHandler.postDelayed(this, 100);
            }
        }, 100);
    }

    // --- Touch handling ---

    private float maxPressureInTouch = 0f;
    private boolean hardPressHandled = false;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();

        // If browser is visible, let it handle events
        if (browserVisible) {
            return super.onTouchEvent(event);
        }

        // Two-finger tap for A-B loop
        if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() == 2) {
            handleTwoFingerTap();
            return true;
        }

        // Three-finger tap to clear loop
        if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() == 3) {
            clearLoop();
            return true;
        }

        // Hard press detection
        if (action == MotionEvent.ACTION_DOWN) {
            maxPressureInTouch = event.getPressure();
            hardPressHandled = false;
        } else if (action == MotionEvent.ACTION_MOVE) {
            float p = event.getPressure();
            if (p > maxPressureInTouch) maxPressureInTouch = p;
            if (!hardPressHandled && maxPressureInTouch >= HARD_PRESS_THRESHOLD) {
                long now = System.currentTimeMillis();
                if ((now - lastHardPressTime) > HARD_PRESS_COOLDOWN) {
                    lastHardPressTime = now;
                    hardPressHandled = true;
                    seekRelative(-10000);
                    return true;
                }
            }
        }

        gestureDetector.onTouchEvent(event);
        return super.onTouchEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (browserVisible) {
            return super.dispatchTouchEvent(event);
        }
        // Let the button handle its own touches
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
        return onTouchEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (browserVisible) {
            hideBrowser();
            return;
        }
        // Don't let back button exit
    }

    private void hideSystemUI() {
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
        if (hasFocus) hideSystemUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        if (videoView != null && !videoView.isPlaying() && !isPaused && !browserVisible) {
            videoView.start();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        loopHandler.removeCallbacksAndMessages(null);
        thumbExecutor.shutdownNow();
    }
}
