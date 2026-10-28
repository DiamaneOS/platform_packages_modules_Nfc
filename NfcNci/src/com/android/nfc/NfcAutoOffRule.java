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

/**
 * When NFC turns off by itself. The countdown runs only while the screen is off. It starts when
 * the screen turns off and again when NFC turns on, when the timeout changes and after each use.
 * When it ends, NFC turns off once the controller has settled after the last screen change and
 * nothing is in use. Times are elapsed realtime in ms.
 */
final class NfcAutoOffRule {
    // NfcService reconfigures the controller on each screen change.
    static final long SCREEN_SETTLE_MS = 10_000;
    static final long BUSY_RETRY_MS = 30_000;

    enum Action {
        /** Nothing until the next event. */
        WAIT,
        /** Check again at {@link #checkAt()}, waking the device. */
        CHECK,
        /** Check again at {@link #checkAt()} or when the device next wakes, if later. */
        RETRY,
        TURN_OFF,
    }

    interface Conditions {
        boolean canUserChangeNfc();
        boolean isScreenOff();
        boolean isBusy();
    }

    private long mTimeoutMs;
    private boolean mNfcOn;
    private long mDeadline;
    private long mScreenChangedAt;
    private boolean mScreenOff;
    private long mCheckAt;

    void setTimeout(long timeoutMs, long now) {
        mTimeoutMs = Math.max(0, timeoutMs);
        restart(now);
    }

    void setNfcOn(boolean on, long now) {
        mNfcOn = on;
        restart(now);
    }

    void onUse(long now) {
        restart(now);
    }

    void onScreenChanged(long now) {
        mScreenChangedAt = now;
    }

    boolean isActive() {
        return mNfcOn && mTimeoutMs > 0;
    }

    long timeoutMs() {
        return mTimeoutMs;
    }

    long deadline() {
        return mDeadline;
    }

    long checkAt() {
        return mCheckAt;
    }

    Action decide(long now, Conditions c) {
        // The first decision that sees the screen off starts the countdown. A lock or an unlock
        // with the screen off keeps it.
        boolean screenOff = c.isScreenOff();
        if (screenOff && !mScreenOff) {
            restart(now);
        }
        mScreenOff = screenOff;
        if (!isActive()) {
            return Action.WAIT;
        }
        // The next screen change or a restriction change calls decide() again.
        if (!screenOff || !c.canUserChangeNfc()) {
            return Action.WAIT;
        }
        long checkAt = Math.max(mDeadline, mScreenChangedAt + SCREEN_SETTLE_MS);
        if (now < checkAt) {
            mCheckAt = checkAt;
            return Action.CHECK;
        }
        if (c.isBusy()) {
            mCheckAt = now + BUSY_RETRY_MS;
            return Action.RETRY;
        }
        return Action.TURN_OFF;
    }

    private void restart(long now) {
        mDeadline = mTimeoutMs > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + mTimeoutMs;
    }
}
