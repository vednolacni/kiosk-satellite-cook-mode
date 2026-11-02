// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.view.View;

/** One page of the main view. It stays while the page does and changes its views in place, so nothing moves under a finger. */
interface Screen {
    View view();

    /** Show this model. Runs on the main thread, whenever anything on the page changed. */
    void show(Model model);
}
