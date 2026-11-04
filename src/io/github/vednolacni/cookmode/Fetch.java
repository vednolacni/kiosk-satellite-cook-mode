// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

/**
 * One picture waiting to be fetched. What was asked for together comes in the order it was asked for, which is the order
 * of the page, and the newest such batch goes before all the others: it is what the cook is looking at now.
 */
final class Fetch implements Runnable, Comparable<Fetch> {
    private final long batch;
    private final long order;
    private final Runnable work;

    Fetch(long batch, long order, Runnable work) {
        this.batch = batch;
        this.order = order;
        this.work = work;
    }

    @Override public void run() {
        work.run();
    }

    @Override public int compareTo(Fetch other) {
        return batch != other.batch ? Long.compare(other.batch, batch) : Long.compare(order, other.order);
    }
}
