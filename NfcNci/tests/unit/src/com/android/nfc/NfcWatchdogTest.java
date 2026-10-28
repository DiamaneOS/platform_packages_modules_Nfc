/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.nfc.Flags;

import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.internal.util.reflection.FieldSetter;
import org.mockito.quality.Strictness;

import java.util.concurrent.CountDownLatch;

public class NfcWatchdogTest {
    @Mock
    private Context mContext;
    private NfcWatchdog mNfcWatchdog;
    private MockitoSession mStaticMockSession;

    @Before
    public void setUp() {
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .mockStatic(Flags.class)
                .mockStatic(PendingIntent.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        MockitoAnnotations.initMocks(this);
        when(android.nfc.Flags.nfcWatchdog()).thenReturn(true);

        mNfcWatchdog = new NfcWatchdog(mContext);
    }

    @After
    public void tearDown() {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testNotifyHasReturned() {
        CountDownLatch mCountDownLatch = mock(CountDownLatch.class);
        try {
            FieldSetter.setField(mNfcWatchdog,
                    mNfcWatchdog.getClass().getDeclaredField("mCountDownLatch"),
                    mCountDownLatch);

        } catch (NoSuchFieldException nsfe) {
            throw new RuntimeException(nsfe);
        }

        mNfcWatchdog.notifyHasReturned();
        verify(mCountDownLatch).countDown();
    }

    @Test
    public void testStopMonitoring() {
        AlarmManager alarmManager = mock(AlarmManager.class);
        PendingIntent pendingIntent = mock(PendingIntent.class);
        when(mContext.getSystemService(AlarmManager.class)).thenReturn(alarmManager);
        when(PendingIntent.getBroadcast(any(), anyInt(), any(), anyInt()))
                .thenReturn(pendingIntent);

        mNfcWatchdog.stopMonitoring();
        verify(alarmManager).cancel(pendingIntent);
        verify(alarmManager, never()).cancelAll();
    }

    @Test
    public void testEnsureWatchdogMonitoring() {
        AlarmManager alarmManager = mock(AlarmManager.class);
        AlarmManager.AlarmClockInfo alarmClockInfo = mock(AlarmManager.AlarmClockInfo.class);
        PendingIntent pendingIntent = mock(PendingIntent.class);
        when(android.nfc.Flags.nfcWatchdog()).thenReturn(true);
        when(mContext.getSystemService(AlarmManager.class)).thenReturn(alarmManager);
        when(alarmManager.getNextAlarmClock()).thenReturn(alarmClockInfo);
        when(PendingIntent.getBroadcast(mContext, 0, mock(Intent.class),
                PendingIntent.FLAG_IMMUTABLE)).thenReturn(pendingIntent);

        mNfcWatchdog.ensureWatchdogMonitoring();
        verify(alarmManager).getNextAlarmClock();
    }
}
