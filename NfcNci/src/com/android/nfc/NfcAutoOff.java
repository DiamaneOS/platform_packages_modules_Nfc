/*
 * Copyright (C) 2026 The DiamaneOS Project
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

package com.android.nfc;

import android.app.AlarmManager;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Turns NFC off after the NFC_AUTO_OFF timeout, as {@link NfcAutoOffRule} says. Decisions run on
 * NfcService's handler thread, where it applies screen states and handles field events, and NFC
 * turns off through NfcService's own disable task, as with the user's switch.
 */
final class NfcAutoOff {
    private static final String TAG = "NfcAutoOff";
    /** Settings.Global.NFC_AUTO_OFF in ms, 0 = never. 0 is also its default in ExtSettings. */
    static final String SETTING = "nfc_off_timeout";
    // The task queue and the disable, whose watchdog allows 6 s.
    private static final long WAKE_LOCK_TIMEOUT_MS = 20_000;

    interface Host extends NfcAutoOffRule.Conditions {
        boolean isNfcOn();

        /** Queues the disable task, which checks {@link #isCurrent} before turning NFC off. */
        void turnOff(int generation);
    }

    private final Context mContext;
    private final NfcInjector mInjector;
    private final Handler mHandler;
    private final Host mHost;
    private final AlarmManager mAlarmManager;
    private final PowerManager.WakeLock mWakeLock;
    private final NfcAutoOffRule mRule = new NfcAutoOffRule();
    // Every event bumps it, so an event after a decision cancels the queued disable.
    private final AtomicInteger mGeneration = new AtomicInteger();
    private final AtomicBoolean mUsePosted = new AtomicBoolean();
    private final AlarmManager.OnAlarmListener mAlarmListener = this::onAlarm;
    private final Runnable mApplyUse = this::applyUse;

    // Handler thread only.
    private ContentObserver mObserver;
    private long mAlarmAt = -1;
    private boolean mAlarmWakes;
    private boolean mTurningOff;
    private int mTurnOffGeneration;

    NfcAutoOff(Context context, NfcInjector injector, Handler handler, Host host) {
        mContext = context;
        mInjector = injector;
        mHandler = handler;
        mHost = host;
        mAlarmManager = context.getSystemService(AlarmManager.class);
        mWakeLock = context.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.setReferenceCounted(false);
    }

    /** Any thread. */
    void onNfcStateChanged() {
        mGeneration.incrementAndGet();
        mHandler.post(this::applyNfcState);
    }

    /** A reader's field, a tag, card emulation, a transaction or the user turning NFC on. */
    void onUse() {
        mGeneration.incrementAndGet();
        if (mUsePosted.compareAndSet(false, true)) {
            mHandler.post(mApplyUse);
        }
    }

    /** Called under the lock the disable task checks {@link #isCurrent} in. */
    void onScreenStateChanging() {
        mGeneration.incrementAndGet();
    }

    /** NfcService gave the controller a new screen state. */
    void onScreenStateApplied() {
        mGeneration.incrementAndGet();
        mHandler.post(() -> {
            mRule.onScreenChanged(now());
            update();
        });
    }

    /** A user restriction or a routing update. */
    void onConditionsChanged() {
        mGeneration.incrementAndGet();
        mHandler.post(this::update);
    }

    /** Any thread. */
    boolean isCurrent(int generation) {
        return generation == mGeneration.get();
    }

    /** The disable task ended, whatever it did. */
    void onTurnOffDone() {
        mWakeLock.release();
        mHandler.post(() -> {
            mTurningOff = false;
            if (isCurrent(mTurnOffGeneration)) {
                // No event and NFC still on, so the disable was refused. Try after a timeout.
                Log.w(TAG, "NFC stayed on");
                mRule.onUse(now());
            }
            update();
        });
    }

    void dump(PrintWriter pw) {
        long now = now();
        pw.println("NfcAutoOff: timeoutMs=" + mRule.timeoutMs() + " active=" + mRule.isActive()
                + " deadlineInMs=" + (mRule.deadline() - now)
                + " alarmInMs=" + (mAlarmAt < 0 ? "none" : mAlarmAt - now)
                + " turningOff=" + mTurningOff);
    }

    private void applyNfcState() {
        // The live state: posts from different threads can arrive out of order.
        boolean on = mHost.isNfcOn();
        long now = now();
        if (on) {
            observeSetting();
            mRule.setTimeout(mInjector.getNfcAutoOffTimeoutMillis(), now);
            // Enabling sets the controller's screen state too.
            mRule.onScreenChanged(now);
        }
        mRule.setNfcOn(on, now);
        update();
    }

    private void observeSetting() {
        if (mObserver != null) {
            return;
        }
        mObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                mGeneration.incrementAndGet();
                mRule.setTimeout(mInjector.getNfcAutoOffTimeoutMillis(), now());
                update();
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(SETTING), false, mObserver);
    }

    private void applyUse() {
        mUsePosted.set(false);
        mRule.onUse(now());
        update();
    }

    private void onAlarm() {
        mAlarmAt = -1;
        update();
    }

    private void update() {
        if (mTurningOff) {
            return;
        }
        // Read before the conditions: they see the flags of every event counted here, and a
        // later event cancels the disable.
        int generation = mGeneration.get();
        switch (mRule.decide(now(), mHost)) {
            case WAIT -> cancelAlarm();
            case CHECK -> setAlarm(mRule.checkAt(), true);
            case RETRY -> setAlarm(mRule.checkAt(), false);
            case TURN_OFF -> turnOff(generation);
        }
    }

    private void turnOff(int generation) {
        cancelAlarm();
        Log.i(TAG, "turning NFC off, unused for " + mRule.timeoutMs() + " ms");
        mTurningOff = true;
        mTurnOffGeneration = generation;
        mWakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        mHost.turnOff(generation);
    }

    private void setAlarm(long at, boolean wakes) {
        // An earlier alarm only checks again, so keep it: saves binder calls while a field flaps.
        if (mAlarmAt >= 0 && mAlarmAt <= at && (mAlarmWakes || !wakes)) {
            return;
        }
        if (wakes) {
            // Exact, since a window fires late. As a core UID it also fires on time in Doze.
            mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, TAG, mAlarmListener,
                    mHandler);
        } else {
            mAlarmManager.set(AlarmManager.ELAPSED_REALTIME, at, TAG, mAlarmListener, mHandler);
        }
        mAlarmAt = at;
        mAlarmWakes = wakes;
    }

    private void cancelAlarm() {
        if (mAlarmAt >= 0) {
            mAlarmManager.cancel(mAlarmListener);
            mAlarmAt = -1;
        }
    }

    private long now() {
        return mInjector.getElapsedSinceBootMillis();
    }
}
