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

import static com.android.nfc.NfcAutoOffRule.Action.CHECK;
import static com.android.nfc.NfcAutoOffRule.Action.RETRY;
import static com.android.nfc.NfcAutoOffRule.Action.TURN_OFF;
import static com.android.nfc.NfcAutoOffRule.Action.WAIT;
import static com.android.nfc.NfcAutoOffRule.BUSY_RETRY_MS;
import static com.android.nfc.NfcAutoOffRule.SCREEN_SETTLE_MS;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

public class NfcAutoOffRuleTest {
    private static final long TIMEOUT = 60_000;
    private static final long ON_AT = 1_000_000;

    private final NfcAutoOffRule mRule = new NfcAutoOffRule();
    private boolean mChangeAllowed = true;
    private boolean mScreenOff = true;
    private boolean mBusy;
    private final NfcAutoOffRule.Conditions mConditions = new NfcAutoOffRule.Conditions() {
        @Override
        public boolean canUserChangeNfc() {
            return mChangeAllowed;
        }

        @Override
        public boolean isScreenOff() {
            return mScreenOff;
        }

        @Override
        public boolean isBusy() {
            return mBusy;
        }
    };

    @Before
    public void nfcOn() {
        mRule.setTimeout(TIMEOUT, ON_AT);
        mRule.onScreenChanged(ON_AT);
        mRule.setNfcOn(true, ON_AT);
        at(ON_AT);
    }

    private NfcAutoOffRule.Action at(long now) {
        return mRule.decide(now, mConditions);
    }

    @Test
    public void countsDownFromNfcOn() {
        assertEquals(CHECK, at(ON_AT + 1));
        assertEquals(ON_AT + TIMEOUT, mRule.checkAt());
        assertEquals(TURN_OFF, at(ON_AT + TIMEOUT));
    }

    @Test
    public void neverWhenOffOrNfcOff() {
        mRule.setTimeout(0, ON_AT);
        assertEquals(WAIT, at(ON_AT + TIMEOUT * 100));
        mRule.setTimeout(TIMEOUT, ON_AT);
        mRule.setNfcOn(false, ON_AT);
        assertEquals(WAIT, at(ON_AT + TIMEOUT * 100));
    }

    @Test
    public void useRestartsTheCountdown() {
        mRule.onUse(ON_AT + 50_000);
        assertEquals(CHECK, at(ON_AT + TIMEOUT));
        assertEquals(ON_AT + 50_000 + TIMEOUT, mRule.checkAt());
    }

    @Test
    public void settingChangeRestartsTheCountdown() {
        mRule.setTimeout(TIMEOUT * 2, ON_AT + 50_000);
        assertEquals(CHECK, at(ON_AT + TIMEOUT));
        assertEquals(ON_AT + 50_000 + TIMEOUT * 2, mRule.checkAt());
    }

    @Test
    public void countsDownOnlyWithTheScreenOff() {
        mScreenOff = false;
        mRule.onScreenChanged(ON_AT + 1_000);
        assertEquals(WAIT, at(ON_AT + 1_000));
        assertEquals(WAIT, at(ON_AT + TIMEOUT * 3));
        long screenOff = ON_AT + TIMEOUT * 3;
        mScreenOff = true;
        mRule.onScreenChanged(screenOff);
        assertEquals(CHECK, at(screenOff));
        assertEquals(screenOff + TIMEOUT, mRule.checkAt());
        assertEquals(TURN_OFF, at(screenOff + TIMEOUT));
    }

    @Test
    public void lockingKeepsTheCountdownAndSettles() {
        long locked = ON_AT + TIMEOUT - 1_000;
        mRule.onScreenChanged(locked);
        assertEquals(CHECK, at(ON_AT + TIMEOUT));
        assertEquals(locked + SCREEN_SETTLE_MS, mRule.checkAt());
        assertEquals(TURN_OFF, at(locked + SCREEN_SETTLE_MS));
    }

    @Test
    public void retriesWithoutWakingWhileBusy() {
        mBusy = true;
        long now = ON_AT + TIMEOUT;
        assertEquals(RETRY, at(now));
        assertEquals(now + BUSY_RETRY_MS, mRule.checkAt());
        mBusy = false;
        assertEquals(TURN_OFF, at(now + BUSY_RETRY_MS));
    }

    @Test
    public void staysOnWhenTheUserMayNotChangeNfc() {
        mChangeAllowed = false;
        assertEquals(WAIT, at(ON_AT + TIMEOUT));
    }

    @Test
    public void hugeTimeoutDoesNotOverflow() {
        mRule.setTimeout(Long.MAX_VALUE, ON_AT);
        assertEquals(CHECK, at(Long.MAX_VALUE - 1));
        assertEquals(Long.MAX_VALUE, mRule.checkAt());
    }
}
