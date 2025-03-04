/*
 * Copyright (C) 2015-2016 The CyanogenMod Project
 * Copyright (C) 2017 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.device.DeviceSettings;

import android.Manifest;
import android.app.ActivityThread;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.session.MediaSessionLegacyHelper;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.widget.Toast;

import com.android.internal.os.DeviceKeyHandler;
import com.android.internal.util.ArrayUtils;

import org.lineageos.device.DeviceSettings.Constants;
import org.lineageos.device.DeviceSettings.DeviceSettings;
import org.lineageos.device.DeviceSettings.R;

import vendor.oneplus.hardware.camera.V1_0.IOnePlusCameraProvider;


public class KeyHandler implements DeviceKeyHandler {

    private static final String TAG = KeyHandler.class.getSimpleName();
    private static final int GESTURE_REQUEST = 1;
    private static final boolean DEBUG = false;

    private static final SparseIntArray sSupportedSliderZenModes = new SparseIntArray();
    private static final SparseIntArray sSupportedSliderRingModes = new SparseIntArray();
    private static final SparseIntArray sSupportedSliderHaptics = new SparseIntArray();
    static {
        sSupportedSliderZenModes.put(Constants.KEY_VALUE_TOTAL_SILENCE, Settings.Global.ZEN_MODE_NO_INTERRUPTIONS);
        sSupportedSliderZenModes.put(Constants.KEY_VALUE_SILENT, Settings.Global.ZEN_MODE_OFF);
        sSupportedSliderZenModes.put(Constants.KEY_VALUE_PRIORTY_ONLY, Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS);
        sSupportedSliderZenModes.put(Constants.KEY_VALUE_VIBRATE, Settings.Global.ZEN_MODE_OFF);
        sSupportedSliderZenModes.put(Constants.KEY_VALUE_NORMAL, Settings.Global.ZEN_MODE_OFF);

        sSupportedSliderRingModes.put(Constants.KEY_VALUE_TOTAL_SILENCE, AudioManager.RINGER_MODE_NORMAL);
        sSupportedSliderRingModes.put(Constants.KEY_VALUE_SILENT, AudioManager.RINGER_MODE_SILENT);
        sSupportedSliderRingModes.put(Constants.KEY_VALUE_PRIORTY_ONLY, AudioManager.RINGER_MODE_NORMAL);
        sSupportedSliderRingModes.put(Constants.KEY_VALUE_VIBRATE, AudioManager.RINGER_MODE_VIBRATE);
        sSupportedSliderRingModes.put(Constants.KEY_VALUE_NORMAL, AudioManager.RINGER_MODE_NORMAL);
        
        sSupportedSliderHaptics.put(Constants.KEY_VALUE_TOTAL_SILENCE, VibrationEffect.EFFECT_HEAVY_CLICK);
        sSupportedSliderHaptics.put(Constants.KEY_VALUE_SILENT, VibrationEffect.EFFECT_DOUBLE_CLICK);
        sSupportedSliderHaptics.put(Constants.KEY_VALUE_PRIORTY_ONLY, VibrationEffect.EFFECT_HEAVY_CLICK);
        sSupportedSliderHaptics.put(Constants.KEY_VALUE_VIBRATE, VibrationEffect.EFFECT_TICK);
        sSupportedSliderHaptics.put(Constants.KEY_VALUE_NORMAL, -1);
    }

    public static final String CLIENT_PACKAGE_NAME = "com.oneplus.camera";
    public static final String CLIENT_PACKAGE_PATH = "/data/misc/lineage/client_package_name";
    
    private static Toast mToast;

    private final Context mContext;
    private final Context mResContext;
    private final Context mSysUiContext;
    private final PowerManager mPowerManager;
    private final NotificationManager mNotificationManager;
    private final AudioManager mAudioManager;
    private SensorManager mSensorManager;
    private Sensor mProximitySensor;
    private Vibrator mVibrator;
    WakeLock mProximityWakeLock;
    WakeLock mGestureWakeLock;
    private int mProximityTimeOut;
    private int mPrevKeyCode = 0;
    private boolean mProximityWakeSupported;
    private boolean mDispOn;
    private ClientPackageNameObserver mClientObserver;
    private IOnePlusCameraProvider mProvider;
    private boolean isOPCameraAvail;

    private BroadcastReceiver mSystemStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                mDispOn = true;
                onDisplayOn();
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                mDispOn = false;
                onDisplayOff();
            }
        }
    };

    public KeyHandler(Context context) {
        mContext = context;
        mResContext = getResContext(context);
        mSysUiContext = ActivityThread.currentActivityThread().getSystemUiContext();
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mNotificationManager
                = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mGestureWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "GestureWakeLock");

        mVibrator = mContext.getSystemService(Vibrator.class);
        if (mVibrator == null || !mVibrator.hasVibrator()) {
            mVibrator = null;
        }

        IntentFilter systemStateFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        systemStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mSystemStateReceiver, systemStateFilter);

        isOPCameraAvail = Utils.isAvailableApp("com.oneplus.camera", context);
        if (isOPCameraAvail) {
            mClientObserver = new ClientPackageNameObserver(CLIENT_PACKAGE_PATH);
            mClientObserver.startWatching();
        }
    }

    private boolean hasSetupCompleted() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0) != 0;
    }

    public KeyEvent handleKeyEvent(KeyEvent event) {
        int scanCode = event.getScanCode();
        String keyCode = Constants.sKeyMap.get(scanCode);
        int keyCodeValue = 0;
        try {
            keyCodeValue = Constants.getPreferenceInt(mContext, keyCode);
        } catch (Exception e) {
            return event;
        }

        if (!hasSetupCompleted()) {
            return event;
        }

        // We only want ACTION_UP event
        if (event.getAction() != KeyEvent.ACTION_UP) {
            return null;
        }

        doHapticFeedback(sSupportedSliderHaptics.get(keyCodeValue));
        mAudioManager.setRingerModeInternal(sSupportedSliderRingModes.get(keyCodeValue));
        if (mPrevKeyCode == Constants.KEY_VALUE_TOTAL_SILENCE)
            doHapticFeedback(sSupportedSliderHaptics.get(keyCodeValue));
        mNotificationManager.setZenMode(sSupportedSliderZenModes.get(keyCodeValue), null, TAG);
        int position = scanCode == 601 ? 2 : scanCode == 602 ? 1 : 0;
        mPrevKeyCode = keyCodeValue;

        int positionValue = 0;
        String toastText;
        Resources res = mResContext.getResources();
        int key = sSupportedSliderRingModes.keyAt(
                sSupportedSliderRingModes.indexOfKey(keyCodeValue));
        switch (key) {
            case Constants.KEY_VALUE_TOTAL_SILENCE: // DND - no int'
                toastText = res.getString(R.string.slider_toast_dnd);
                positionValue = Constants.MODE_TOTAL_SILENCE;
                break;
            case Constants.KEY_VALUE_SILENT: // Ringer silent
                toastText = res.getString(R.string.slider_toast_silent);
                positionValue = Constants.MODE_SILENT;
                break;
            case Constants.KEY_VALUE_PRIORTY_ONLY: // DND - priority
                toastText = res.getString(R.string.slider_toast_priority);
                positionValue = Constants.MODE_PRIORITY_ONLY;
                break;
            case Constants.KEY_VALUE_VIBRATE: // Ringer vibrate
                toastText = res.getString(R.string.slider_toast_vibrate);
                positionValue = Constants.MODE_VIBRATE;
                break;
            default:
            case Constants.KEY_VALUE_NORMAL: // Ringer normal DND off
                toastText = res.getString(R.string.slider_toast_normal);
                positionValue = Constants.MODE_RING;
                break;
        }

        sendUpdateBroadcast(position, positionValue);
        mPrevKeyCode = keyCodeValue;

        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (mToast != null) mToast.cancel();
                mToast = Toast.makeText(
                        mSysUiContext, toastText, Toast.LENGTH_SHORT);
                mToast.show();
            }
        });

        return null;
    }

    private void sendUpdateBroadcast(int position, int position_value) {
        Intent intent = new Intent(Constants.ACTION_UPDATE_SLIDER_POSITION);
        intent.putExtra(Constants.EXTRA_SLIDER_POSITION, position);
        intent.putExtra(Constants.EXTRA_SLIDER_POSITION_VALUE, position_value);
        mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
        intent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        Log.d(TAG, "slider change to positon " + position
                            + " with value " + position_value);
    }

    private void doHapticFeedback(int effect) {
        if (mVibrator != null && mVibrator.hasVibrator() && effect != -1) {
            mVibrator.vibrate(VibrationEffect.get(effect));
        }
    }

    private Context getResContext(Context context) {
        Context resContext;
        try {
            resContext = context.createPackageContext("org.lineageos.device.DeviceSettings",
                    Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
        } catch (NameNotFoundException e) {
            // nothing to do about this, shouldn't ever reach here anyway
            resContext = context;
        }
        return resContext;
    }

    public void handleNavbarToggle(boolean enabled) {
        // do nothing
    }

    public boolean canHandleKeyEvent(KeyEvent event) {
        return false;
    }

        private void onDisplayOff() {
        if (DEBUG) Log.i(TAG, "Display off");
        if (mClientObserver != null) {
            mClientObserver.stopWatching();
            mClientObserver = null;
        }
    }

    private void onDisplayOn() {
        if (DEBUG) Log.i(TAG, "Display on");
        if ((mClientObserver == null) && (isOPCameraAvail)) {
            mClientObserver = new ClientPackageNameObserver(CLIENT_PACKAGE_PATH);
            mClientObserver.startWatching();
        }
    }

    private class ClientPackageNameObserver extends FileObserver {

        public ClientPackageNameObserver(String file) {
            super(CLIENT_PACKAGE_PATH, MODIFY);
        }

        @Override
        public void onEvent(int event, String file) {
            String pkgName = Utils.getFileValue(CLIENT_PACKAGE_PATH, "0");
            if (event == FileObserver.MODIFY) {
                try {
                    Log.d(TAG, "client_package" + file + " and " + pkgName);
                    mProvider = IOnePlusCameraProvider.getService();
                    mProvider.setPackageName(pkgName);
                } catch (RemoteException e) {
                    Log.e(TAG, "setPackageName error", e);
                }
            }
        }
    }
}
