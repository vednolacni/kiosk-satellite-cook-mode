// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.kitchen;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;

/** The views and the main thread on the kiosk. */
final class AndroidUi implements Ui {
    private final Handler main = new Handler(Looper.getMainLooper());
    /** The newest model, for a panel KS builds later. */
    private volatile PanelModel latest;
    /** The panel on screen. Main thread only. */
    private PanelView panel;

    @Override public OverlayFactory panel(Ui.Actions actions) {
        return new OverlayFactory() {
            @Override public View create(Context context, KsTheme theme) {
                PanelView view = new PanelView(context, theme, actions);
                PanelModel model = latest;
                if (model != null) view.render(model);
                panel = view;
                return view.root;
            }

            @Override public void onThemeChanged(View view, KsTheme theme) {
                if (panel != null && panel.root == view) panel.restyle(theme);
            }

            @Override public void onDestroy(View view) {
                if (panel != null && panel.root == view) panel = null;
            }
        };
    }

    @Override public void render(PanelModel model) {
        latest = model;
        main.post(() -> {
            // A newer model is on its way, so only it is drawn.
            if (panel != null && latest == model) panel.render(model);
        });
    }

    @Override public OverlayFactory pill(String label, Runnable end) {
        return new OverlayFactory() {
            @Override public View create(Context context, KsTheme theme) {
                TextView pill = new TextView(context);
                pill.setText(label);
                pill.setOnClickListener(view -> {
                    try {
                        end.run();
                    } catch (RuntimeException sessionEnded) {
                        // The session ended between the tap and now.
                    }
                });
                onThemeChanged(pill, theme);
                return pill;
            }

            @Override public void onThemeChanged(View view, KsTheme theme) {
                TextView pill = (TextView) view;
                theme.stylePill(pill, true);
                Drawable hat = icon(theme, "mdi:chef-hat", "onPrimary");
                if (hat != null) hat.setBounds(0, 0, theme.px(20), theme.px(20));
                pill.setCompoundDrawablesRelative(hat, null, null, null);
                pill.setCompoundDrawablePadding(theme.px(8));
            }
        };
    }

    @Override public void post(Runnable task, long delayMs) {
        main.postDelayed(task, delayMs);
    }

    @Override public void cancel(Runnable task) {
        main.removeCallbacks(task);
    }

    /** A KS icon, or none for an unknown name. */
    static Drawable icon(KsTheme theme, String name, String role) {
        try {
            return theme.icon(name, role);
        } catch (LinkageError olderHost) {
            return null;
        }
    }
}
