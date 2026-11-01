package org.telegram.ui.Components;

import java.util.ArrayList;

/**
 * Finds long pauses in a voice message so playback can jump over them.
 *
 * Telegram for Android integration: voice notes are Opus; MediaController already decodes
 * them for playback. The PCM is scanned once (or the decoded waveform at a finer resolution
 * than message.media.document waveform) and the result is cached per document id.
 * MediaController.updateProgress checks {@link #skipTarget(long)} and seeks when it is >= 0.
 */
public class SilenceSkipper {

    public static class Range {
        public final long startMs, endMs;

        Range(long s, long e) {
            startMs = s;
            endMs = e;
        }
    }

    /** Pauses shorter than this stay: they are part of normal speech rhythm. */
    public static final long MIN_SILENCE_MS = 700;
    /** Kept at both edges of a pause so words are not clipped. */
    public static final long KEEP_MS = 180;
    private static final int FRAME_MS = 20;

    public final ArrayList<Range> ranges = new ArrayList<>();
    public final long durationMs;
    /** Peak level per 1/100 of the message, 0..1, for the waveform. */
    public final float[] levels = new float[100];

    public SilenceSkipper(short[] pcm, int sampleRate) {
        durationMs = pcm.length * 1000L / sampleRate;
        int frame = sampleRate * FRAME_MS / 1000;
        int frames = pcm.length / frame;
        float[] rms = new float[frames];
        float peak = 1;
        for (int f = 0; f < frames; f++) {
            double sum = 0;
            for (int i = f * frame; i < (f + 1) * frame; i++) {
                sum += (double) pcm[i] * pcm[i];
            }
            rms[f] = (float) Math.sqrt(sum / frame);
            peak = Math.max(peak, rms[f]);
        }
        // Silence = 30 dB below the loudest part of this message.
        float threshold = peak * 0.0316f;
        int runStart = -1;
        for (int f = 0; f <= frames; f++) {
            boolean silent = f < frames && rms[f] < threshold;
            if (silent && runStart < 0) {
                runStart = f;
            } else if (!silent && runStart >= 0) {
                long s = (long) runStart * FRAME_MS, e = (long) f * FRAME_MS;
                if (e - s >= MIN_SILENCE_MS + 2 * KEEP_MS && runStart > 0 && f < frames) {
                    ranges.add(new Range(s + KEEP_MS, e - KEEP_MS));
                }
                runStart = -1;
            }
        }
        for (int b = 0; b < levels.length; b++) {
            int f0 = b * frames / levels.length, f1 = Math.max(f0 + 1, (b + 1) * frames / levels.length);
            float m = 0;
            for (int f = f0; f < f1 && f < frames; f++) {
                m = Math.max(m, rms[f]);
            }
            levels[b] = m / peak;
        }
    }

    /** Where to continue from if position is inside a pause, otherwise -1. */
    public long skipTarget(long positionMs) {
        for (Range r : ranges) {
            if (positionMs >= r.startMs && positionMs < r.endMs) {
                return r.endMs;
            }
        }
        return -1;
    }

    public boolean isSilent(long positionMs) {
        return skipTarget(positionMs) >= 0;
    }

    public long savedMs() {
        long s = 0;
        for (Range r : ranges) {
            s += r.endMs - r.startMs;
        }
        return s;
    }
}
