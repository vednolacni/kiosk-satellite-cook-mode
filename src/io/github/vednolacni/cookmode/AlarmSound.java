// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * The sound of a ringing timer: the device's alarm tone on the alarm stream, repeated until silenced, or plain beeps on
 * a tablet with no tone at all. Ringtone only loops by itself from Android 9, and kitchen tablets such as the ThinkSmart
 * View run Android 8.1, so it is played again whenever it stops. Main thread only. It gives up after a few minutes so a
 * forgotten timer does not ring all day.
 */
final class AlarmSound {
    private static final long CHECK_MS = 1_500L;
    private static final long LONGEST_MS = 3 * 60_000L;
    private static final int BEEP_MS = 800;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable again = this::again;
    private Ringtone ringtone;
    private ToneGenerator beeper;
    private long until;

    void start(Context context) {
        if (playing()) return;
        until = SystemClock.uptimeMillis() + LONGEST_MS;
        ringtone = ringtone(context);
        if (ringtone == null) beeper = beeper();
        main.post(again);
    }

    void stop() {
        main.removeCallbacks(again);
        if (ringtone != null) {
            try {
                ringtone.stop();
            } catch (RuntimeException alreadyGone) {
                // Nothing left to stop.
            }
            ringtone = null;
        }
        if (beeper != null) {
            beeper.release();
            beeper = null;
        }
    }

    boolean playing() {
        return ringtone != null || beeper != null;
    }

    private static Ringtone ringtone(Context context) {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (uri == null) return null;
            Ringtone tone = RingtoneManager.getRingtone(context, uri);
            if (tone == null) return null;
            tone.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
            return tone;
        } catch (RuntimeException noTone) {
            return null;
        }
    }

    private static ToneGenerator beeper() {
        try {
            return new ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME);
        } catch (RuntimeException noSound) {
            // A tablet without any sound still shows the ringing timer.
            return null;
        }
    }

    private void again() {
        if (!playing()) return;
        if (SystemClock.uptimeMillis() >= until) {
            stop();
            return;
        }
        try {
            if (ringtone != null && !ringtone.isPlaying()) ringtone.play();
            if (beeper != null) beeper.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, BEEP_MS);
        } catch (RuntimeException noSound) {
            stop();
            return;
        }
        main.postDelayed(again, CHECK_MS);
    }
}
