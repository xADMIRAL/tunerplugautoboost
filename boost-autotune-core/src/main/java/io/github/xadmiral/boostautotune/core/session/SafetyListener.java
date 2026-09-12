package io.github.xadmiral.boostautotune.core.session;

import io.github.xadmiral.boostautotune.core.model.Sample;

/** Notified when a sample exceeds the hard boost limit. The plugin reacts by writing a safe duty. */
public interface SafetyListener {
    void overboost(Sample sample, double limitKpa);
}
