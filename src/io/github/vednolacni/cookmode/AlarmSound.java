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
 * The sound of a ringing timer: the device's alarm tone, repeated until silenced, or plain beeps on a tablet with no tone
 * at all or when asked for them. Ringtone only loops by itself from Android 9, and kitchen tablets such as the ThinkSmart
 * View run Android 8.1, so it is played again whenever it stops. Beeps need no Context, so they also work before any view
 * has been built. Main thread only. It gives up after a few minutes so a forgotten timer does not ring all day.
 */
final class AlarmSound {
    private static final long CHECK_MS = 1_500L;
    private static final int BEEP_MS = 800;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable again = () -> Parts.guard(this::again);
    private Ringtone ringtone;
    private ToneGenerator beeper;
    private long until;

    /** Start sounding for at most minutes, or go on that long if already sounding. context may be null, which means beeps. */
    void start(Context context, boolean beeps, boolean mediaStream, int minutes) {
        until = SystemClock.uptimeMillis() + minutes * 60_000L;
        if (playing()) return;
        if (context != null && !beeps) ringtone = ringtone(context, mediaStream);
        if (ringtone == null) beeper = beeper(mediaStream);
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

    /** The volume of the stream the alarm sounds on, as a share of its largest. */
    static float volume(Context context, boolean mediaStream) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return 1;
        int stream = mediaStream ? AudioManager.STREAM_MUSIC : AudioManager.STREAM_ALARM;
        int largest = audio.getStreamMaxVolume(stream);
        return largest <= 0 ? 1 : audio.getStreamVolume(stream) / (float) largest;
    }

    private static Ringtone ringtone(Context context, boolean mediaStream) {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (uri == null) return null;
            Ringtone tone = RingtoneManager.getRingtone(context, uri);
            if (tone == null) return null;
            tone.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(mediaStream ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
            return tone;
        } catch (RuntimeException noTone) {
            return null;
        }
    }

    private static ToneGenerator beeper(boolean mediaStream) {
        try {
            return new ToneGenerator(mediaStream ? AudioManager.STREAM_MUSIC : AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME);
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
