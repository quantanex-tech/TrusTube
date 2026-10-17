// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.app.Application;

public final class ProofApplication extends Application {
    private ProofState state;
    @Override public void onCreate() { super.onCreate(); state = new ProofState(this); }
    public ProofState state() { return state; }
}
