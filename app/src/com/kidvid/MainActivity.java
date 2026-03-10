package com.kidvid;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.VideoView;
import android.Manifest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private VideoView videoView;
    private MediaPlayer currentPlayer;
    private List<String> videoFiles = new ArrayList<>();
    private int currentIndex = 0;
    private GestureDetector gestureDetector;
    private boolean isPaused = false;
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
    private static final long HARD_PRESS_COOLDOWN = 300; // ms between hard press rewinds

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        videoView = findViewById(R.id.videoView);
        hideSystemUI();

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
                    // Vertical swipe - change video
                    if (dy < 0) {
                        nextVideo();
                    } else {
                        prevVideo();
                    }
                    return true;
                }

                if (adx > SWIPE_THRESHOLD && Math.abs(vX) > SWIPE_VELOCITY && adx > ady) {
                    // Horizontal swipe - scrub
                    if (dx < 0) {
                        // Swipe left = rewind 5s
                        seekRelative(-5000);
                    } else {
                        // Swipe right = forward 5s
                        seekRelative(5000);
                    }
                    return true;
                }
                return false;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                // Tap left half = back 5s, right half = forward 5s
                int screenWidth = getWindow().getDecorView().getWidth();
                if (e.getX() < screenWidth / 2.0f) {
                    seekRelative(-5000);
                } else {
                    seekRelative(5000);
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                // Double tap left = back 15s, right = forward 15s
                int screenWidth = getWindow().getDecorView().getWidth();
                if (e.getX() < screenWidth / 2.0f) {
                    seekRelative(-15000);
                } else {
                    seekRelative(15000);
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                // Long press = toggle pause
                togglePause();
            }
        });

        // Check permission
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERM_REQUEST);
        } else {
            loadAndPlay();
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
        // Prefer SD card if it has kidvid content
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
        // Also scan for any mounted SD card
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
        // Fallback to internal
        activeVideosDir = VIDEOS_DIR;
        activeManifest = MANIFEST_FILE;
    }

    private void loadVideoList() {
        videoFiles.clear();
        detectStorage();
        // Try manifest first
        File manifest = new File(activeManifest);
        if (manifest.exists()) {
            try {
                FileInputStream fis = new FileInputStream(manifest);
                byte[] data = new byte[(int) manifest.length()];
                fis.read(data);
                fis.close();
                String content = new String(data, StandardCharsets.UTF_8).trim();
                // Handle both array format and object format
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
                    File f = new File(activeVideosDir + filename);
                    if (f.exists()) {
                        videoFiles.add(f.getAbsolutePath());
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Fallback: scan directory
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
                        }
                    }
                }
            }
        }
    }

    private void seekRelative(int ms) {
        if (videoView == null) return;
        int current = videoView.getCurrentPosition();
        int duration = videoView.getDuration();
        int target = Math.max(0, Math.min(current + ms, duration));
        videoView.seekTo(target);
        if (isPaused) {
            // Resume after seeking
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
                // Mark loop start
                loopStartMs = videoView.getCurrentPosition();
            } else {
                // Mark loop end and activate
                loopEndMs = videoView.getCurrentPosition();
                if (loopEndMs <= loopStartMs) {
                    // If end is before start, swap
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
                if (loopActive) {
                    loopHandler.postDelayed(this, 100);
                }
            }
        }, 100);
    }

    // --- Touch handling ---

    private int pointerCount = 0;

    // Track max pressure during a touch to detect hard presses on ACTION_UP
    private float maxPressureInTouch = 0f;
    private boolean hardPressHandled = false;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();

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

        // Track pressure throughout touch for hard press detection
        if (action == MotionEvent.ACTION_DOWN) {
            maxPressureInTouch = event.getPressure();
            hardPressHandled = false;
        } else if (action == MotionEvent.ACTION_MOVE) {
            float p = event.getPressure();
            if (p > maxPressureInTouch) maxPressureInTouch = p;
            // Hard press: high pressure + held for a moment, trigger rewind
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

        // Always pass to gesture detector for swipes/taps
        gestureDetector.onTouchEvent(event);
        return super.onTouchEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return onTouchEvent(event);
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
        if (hasFocus) {
            hideSystemUI();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        if (videoView != null && !videoView.isPlaying() && !isPaused) {
            videoView.start();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        loopHandler.removeCallbacksAndMessages(null);
    }
}
