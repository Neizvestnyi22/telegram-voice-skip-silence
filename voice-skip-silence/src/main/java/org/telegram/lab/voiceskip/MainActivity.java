package org.telegram.lab.voiceskip;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.lab.LabActivity;
import org.telegram.lab.R;
import org.telegram.lab.Tg;
import org.telegram.lab.TouchDriver;
import org.telegram.lab.ui.Bubble;
import org.telegram.lab.ui.ChatScreen;
import org.telegram.lab.ui.MessageCell;
import org.telegram.lab.ui.WallpaperDrawable;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.SilenceSkipper;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.Locale;

public class MainActivity extends LabActivity {

    private static final int RATE = 16000;
    private ChatScreen chat;
    private VoiceCell voice;
    private short[] pcm;
    private SilenceSkipper skipper;

    private volatile boolean playing, skipOn;
    private volatile long positionMs;
    private volatile long jumpFlashAt;
    private Thread player;

    @Override
    protected void onCreateScreen(FrameLayout content) {
        pcm = readWav(org.telegram.lab.voiceskip.R.raw.voice_long);
        skipper = new SilenceSkipper(pcm, RATE);

        chat = new ChatScreen(this, WallpaperDrawable.BLUE);
        content.addView(chat);
        chat.header.setTitle("Ben Carter").setSubtitle("online", true).setAvatar("Ben Carter");
        chat.header.addAction(R.drawable.tg_ic_call);
        chat.header.addAction(R.drawable.tg_ic_ab_other);

        chat.addDate("Today");
        chat.add(new MessageCell(this, true).setText("So what's the plan for Saturday? 🏞").setTime("18:40").setStatus(MessageCell.STATUS_READ));
        chat.add(new MessageCell(this, false).setText("Wait, I'll record it, easier to explain").setTime("18:41").setTail(false));
        voice = chat.add(new VoiceCell(this));
        chat.scrollToBottom();
    }

    private short[] readWav(int res) {
        try (InputStream in = getResources().openRawResource(res); DataInputStream d = new DataInputStream(in)) {
            byte[] all = new byte[in.available()];
            d.readFully(all);
            int n = (all.length - 44) / 2;
            short[] s = new short[n];
            for (int i = 0; i < n; i++) {
                s[i] = (short) ((all[44 + i * 2] & 0xFF) | (all[45 + i * 2] << 8));
            }
            return s;
        } catch (Exception e) {
            return new short[RATE];
        }
    }

    private void togglePlay() {
        if (playing) {
            playing = false;
            return;
        }
        if (positionMs >= skipper.durationMs - 50) {
            positionMs = 0;
        }
        playing = true;
        player = new Thread(this::playLoop, "voice");
        player.start();
        voice.postInvalidateOnAnimation();
    }

    /** Streams PCM to AudioTrack in 20 ms chunks; pauses are jumped over while skipping is on. */
    private void playLoop() {
        int chunk = RATE / 50;
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(chunk * 2 * 4)
                .build();
        track.play();
        long started = SystemClock.elapsedRealtime();
        long startPos = positionMs;
        long written = 0;
        while (playing) {
            long pos = positionMs;
            if (skipOn) {
                long target = skipper.skipTarget(pos);
                if (target >= 0) {
                    pos = target;
                    jumpFlashAt = SystemClock.elapsedRealtime();
                }
            }
            int from = (int) (pos * RATE / 1000);
            if (from >= pcm.length) {
                break;
            }
            int len = Math.min(chunk, pcm.length - from);
            track.write(pcm, from, len);
            positionMs = pos + len * 1000L / RATE;
            written += len;
            // Keep the UI clock close to what is heard when the sink runs faster than real time.
            long ahead = written * 1000L / RATE - (SystemClock.elapsedRealtime() - started) - 120;
            if (ahead > 0) {
                SystemClock.sleep(ahead);
            }
        }
        track.stop();
        track.release();
        if (positionMs >= skipper.durationMs - 50) {
            positionMs = skipper.durationMs;
        }
        playing = false;
        voice.postInvalidate();
    }

    private static String time(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    @Override
    protected void onPause() {
        super.onPause();
        playing = false;
    }

    /** Incoming voice message bubble: play button, waveform, duration and the skip-pauses chip. */
    private class VoiceCell extends View {

        final RectF bubble = new RectF();
        final RectF play = new RectF();
        final RectF chip = new RectF();
        final RectF wave = new RectF();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint small = Tg.paint(12.5f, Tg.timeIn, false);
        private final TextPaint chipText = Tg.paint(12.5f, Tg.accent, true);
        private final Path path = new Path();
        private final Drawable skipIcon;

        VoiceCell(Context c) {
            super(c);
            skipIcon = Tg.icon(c, R.drawable.tg_msg_speed, Tg.accent);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), AndroidUtilities.dp(86));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            float left = AndroidUtilities.dp(10);
            bubble.set(left, AndroidUtilities.dp(3), left + AndroidUtilities.dp(300), getHeight() - AndroidUtilities.dp(3));
            play.set(bubble.left + AndroidUtilities.dp(10), bubble.top + AndroidUtilities.dp(10), bubble.left + AndroidUtilities.dp(54), bubble.top + AndroidUtilities.dp(54));
            wave.set(play.right + AndroidUtilities.dp(10), bubble.top + AndroidUtilities.dp(14), bubble.right - AndroidUtilities.dp(12), bubble.top + AndroidUtilities.dp(38));
            chip.set(wave.left + AndroidUtilities.dp(40), bubble.bottom - AndroidUtilities.dp(30), wave.left + AndroidUtilities.dp(160), bubble.bottom - AndroidUtilities.dp(8));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Bubble.draw(canvas, bubble, false, true);
            // Play / pause.
            paint.setColor(Tg.accent);
            canvas.drawCircle(play.centerX(), play.centerY(), play.width() / 2, paint);
            paint.setColor(0xFFFFFFFF);
            float cx = play.centerX(), cy = play.centerY();
            if (playing) {
                float w = AndroidUtilities.dp(4), h = AndroidUtilities.dp(15);
                canvas.drawRoundRect(cx - w * 1.6f, cy - h / 2, cx - w * 0.6f + w * 0.5f, cy + h / 2, 3, 3, paint);
                canvas.drawRoundRect(cx + w * 0.6f - w * 0.5f, cy - h / 2, cx + w * 1.6f, cy + h / 2, 3, 3, paint);
            } else {
                path.reset();
                path.moveTo(cx - AndroidUtilities.dp(5), cy - AndroidUtilities.dp(9));
                path.lineTo(cx + AndroidUtilities.dp(10), cy);
                path.lineTo(cx - AndroidUtilities.dp(5), cy + AndroidUtilities.dp(9));
                path.close();
                canvas.drawPath(path, paint);
            }
            // Waveform: pauses fade out while skipping is on.
            int bars = 46;
            float step = wave.width() / bars, bw = AndroidUtilities.dp(2.6f);
            float progress = positionMs / (float) skipper.durationMs;
            for (int i = 0; i < bars; i++) {
                float f = (i + 0.5f) / bars;
                int li = Math.min(skipper.levels.length - 1, (int) (f * skipper.levels.length));
                float level = Math.max(0.12f, Math.min(1f, skipper.levels[li] * 1.4f));
                long ms = (long) (f * skipper.durationMs);
                boolean silent = skipper.isSilent(ms);
                int color = f <= progress ? Tg.accent : 0xFFBFD3E6;
                if (skipOn && silent) {
                    color = Tg.alpha(color, 0.3f);
                    level = 0.12f;
                }
                paint.setColor(color);
                float h = wave.height() * level;
                float x = wave.left + i * step;
                canvas.drawRoundRect(x, wave.centerY() - h / 2, x + bw, wave.centerY() + h / 2, bw / 2, bw / 2, paint);
            }
            // Duration.
            long left = skipOn ? remainingWithSkip() : skipper.durationMs - positionMs;
            String t = playing || positionMs > 0 ? time(left) : time(skipOn ? remainingWithSkip() : skipper.durationMs);
            canvas.drawText(t, wave.left, bubble.bottom - AndroidUtilities.dp(14), small);
            // Chip, sized to its label.
            chip.right = chip.left + chipText.measureText(chipLabel()) + AndroidUtilities.dp(38);
            paint.setColor(skipOn ? Tg.alpha(Tg.accent, 0.16f) : 0x0F000000);
            canvas.drawRoundRect(chip, chip.height() / 2, chip.height() / 2, paint);
            int is = AndroidUtilities.dp(16);
            skipIcon.setBounds((int) chip.left + AndroidUtilities.dp(8), (int) (chip.centerY() - is / 2f), (int) chip.left + AndroidUtilities.dp(8) + is, (int) (chip.centerY() + is / 2f));
            skipIcon.setColorFilter(new android.graphics.PorterDuffColorFilter(skipOn ? Tg.accent : Tg.textGray, android.graphics.PorterDuff.Mode.SRC_IN));
            skipIcon.draw(canvas);
            chipText.setColor(skipOn ? Tg.accent : Tg.textGray2);
            canvas.drawText(chipLabel(), chip.left + AndroidUtilities.dp(29), chip.centerY() + AndroidUtilities.dp(4.5f), chipText);
            small.setColor(Tg.timeIn);
            canvas.drawText("18:42", bubble.right - AndroidUtilities.dp(10) - small.measureText("18:42"), bubble.bottom - AndroidUtilities.dp(14), small);
            // A short flash on the waveform when a pause is skipped.
            long flash = SystemClock.elapsedRealtime() - jumpFlashAt;
            if (flash < 450) {
                float a = 1f - flash / 450f;
                paint.setColor(Tg.alpha(Tg.accent, 0.25f * a));
                float x = wave.left + wave.width() * progress;
                canvas.drawCircle(x, wave.centerY(), AndroidUtilities.dp(10) + AndroidUtilities.dp(14) * (1 - a), paint);
            }
            if (playing) {
                postInvalidateOnAnimation();
            }
        }

        private String chipLabel() {
            return skipOn ? "Pauses −" + time(skipper.savedMs()) : "Skip pauses";
        }

        private long remainingWithSkip() {
            long rest = skipper.durationMs - positionMs;
            for (SilenceSkipper.Range r : skipper.ranges) {
                if (r.startMs >= positionMs) {
                    rest -= r.endMs - r.startMs;
                } else if (r.endMs > positionMs) {
                    rest -= r.endMs - positionMs;
                }
            }
            return rest;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                return play.contains(e.getX(), e.getY()) || chip.contains(e.getX(), e.getY());
            }
            if (e.getAction() == MotionEvent.ACTION_UP) {
                if (play.contains(e.getX(), e.getY())) {
                    togglePlay();
                } else if (chip.contains(e.getX(), e.getY())) {
                    skipOn = !skipOn;
                }
                invalidate();
            }
            return true;
        }
    }

    @Override
    protected void onAutoplay(TouchDriver d) {
        d.wait(800)
                .zoom(1.7f, () -> voice, 900)
                .wait(400)
                .tapPoint(() -> {
                    float[] o = d.point(voice, 0, 0);
                    return new float[]{o[0] + voice.play.centerX(), o[1] + voice.play.centerY()};
                })
                .wait(5200)
                .tapPoint(() -> {
                    float[] o = d.point(voice, 0, 0);
                    return new float[]{o[0] + voice.chip.centerX(), o[1] + voice.chip.centerY()};
                })
                .wait(16000)
                .resetZoom(900)
                .wait(1500);
    }
}
