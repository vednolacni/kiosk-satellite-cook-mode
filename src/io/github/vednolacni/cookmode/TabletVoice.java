// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import java.util.Locale;

/**
 * Read-aloud with the tablet's own text-to-speech engine. Not every kiosk has one, and few have a Slovenian voice, so
 * the plugin reports when the language is missing and the user can switch to Home Assistant's voice instead. Created
 * on the main thread from a view's context.
 */
final class TabletVoice {
    /** Why the voice cannot speak, for the plugin's status, or null when it can. */
    interface Report {
        void problem(String message);
    }

    private final TextToSpeech engine;
    private final Locale locale;
    private final Report report;
    private volatile boolean ready;
    private String pending;

    TabletVoice(Context context, Locale locale, Report report) {
        this.locale = locale;
        this.report = report;
        engine = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS) {
                report.problem("This tablet has no text-to-speech engine. Choose Home Assistant under Read aloud.");
                return;
            }
            int result = setLanguage();
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                report.problem("The tablet's voice does not speak " + this.locale.getDisplayLanguage(Locale.ENGLISH) + ". Install it in Android's text-to-speech settings or choose Home Assistant under Read aloud.");
                return;
            }
            ready = true;
            String waiting;
            synchronized (this) {
                waiting = pending;
                pending = null;
            }
            if (waiting != null) speak(waiting);
        });
    }

    private int setLanguage() {
        try {
            return engine.setLanguage(locale);
        } catch (RuntimeException failed) {
            return TextToSpeech.LANG_NOT_SUPPORTED;
        }
    }

    /** Say text, cutting off whatever was being said. Before the engine is ready, the latest text waits. */
    void speak(String text) {
        if (text == null || text.isEmpty()) return;
        if (!ready) {
            synchronized (this) {
                pending = text;
            }
            return;
        }
        try {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cook-mode");
        } catch (RuntimeException failed) {
            report.problem("The tablet's voice failed: " + failed.getMessage());
        }
    }

    void silence() {
        try {
            engine.stop();
        } catch (RuntimeException failed) {
            // Nothing is being said.
        }
    }

    void close() {
        try {
            engine.stop();
            engine.shutdown();
        } catch (RuntimeException failed) {
            // The engine is gone already.
        }
    }
}
