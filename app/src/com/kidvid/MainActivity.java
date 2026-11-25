package com.kidvid;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.IntentFilter;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
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
import java.util.Calendar;
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

    // Soft mode (no Device Owner): screen-pin + sticky bring-to-front.
    private static final long STICKY_RETURN_DELAY_MS = 400;
    private boolean deviceOwnerMode = false;
    private boolean parentExitRequested = false;
    private final Handler stickyHandler = new Handler(Looper.getMainLooper());
    private final Runnable stickyReturnRunnable = new Runnable() {
        @Override public void run() {
            if (parentExitRequested || isFinishing()) return;
            // Don't fight a screen-off pause; resume + pin when the display wakes.
            Boolean interactive = null;
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                    interactive = Boolean.valueOf(pm.isInteractive());
                }
            } catch (Exception ignored) {}
            if (!StickyReturnPolicy.shouldBringToFront(parentExitRequested, isFinishing(), interactive)) {
                return;
            }
            bringKidVidToFront();
            pinScreen();
        }
    };

    private ExecutorService thumbExecutor = Executors.newFixedThreadPool(2);

    // Browse button
    private Button browseButton;

    // Parent-configured volume policy and hard cap enforcement
    private AudioManager audioManager;
    private VolumeSettingsStore volumeStore;
    private VolumeController volumeController;
    private float currentPlayerGain = 1.0f;
    private final Handler volumeHandler = new Handler(Looper.getMainLooper());
    private ContentObserver volumeObserver;
    private static final long VOLUME_CHECK_INTERVAL_MS = 2000;
    private final Runnable volumeTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (volumeController != null) {
                volumeController.tick();
            }
            volumeHandler.postDelayed(this, VOLUME_CHECK_INTERVAL_MS);
        }
    };

    // Parent-gated delete: sustained ~5s hold on a thumbnail, then PIN.
    // Must cancel on finger-up / move / scroll — a brief press must never open PIN.
    private static final long PARENT_DELETE_HOLD_MS = 5000;
    private static final long PARENT_DELETE_PROGRESS_MS = 1000;
    private static final long PARENT_DELETE_LOCK_SCROLL_MS = 450;
    private static final String PARENT_DELETE_PIN = "123456";
    private final Handler deleteHoldHandler = new Handler(Looper.getMainLooper());
    private Runnable deleteHoldRunnable;
    private Runnable deleteHoldProgressRunnable;
    private Runnable deleteHoldLockScrollRunnable;
    private int deleteHoldPosition = -1;
    private boolean deleteHoldTriggered = false;
    private boolean deleteHoldActive = false;
    private View deleteHoldView;
    private long deleteHoldStartElapsed;
    private float deleteHoldStartRawX;
    private float deleteHoldStartRawY;
    private int deleteHoldSlopSq;
    private boolean deleteHoldMoved;
    private Toast deleteHoldProgressToast;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        videoView = findViewById(R.id.videoView);
        rootLayout = (FrameLayout) videoView.getParent();
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
                // Long press = rewind 10 seconds
                seekRelative(-10000);
            }
        });

        initVolumeControl();

        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERM_REQUEST);
        } else {
            loadAndPlay();
        }

        // Start background sync service
        SyncService.schedule(this);

        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        deleteHoldSlopSq = (slop * 3) * (slop * 3);
    }


    /**
     * Soft pin always; Device Owner hardens lock-task when available.
     * Fire cannot set Device Owner without wipe — soft pin is the default.
     */
    private void enableLockTask() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, KidVidDeviceAdmin.class);
            deviceOwnerMode = dpm != null && dpm.isDeviceOwnerApp(getPackageName());
            if (deviceOwnerMode) {
                try {
                    dpm.setLockTaskPackages(admin, new String[]{getPackageName(), "com.topjohnwu.magisk"});
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
                    }
                } catch (Exception ignored) {}
                // Package replace (adb install -r) can drop the HOME role back to
                // Nexus Launcher. Re-assert it on every start so a screen-on after
                // an update still opens KidVid, not the Pixel launcher.
                try {
                    IntentFilter homeFilter = new IntentFilter(Intent.ACTION_MAIN);
                    homeFilter.addCategory(Intent.CATEGORY_HOME);
                    homeFilter.addCategory(Intent.CATEGORY_DEFAULT);
                    dpm.addPersistentPreferredActivity(admin, homeFilter,
                            new ComponentName(getPackageName(), MainActivity.class.getName()));
                } catch (Exception ignored) {}
            }
            pinScreen();
        } catch (Exception e) {
            // ignore
        }
    }

    private void pinScreen() {
        if (parentExitRequested || isFinishing()) return;
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) return;
            }
            startLockTask();
        } catch (Exception e) {
            // First soft-pin may show a confirm dialog on some Fire builds.
        }
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

    private void scheduleStickyReturn() {
        if (parentExitRequested || isFinishing()) return;
        stickyHandler.removeCallbacks(stickyReturnRunnable);
        stickyHandler.postDelayed(stickyReturnRunnable, STICKY_RETURN_DELAY_MS);
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

        Button settingsButton = new Button(this);
        settingsButton.setText("\u2699\uFE0E");
        settingsButton.setTextSize(16);
        settingsButton.setBackgroundColor(Color.argb(60, 40, 40, 60));
        settingsButton.setTextColor(Color.argb(175, 205, 205, 225));
        settingsButton.setPadding(22, 14, 22, 14);
        settingsButton.setMinWidth(0);
        settingsButton.setMinHeight(0);
        settingsButton.setMinimumWidth(0);
        settingsButton.setMinimumHeight(0);
        settingsButton.setContentDescription("Settings");
        settingsButton.setTag("settings_button");
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VolumePolicy current = volumeController != null
                        ? volumeController.getPolicy()
                        : VolumePolicy.defaults();
                VolumeSettingsUi.showPinThenSettings(
                        MainActivity.this,
                        PARENT_DELETE_PIN,
                        current,
                        new VolumeSettingsUi.PolicySavedListener() {
                            @Override
                            public void onPolicySaved(VolumePolicy updated) {
                                if (volumeStore != null) {
                                    volumeStore.save(updated);
                                }
                                if (volumeController != null) {
                                    volumeController.applyPolicy(updated);
                                }
                                Toast.makeText(MainActivity.this,
                                        "Volume settings saved", Toast.LENGTH_SHORT).show();
                            }
                        });
            }
        });
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        settingsParams.setMargins(8, 0, 0, 0);
        titleRow.addView(settingsButton, settingsParams);

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
                // Ignore the click that follows a completed 5s parent-delete hold
                if (deleteHoldTriggered) {
                    deleteHoldTriggered = false;
                    return;
                }
                hideBrowser();
                playVideo(position);
            }
        });
        // Any scroll must abort a pending parent-delete hold (kids flicking the grid).
        browserGrid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState != SCROLL_STATE_IDLE) {
                    cancelParentDeleteHold();
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                // no-op
            }
        });
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        // Allow a little finger tremor during a deliberate 5s hold; still cancel on real drags.
        deleteHoldSlopSq = (slop * 3) * (slop * 3);

        ((LinearLayout) browserOverlay).addView(browserGrid,
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rootLayout.addView(browserOverlay,
            new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void hideBrowser() {
        cancelParentDeleteHold();
        deleteHoldTriggered = false;
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

    // --- Parent-gated delete (sustained 5s hold + PIN) ---

    private void startParentDeleteHold(final View cell, final int position) {
        cancelParentDeleteHold();
        deleteHoldActive = true;
        deleteHoldMoved = false;
        deleteHoldPosition = position;
        deleteHoldView = cell;
        deleteHoldStartElapsed = SystemClock.elapsedRealtime();

        deleteHoldProgressRunnable = new Runnable() {
            @Override
            public void run() {
                deleteHoldProgressRunnable = null;
                if (!deleteHoldActive || deleteHoldPosition != position) return;
                // Visual + toast so a parent knows the hold is counting; still no PIN yet.
                if (deleteHoldView != null) {
                    deleteHoldView.setBackgroundColor(Color.argb(160, 180, 40, 40));
                }
                deleteHoldProgressToast = Toast.makeText(MainActivity.this,
                    "Keep holding\u2026", Toast.LENGTH_SHORT);
                deleteHoldProgressToast.show();
            }
        };
        deleteHoldHandler.postDelayed(deleteHoldProgressRunnable, PARENT_DELETE_PROGRESS_MS);

        // After a brief stationary press, stop GridView from stealing the gesture for scroll.
        deleteHoldLockScrollRunnable = new Runnable() {
            @Override
            public void run() {
                deleteHoldLockScrollRunnable = null;
                if (!deleteHoldActive || deleteHoldView == null) return;
                ViewParent parent = deleteHoldView.getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true);
                }
            }
        };
        deleteHoldHandler.postDelayed(deleteHoldLockScrollRunnable, PARENT_DELETE_LOCK_SCROLL_MS);

        deleteHoldRunnable = new Runnable() {
            @Override
            public void run() {
                deleteHoldRunnable = null;
                // deleteHoldActive is cleared on UP/MOVE/CANCEL — never show PIN after release.
                if (!deleteHoldActive || deleteHoldPosition != position) return;
                long held = SystemClock.elapsedRealtime() - deleteHoldStartElapsed;
                if (held < PARENT_DELETE_HOLD_MS - 50) {
                    cancelParentDeleteHold();
                    return;
                }
                deleteHoldTriggered = true;
                deleteHoldActive = false;
                clearParentDeleteHoldCallbacksOnly();
                restoreDeleteHoldCellBackground();
                if (deleteHoldView != null) {
                    deleteHoldView.setPressed(false);
                }
                showParentDeletePinDialog(position);
            }
        };
        deleteHoldHandler.postDelayed(deleteHoldRunnable, PARENT_DELETE_HOLD_MS);
    }

    private void cancelParentDeleteHold() {
        clearParentDeleteHoldCallbacksOnly();
        if (deleteHoldView != null) {
            ViewParent parent = deleteHoldView.getParent();
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(false);
            }
            deleteHoldView.setPressed(false);
        }
        restoreDeleteHoldCellBackground();
        deleteHoldActive = false;
        deleteHoldPosition = -1;
        deleteHoldView = null;
        deleteHoldStartElapsed = 0;
        if (deleteHoldProgressToast != null) {
            deleteHoldProgressToast.cancel();
            deleteHoldProgressToast = null;
        }
    }

    private void clearParentDeleteHoldCallbacksOnly() {
        if (deleteHoldRunnable != null) {
            deleteHoldHandler.removeCallbacks(deleteHoldRunnable);
            deleteHoldRunnable = null;
        }
        if (deleteHoldProgressRunnable != null) {
            deleteHoldHandler.removeCallbacks(deleteHoldProgressRunnable);
            deleteHoldProgressRunnable = null;
        }
        if (deleteHoldLockScrollRunnable != null) {
            deleteHoldHandler.removeCallbacks(deleteHoldLockScrollRunnable);
            deleteHoldLockScrollRunnable = null;
        }
    }

    private void restoreDeleteHoldCellBackground() {
        if (deleteHoldView == null) return;
        Object tag = deleteHoldView.getTag();
        if (tag instanceof Integer) {
            int pos = (Integer) tag;
            deleteHoldView.setBackgroundColor(
                pos == currentIndex ? Color.argb(100, 100, 100, 255) : Color.argb(60, 255, 255, 255));
        }
    }

    private boolean parentDeleteMoveExceededSlop(MotionEvent event) {
        float dx = event.getRawX() - deleteHoldStartRawX;
        float dy = event.getRawY() - deleteHoldStartRawY;
        return dx * dx + dy * dy > deleteHoldSlopSq;
    }

    private void showParentDeletePinDialog(final int position) {
        if (isFinishing() || position < 0 || position >= videoFiles.size()) return;

        String title = position < videoTitles.size() ? videoTitles.get(position) : "this video";

        final EditText pinInput = new EditText(this);
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setHint("PIN");
        pinInput.setPadding(48, 32, 48, 32);

        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Delete video?")
            .setMessage("Hold confirmed. Enter parent PIN to permanently remove:\n" + title)
            .setView(pinInput)
            .setCancelable(true)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", null)
            .create();

        dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String entered = pinInput.getText() != null ? pinInput.getText().toString().trim() : "";
                        if (PARENT_DELETE_PIN.equals(entered)) {
                            dialog.dismiss();
                            performParentDelete(position);
                        } else {
                            Toast.makeText(MainActivity.this, "Wrong PIN", Toast.LENGTH_SHORT).show();
                            pinInput.setText("");
                        }
                    }
                });
            }
        });
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface d) {
                // Allow the next normal tap after dialog closes
                deleteHoldTriggered = false;
            }
        });
        dialog.show();
    }

    private void performParentDelete(final int position) {
        if (position < 0 || position >= videoFiles.size()) return;

        final String path = videoFiles.get(position);
        final String title = position < videoTitles.size() ? videoTitles.get(position) : "Video";
        final String filename = new File(path).getName();
        final boolean wasCurrent = (position == currentIndex);

        Toast.makeText(this, "Deleting...", Toast.LENGTH_SHORT).show();

        thumbExecutor.execute(new Runnable() {
            @Override
            public void run() {
                File local = new File(path);
                boolean localOk = !local.exists() || local.delete();

                // Parent PIN: remove from shared library + tee pending deletes
                boolean remoteOk = SyncService.deleteRemoteVideo(filename);

                final boolean localDeleted = localOk;
                final boolean remoteDeleted = remoteOk;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!localDeleted) {
                            Toast.makeText(MainActivity.this,
                                "Delete failed (could not remove file)", Toast.LENGTH_LONG).show();
                            return;
                        }

                        thumbnailCache.remove(path);
                        videoFiles.remove(position);
                        if (position < videoTitles.size()) {
                            videoTitles.remove(position);
                        }

                        if (videoFiles.isEmpty()) {
                            currentIndex = 0;
                            try { videoView.stopPlayback(); } catch (Exception ignored) {}
                        } else if (wasCurrent) {
                            currentIndex = Math.min(position, videoFiles.size() - 1);
                            playVideo(currentIndex);
                        } else if (position < currentIndex) {
                            currentIndex--;
                        }

                        if (browserVisible && browserGrid != null && browserGrid.getAdapter() != null) {
                            ((ThumbnailAdapter) browserGrid.getAdapter()).notifyDataSetChanged();
                        }

                        if (remoteDeleted) {
                            Toast.makeText(MainActivity.this,
                                "Deleted: " + title, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(MainActivity.this,
                                "Deleted locally; server DELETE failed (may reappear on sync)",
                                Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
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
                // Do NOT use string tags for view identity — ImageView.setTag(path) is
                // reserved for async recycle checks (was NPE: findViewWithTag("thumb") null).
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                int thumbHeight = (int)(getResources().getDisplayMetrics().widthPixels / 2.5);
                cell.addView(thumb, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, thumbHeight));

                label = new TextView(MainActivity.this);
                label.setTextColor(Color.WHITE);
                label.setTextSize(12);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(2);
                label.setPadding(4, 8, 4, 4);
                cell.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                cell = (LinearLayout) convertView;
                // Child 0 = ImageView, child 1 = TextView (creation order above)
                thumb = (ImageView) cell.getChildAt(0);
                label = (TextView) cell.getChildAt(1);
            }
            if (thumb == null || label == null) {
                // Defensive: never crash the grid if structure unexpected
                if (convertView != null) return convertView;
                return new LinearLayout(MainActivity.this);
            }

            String path = videoFiles.get(position);
            String title = position < videoTitles.size() ? videoTitles.get(position) : "Video " + (position + 1);
            label.setText(title);
            cell.setBackgroundColor(position == currentIndex ? Color.argb(100, 100, 100, 255) : Color.argb(60, 255, 255, 255));
            cell.setTag(position);
            // Sustained hold ~5s → parent PIN delete. Must return true on DOWN so we receive
            // UP/MOVE/CANCEL and can abort the timer (returning false was the false-positive bug).
            cell.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    Object tag = v.getTag();
                    if (!(tag instanceof Integer)) return false;
                    int pos = (Integer) tag;
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            deleteHoldStartRawX = event.getRawX();
                            deleteHoldStartRawY = event.getRawY();
                            v.setPressed(true);
                            startParentDeleteHold(v, pos);
                            // Claim the stream so UP/MOVE/CANCEL reach us and cancel the timer.
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            if (deleteHoldActive && parentDeleteMoveExceededSlop(event)) {
                                deleteHoldMoved = true;
                                // Real drag / scroll intent — abort hold and let parent intercept.
                                ViewParent parent = v.getParent();
                                if (parent != null) {
                                    parent.requestDisallowInterceptTouchEvent(false);
                                }
                                v.setPressed(false);
                                cancelParentDeleteHold();
                            }
                            return true;
                        case MotionEvent.ACTION_UP: {
                            boolean wasTriggered = deleteHoldTriggered;
                            boolean wasActive = deleteHoldActive;
                            boolean moved = deleteHoldMoved || parentDeleteMoveExceededSlop(event);
                            long held = wasActive
                                ? (SystemClock.elapsedRealtime() - deleteHoldStartElapsed) : 0;
                            v.setPressed(false);
                            cancelParentDeleteHold();
                            // Short stationary press → normal tap-to-play (we consumed the stream).
                            if (!wasTriggered && !moved && wasActive && held < PARENT_DELETE_HOLD_MS
                                    && browserGrid != null && pos >= 0 && pos < videoFiles.size()) {
                                browserGrid.performItemClick(v, pos, browserGrid.getItemIdAtPosition(pos));
                            }
                            return true;
                        }
                        case MotionEvent.ACTION_CANCEL:
                            v.setPressed(false);
                            cancelParentDeleteHold();
                            return true;
                    }
                    return true;
                }
            });

            // Clear recycled cell so a prior Goldie frame never sticks on Splash.
            // Tag holds bound path for async callback validity (not view identity).
            thumb.setImageDrawable(null);
            thumb.setBackgroundColor(Color.DKGRAY);
            thumb.setTag(path);

            if (thumbnailCache.containsKey(path)) {
                Bitmap bmp = thumbnailCache.get(path);
                if (bmp != null) {
                    thumb.setImageBitmap(bmp);
                    thumb.setBackgroundColor(Color.TRANSPARENT);
                }
            } else {
                final ImageView thumbRef = thumb;
                final String thumbPath = path;
                thumbExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        Bitmap bmp = null;
                        try {
                            // Prefer server/client sidecar stem.jpg when present
                            String stemJpg = thumbPath;
                            int dot = thumbPath.lastIndexOf('.');
                            if (dot > 0) stemJpg = thumbPath.substring(0, dot) + ".jpg";
                            File jpg = new File(stemJpg);
                            if (jpg.isFile() && jpg.length() > 0) {
                                bmp = BitmapFactory.decodeFile(jpg.getAbsolutePath());
                            }
                            if (bmp == null) {
                                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                                mmr.setDataSource(thumbPath);
                                bmp = mmr.getFrameAtTime(5000000);
                                mmr.release();
                            }
                        } catch (Exception e) {
                            bmp = null;
                        }
                        thumbnailCache.put(thumbPath, bmp);
                        final Bitmap out = bmp;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Object tag = thumbRef.getTag();
                                if (tag == null || !thumbPath.equals(tag)) return;
                                if (out != null) {
                                    thumbRef.setImageBitmap(out);
                                    thumbRef.setBackgroundColor(Color.TRANSPARENT);
                                } else {
                                    thumbRef.setImageDrawable(null);
                                    thumbRef.setBackgroundColor(Color.DKGRAY);
                                }
                            }
                        });
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
                if (volumeController != null) {
                    volumeController.onPlaybackPrepared();
                } else {
                    mp.setVolume(currentPlayerGain, currentPlayerGain);
                }
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
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (volumeController != null) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    volumeController.onVolumeKey(keyCode);
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

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

        // Prefer app dirs (getFilesDir + ALL getExternalFilesDirs) — adoptable SD has most free space
        java.util.LinkedHashSet<String> preferred = new java.util.LinkedHashSet<>();
        File filesVids = new File(getFilesDir(), "videos");
        if (filesVids.isDirectory() || filesVids.mkdirs()) {
            preferred.add(filesVids.getAbsolutePath());
        }
        File[] extRoots = getExternalFilesDirs(null);
        if (extRoots != null) {
            // Sort by free space descending so SD (huge) is scanned/played first
            java.util.Arrays.sort(extRoots, new java.util.Comparator<File>() {
                public int compare(File a, File b) {
                    long fa = (a == null) ? -1 : a.getUsableSpace();
                    long fb = (b == null) ? -1 : b.getUsableSpace();
                    return Long.compare(fb, fa);
                }
            });
            for (File root : extRoots) {
                if (root == null) continue;
                File v = new File(root, "videos");
                if (v.isDirectory() || v.mkdirs()) preferred.add(v.getAbsolutePath());
            }
        }
        for (String p : preferred) {
            if (!videoDirs.contains(p)) videoDirs.add(p);
        }

        // Legacy paths (may still hold pre-migration library)
        for (String sdPath : SD_PATHS) {
            File sdVids = new File(sdPath + "videos/");
            if (sdVids.exists() && sdVids.isDirectory()) {
                String path = sdVids.getAbsolutePath();
                if (!videoDirs.contains(path)) {
                    videoDirs.add(path);
                    loadManifestTitles(new File(sdPath + "manifest.json"), titleMap);
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
                    String path = sdVids.getAbsolutePath();
                    if (sdVids.exists() && sdVids.isDirectory() && !videoDirs.contains(path)) {
                        videoDirs.add(path);
                        loadManifestTitles(new File(mount, "kidvid/manifest.json"), titleMap);
                    }
                }
            }
        }
        File internalVids = new File(VIDEOS_DIR);
        if (internalVids.exists() && internalVids.isDirectory()
                && !videoDirs.contains(internalVids.getAbsolutePath())) {
            videoDirs.add(internalVids.getAbsolutePath());
            loadManifestTitles(new File(MANIFEST_FILE), titleMap);
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

        // Prefer dir with most free space that has videos (SD after migration)
        String bestActive = null;
        long bestFree = -1;
        for (String dirPath : videoDirs) {
            File d = new File(dirPath);
            File[] fs = d.listFiles();
            boolean hasVid = false;
            if (fs != null) {
                for (File f : fs) {
                    String n = f.getName().toLowerCase();
                    if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm") || n.endsWith(".3gp")) {
                        hasVid = true; break;
                    }
                }
            }
            if (!hasVid) continue;
            long free = d.getUsableSpace();
            if (free > bestFree) {
                bestFree = free;
                bestActive = dirPath.endsWith("/") ? dirPath : dirPath + "/";
            }
        }
        if (bestActive != null) {
            activeVideosDir = bestActive;
        } else if (!videoDirs.isEmpty()) {
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
        // Fire OS / API 28: never call R+ Window APIs (NoSuchMethodError even if
        // VERSION_CODES.R constant compares oddly on some firmwares).
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                getWindow().setDecorFitsSystemWindows(false);
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
                return;
            }
        } catch (Throwable t) {
            // fall through to legacy flags
        }
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
            pinScreen();
        } else {
            // Soft-pin can be dismissed on Fire; yank KidVid back.
            scheduleStickyReturn();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        stickyHandler.removeCallbacks(stickyReturnRunnable);
        hideSystemUI();
        if (!parentExitRequested) {
            pinScreen();
        }
        if (volumeController != null) {
            volumeController.tick();
        }
        if (videoView != null && !videoView.isPlaying() && !isPaused && !browserVisible) {
            videoView.start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!parentExitRequested) scheduleStickyReturn();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stickyHandler.removeCallbacksAndMessages(null);
        cancelParentDeleteHold();
        seekHandler.removeCallbacksAndMessages(null);
        deleteHoldHandler.removeCallbacksAndMessages(null);
        thumbExecutor.shutdownNow();
        volumeHandler.removeCallbacksAndMessages(null);
        if (volumeObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(volumeObserver);
            } catch (Exception ignored) {}
            volumeObserver = null;
        }
    }

    private void initVolumeControl() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        volumeStore = new VolumeSettingsStore(new SharedPreferencesStore(this));
        volumeController = new VolumeController(new VolumeController.VolumeHost() {
            @Override
            public int getStreamMaxVolume() {
                if (audioManager == null) return 0;
                return audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            }

            @Override
            public int getStreamVolume() {
                if (audioManager == null) return 0;
                return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            }

            @Override
            public void setStreamVolume(int index) {
                if (audioManager == null) return;
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0);
                } catch (SecurityException ignored) {}
            }

            @Override
            public void setPlayerVolume(float gain) {
                currentPlayerGain = gain;
                if (currentPlayer != null) {
                    try {
                        currentPlayer.setVolume(gain, gain);
                    } catch (IllegalStateException ignored) {}
                }
            }

            @Override
            public int getCurrentMinuteOfDay() {
                Calendar cal = Calendar.getInstance();
                return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            }
        });

        volumeController.applyPolicy(volumeStore.load());
        volumeObserver = new ContentObserver(volumeHandler) {
            @Override
            public void onChange(boolean selfChange) {
                if (volumeController != null) {
                    volumeController.tick();
                }
            }
        };
        try {
            getContentResolver().registerContentObserver(
                    Settings.System.CONTENT_URI, true, volumeObserver);
        } catch (Exception ignored) {}
        volumeHandler.removeCallbacks(volumeTickRunnable);
        volumeHandler.postDelayed(volumeTickRunnable, VOLUME_CHECK_INTERVAL_MS);
    }
}
