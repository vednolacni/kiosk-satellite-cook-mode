// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.kitchen;

import me.jxl.kiosk.plugins.OverlayFactory;

/** The Android side of the plugin: its views and the main thread. Tests stand in their own. */
interface Ui {
    /** What the panel's meals and buttons do. Called on the main thread. */
    interface Actions {
        void open(Meal meal);
        void openApp();
        void close();
        void refresh();
    }

    /** The meal plan panel. KS builds it later on the main thread, with the latest model given to render. */
    OverlayFactory panel(Actions actions);

    /** Show this model in the panel: at once while it is up, else when KS builds it. Safe on any thread. */
    void render(PanelModel model);

    /** The pill that floats over a recipe page while a cooking session runs. A tap runs end. */
    OverlayFactory pill(String label, Runnable end);

    /** Run a task on the main thread after a delay in milliseconds. */
    void post(Runnable task, long delayMs);

    /** Drop a posted task that has not run yet. */
    void cancel(Runnable task);
}
