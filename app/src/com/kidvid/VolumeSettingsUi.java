package com.kidvid;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.os.Build;
import android.widget.Toast;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.TimePicker;

/**
 * Parent-gated PIN prompt and Volume Settings dialog.
 */
final class VolumeSettingsUi {

    interface PolicySavedListener {
        void onPolicySaved(VolumePolicy policy);
    }

    private VolumeSettingsUi() {}

    static void showPinThenSettings(
            final Activity activity,
            final String expectedPin,
            final VolumePolicy currentPolicy,
            final PolicySavedListener listener) {
        if (activity.isFinishing()) return;

        final EditText pinInput = new EditText(activity);
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setHint("PIN");
        pinInput.setTextColor(Color.WHITE);
        pinInput.setHintTextColor(Color.argb(140, 200, 200, 220));
        pinInput.setBackgroundColor(Color.rgb(42, 42, 60));
        pinInput.setPadding(40, 28, 40, 28);
        pinInput.setImeOptions(EditorInfo.IME_ACTION_DONE);

        final AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Parent Settings")
                .setMessage("Enter PIN")
                .setView(pinInput)
                .setCancelable(true)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("OK", null)
                .create();

        final Runnable submit = new Runnable() {
            @Override
            public void run() {
                String entered = pinInput.getText() != null ? pinInput.getText().toString().trim() : "";
                dialog.dismiss();
                if (expectedPin.equals(entered)) {
                    showSettingsDialog(activity, currentPolicy, listener);
                }
            }
        };

        pinInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                                && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    submit.run();
                    return true;
                }
                return false;
            }
        });

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                pinInput.requestFocus();
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        submit.run();
                    }
                });
            }
        });

        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.show();
    }

    static void showSettingsDialog(
            final Activity activity,
            final VolumePolicy initialPolicy,
            final PolicySavedListener listener) {
        if (activity.isFinishing()) return;

        final int[] state = new int[] {
            initialPolicy.startingVolumePercent,
            initialPolicy.maxVolumePercent,
            initialPolicy.dipStartMinutes,
            initialPolicy.dipDurationMinutes,
            initialPolicy.dipVolumePercent
        };
        final boolean[] dipEnabled = new boolean[] { initialPolicy.dipEnabled };

        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(Color.rgb(24, 24, 36));

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(44, 32, 44, 24);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView startLabel = makeLabel(activity, "", 15, true);
        final SeekBar startSeek = new SeekBar(activity);
        startSeek.setMax(100);
        startSeek.setTag("seek_starting_volume");

        final TextView maxLabel = makeLabel(activity, "", 15, true);
        TextView maxHint = makeHint(activity, "Playback and volume buttons never exceed this.");
        final SeekBar maxSeek = new SeekBar(activity);
        maxSeek.setMax(100);
        maxSeek.setTag("seek_max_volume");

        View divider = new View(activity);
        divider.setBackgroundColor(Color.argb(60, 255, 255, 255));
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 2);
        divParams.setMargins(0, 24, 0, 20);

        LinearLayout dipHeaderRow = new LinearLayout(activity);
        dipHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        dipHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        dipHeaderRow.setPadding(0, 6, 0, 14);

        TextView dipHeaderLabel = makeLabel(activity, "Volume dip schedule", 16, true);
        dipHeaderLabel.setPadding(0, 0, 12, 0);
        dipHeaderRow.addView(dipHeaderLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Button dipToggleBtn = makeChipButton(activity, "");
        dipToggleBtn.setTag("switch_dip_enabled");
        dipToggleBtn.setContentDescription("Toggle volume dip schedule");
        dipHeaderRow.addView(dipToggleBtn);

        final TextView dipStartTitle = makeLabel(activity, "Dip starts at", 14, true);
        LinearLayout startTimeRow = new LinearLayout(activity);
        startTimeRow.setOrientation(LinearLayout.HORIZONTAL);
        startTimeRow.setGravity(Gravity.CENTER_VERTICAL);
        startTimeRow.setPadding(0, 8, 0, 16);

        final Button minus30Btn = makeChipButton(activity, "-30m");
        final Button startTimeBtn = makeChipButton(activity, "");
        startTimeBtn.setTag("btn_dip_start_time");
        final Button plus30Btn = makeChipButton(activity, "+30m");

        LinearLayout.LayoutParams timeBtnParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        timeBtnParams.setMargins(12, 0, 12, 0);
        startTimeRow.addView(minus30Btn);
        startTimeRow.addView(startTimeBtn, timeBtnParams);
        startTimeRow.addView(plus30Btn);

        final TextView dipDurLabel = makeLabel(activity, "", 14, true);
        final SeekBar dipDurSeek = new SeekBar(activity);
        dipDurSeek.setMax(VolumePolicy.MINUTES_PER_DAY / 15); // 96 steps of 15 min
        dipDurSeek.setTag("seek_dip_duration");

        final TextView dipVolLabel = makeLabel(activity, "", 14, true);
        final SeekBar dipVolSeek = new SeekBar(activity);
        dipVolSeek.setMax(100);
        dipVolSeek.setTag("seek_dip_volume");

        final Runnable refreshUi = new Runnable() {
            @Override
            public void run() {
                if (state[0] > state[1]) state[0] = state[1];
                if (state[4] > state[1]) state[4] = state[1];

                startSeek.setProgress(state[0]);
                maxSeek.setProgress(state[1]);
                dipToggleBtn.setText(dipEnabled[0] ? "ON" : "OFF");
                dipToggleBtn.setBackgroundColor(dipEnabled[0]
                        ? Color.rgb(46, 125, 50)
                        : Color.rgb(72, 72, 92));
                dipDurSeek.setProgress(state[3] / 15);
                dipVolSeek.setProgress(state[4]);

                startLabel.setText("Starting volume: " + state[0] + "%");
                maxLabel.setText("Max volume ever: " + state[1] + "%");
                startTimeBtn.setText(VolumePolicy.formatTime12h(state[2]));

                int endMin = VolumePolicy.normalizeMinuteOfDay(state[2] + state[3]);
                String durText = VolumePolicy.formatDuration(state[3]);
                if (state[3] > 0 && state[3] < VolumePolicy.MINUTES_PER_DAY) {
                    durText += " (until " + VolumePolicy.formatTime12h(endMin) + ")";
                }
                dipDurLabel.setText("Dip duration: " + durText);
                dipVolLabel.setText("Dip volume: " + state[4] + "%");

                boolean en = dipEnabled[0];
                float alpha = en ? 1.0f : 0.4f;
                dipStartTitle.setAlpha(alpha);
                startTimeBtn.setEnabled(en);
                minus30Btn.setEnabled(en);
                plus30Btn.setEnabled(en);
                startTimeRow.setAlpha(alpha);
                dipDurLabel.setAlpha(alpha);
                dipDurSeek.setEnabled(en);
                dipDurSeek.setAlpha(alpha);
                dipVolLabel.setAlpha(alpha);
                dipVolSeek.setEnabled(en);
                dipVolSeek.setAlpha(alpha);
            }
        };

        startSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                state[0] = Math.min(progress, state[1]);
                refreshUi.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        maxSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                state[1] = progress;
                if (state[0] > state[1]) state[0] = state[1];
                if (state[4] > state[1]) state[4] = state[1];
                refreshUi.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        dipToggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dipEnabled[0] = !dipEnabled[0];
                refreshUi.run();
            }
        });

        startTimeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int hour = state[2] / 60;
                int minute = state[2] % 60;
                new TimePickerDialog(activity, new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(TimePicker view, int hourOfDay, int min) {
                        state[2] = VolumePolicy.normalizeMinuteOfDay(hourOfDay * 60 + min);
                        refreshUi.run();
                    }
                }, hour, minute, false).show();
            }
        });

        minus30Btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state[2] = VolumePolicy.normalizeMinuteOfDay(state[2] - 30);
                refreshUi.run();
            }
        });

        plus30Btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state[2] = VolumePolicy.normalizeMinuteOfDay(state[2] + 30);
                refreshUi.run();
            }
        });

        dipDurSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                state[3] = progress * 15;
                refreshUi.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        dipVolSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                state[4] = Math.min(progress, state[1]);
                refreshUi.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        root.addView(startLabel);
        root.addView(startSeek, seekLayoutParams());
        root.addView(maxLabel);
        root.addView(maxHint);
        root.addView(maxSeek, seekLayoutParams());
        root.addView(divider, divParams);
        root.addView(dipHeaderRow);
        root.addView(dipStartTitle);
        root.addView(startTimeRow);
        root.addView(dipDurLabel);
        root.addView(dipDurSeek, seekLayoutParams());
        root.addView(dipVolLabel);
        root.addView(dipVolSeek, seekLayoutParams());

        View powerDivider = new View(activity);
        powerDivider.setBackgroundColor(Color.argb(60, 255, 255, 255));
        LinearLayout.LayoutParams powerDivParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 2);
        powerDivParams.setMargins(0, 16, 0, 16);
        root.addView(powerDivider, powerDivParams);
        root.addView(makeLabel(activity, "Phone", 16, true));
        root.addView(makeHint(activity, "Only from this PIN screen. Confirm before the phone restarts or powers off."));

        LinearLayout powerRow = new LinearLayout(activity);
        powerRow.setOrientation(LinearLayout.HORIZONTAL);
        powerRow.setPadding(0, 8, 0, 8);
        final Button restartBtn = makeChipButton(activity, "Restart");
        restartBtn.setTag("btn_restart");
        final Button powerOffBtn = makeChipButton(activity, "Power off");
        powerOffBtn.setTag("btn_power_off");
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half.setMargins(0, 0, 12, 0);
        powerRow.addView(restartBtn, half);
        powerRow.addView(powerOffBtn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(powerRow);

        refreshUi.run();

        final AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Volume Settings")
                .setView(scroll)
                .setCancelable(true)
                .setNeutralButton("Defaults", null)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        VolumePolicy updated = VolumePolicy.of(
                                state[0],
                                state[1],
                                dipEnabled[0],
                                state[2],
                                state[3],
                                state[4]);
                        listener.onPolicySaved(updated);
                    }
                })
                .create();

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        VolumePolicy def = VolumePolicy.defaults();
                        state[0] = def.startingVolumePercent;
                        state[1] = def.maxVolumePercent;
                        state[2] = def.dipStartMinutes;
                        state[3] = def.dipDurationMinutes;
                        state[4] = def.dipVolumePercent;
                        dipEnabled[0] = def.dipEnabled;
                        refreshUi.run();
                    }
                });
                restartBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        confirmPowerAction(activity, dialog, "Restart the phone?",
                                "KidVid will come back after the phone restarts.",
                                true);
                    }
                });
                powerOffBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        confirmPowerAction(activity, dialog, "Power off the phone?",
                                "The phone will shut down. You will need to press the power button to turn it back on.",
                                false);
                    }
                });
            }
        });

        dialog.show();
    }

    private static TextView makeLabel(Activity activity, String text, int sizeSp, boolean bold) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(sizeSp);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, 8, 0, 4);
        return tv;
    }

    private static TextView makeHint(Activity activity, String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(Color.argb(180, 200, 200, 220));
        tv.setTextSize(12);
        tv.setPadding(0, 0, 0, 6);
        return tv;
    }

    private static Button makeChipButton(Activity activity, String text) {
        Button btn = new Button(activity);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(14);
        btn.setBackgroundColor(Color.argb(180, 56, 56, 84));
        btn.setPadding(20, 12, 20, 12);
        btn.setMinWidth(0);
        btn.setMinHeight(0);
        btn.setMinimumWidth(0);
        btn.setMinimumHeight(0);
        return btn;
    }

    private static LinearLayout.LayoutParams seekLayoutParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 4, 0, 18);
        return p;
    }

    private static void confirmPowerAction(
            final Activity activity,
            final AlertDialog parent,
            String title,
            String message,
            final boolean restart) {
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(true)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(restart ? "Restart" : "Power off", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (parent != null) {
                            parent.dismiss();
                        }
                        boolean ok = restart
                                ? PowerActions.restart(powerHost(activity))
                                : PowerActions.powerOff(powerHost(activity));
                        if (!ok) {
                            Toast.makeText(activity,
                                    restart ? "Could not restart" : "Could not power off",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private static PowerActions.Host powerHost(final Activity activity) {
        return new PowerActions.Host() {
            @Override
            public boolean isDeviceOwner() {
                try {
                    DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(
                            Activity.DEVICE_POLICY_SERVICE);
                    return dpm != null && dpm.isDeviceOwnerApp(activity.getPackageName());
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public boolean deviceOwnerReboot() {
                try {
                    if (Build.VERSION.SDK_INT < 24) {
                        return false;
                    }
                    DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(
                            Activity.DEVICE_POLICY_SERVICE);
                    if (dpm == null) {
                        return false;
                    }
                    ComponentName admin = new ComponentName(activity, KidVidDeviceAdmin.class);
                    dpm.reboot(admin);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public boolean runRoot(String command) {
                if (command == null || command.length() == 0) {
                    return false;
                }
                try {
                    Process p = Runtime.getRuntime().exec(new String[] {"su", "-c", command});
                    return p != null;
                } catch (Exception e) {
                    return false;
                }
            }
        };
    }
}
