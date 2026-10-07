#!/usr/bin/env python3
"""
Original house track for the trailer (build/trailer-music.wav, not committed), synthesised from scratch with numpy:
128 BPM, 20 bars (37.5 s): pad swell -> kick / hats / bass build -> snare roll + riser + break -> full drop -> outro.
No samples, no third-party material.

  python3 scripts/make_music.py [out.wav]
"""
import os
import sys
import wave

import numpy as np

SR = 44100
BPM = 128
BEAT = 60.0 / BPM
BAR = BEAT * 4
BARS = 20
N = int(SR * BAR * BARS)
rng = np.random.default_rng(7)


def t_of(n):
    return np.arange(n) / SR


def mix(buf, sig, at):
    i = int(at * SR)
    if i >= len(buf):
        return
    sig = sig[: len(buf) - i]
    buf[i:i + len(sig)] += sig


def kick(vol=1.0):
    t = t_of(int(SR * 0.42))
    f = 45 + 110 * np.exp(-t * 28)
    ph = 2 * np.pi * np.cumsum(f) / SR
    return vol * np.sin(ph) * np.exp(-t * 7.5) * (1 + 0.6 * np.exp(-t * 60))


def hat(open_=False, vol=0.3):
    n = int(SR * (0.22 if open_ else 0.05))
    t = t_of(n)
    noise = rng.standard_normal(n)
    noise = np.diff(noise, prepend=0)  # crude high-pass
    return vol * noise * np.exp(-t * (14 if open_ else 70))


def clap(vol=0.5):
    n = int(SR * 0.25)
    t = t_of(n)
    noise = rng.standard_normal(n)
    env = np.exp(-t * 22)
    for k in (0.0, 0.012, 0.024):  # three quick bursts
        env += 0.7 * np.exp(-np.maximum(t - k, 0) * 60) * (t >= k)
    return vol * noise * env * 0.5


def bass(freq, dur, vol=0.5):
    n = int(SR * dur)
    t = t_of(n)
    saw = 2 * ((t * freq) % 1.0) - 1
    sub = np.sin(2 * np.pi * freq * t)
    # a simple one-pole low-pass that opens with the note
    sig = 0.6 * saw + sub
    a = np.exp(-t * 9)
    out = np.zeros(n)
    y = 0.0
    for i in range(n):
        k = 0.05 + 0.25 * a[i]
        y += k * (sig[i] - y)
        out[i] = y
    return vol * out * np.minimum(1, t * 400) * np.exp(-t * 3.2)


def chord(freqs, dur, vol=0.18, bright=1.0):
    n = int(SR * dur)
    t = t_of(n)
    out = np.zeros(n)
    for f in freqs:
        for det in (-0.006, 0.0, 0.006):
            out += 2 * (((t * f * (1 + det)) % 1.0)) - 1
    out /= max(1, len(freqs) * 3)
    # low-pass by moving average, brighter = shorter window
    w = max(2, int(14 / bright))
    out = np.convolve(out, np.ones(w) / w, mode="same")
    env = np.minimum(1, t * 8) * np.minimum(1, (dur - t) * 8)
    return vol * out * env


def pad(freqs, dur, vol=0.2):
    n = int(SR * dur)
    t = t_of(n)
    out = np.zeros(n)
    for f in freqs:
        for det in (-0.004, 0.004):
            out += np.sin(2 * np.pi * f * (1 + det) * t) + 0.4 * np.sin(4 * np.pi * f * (1 + det) * t)
    out /= len(freqs) * 2
    env = np.minimum(1, t / (dur * 0.7)) * np.minimum(1, (dur - t) * 3)
    return vol * out * env


def riser(dur, vol=0.35):
    n = int(SR * dur)
    t = t_of(n)
    p = t / dur
    noise = rng.standard_normal(n)
    # noise that gets brighter: blend a smoothed and a raw version
    smooth = np.convolve(noise, np.ones(40) / 40, mode="same")
    sig = (1 - p) * smooth * 4 + p * np.diff(noise, prepend=0) * 0.7
    tone = np.sin(2 * np.pi * np.cumsum(200 + 1800 * p ** 2) / SR)
    return vol * (0.7 * sig + 0.5 * tone) * p ** 1.5


def snare(vol=0.4):
    n = int(SR * 0.2)
    t = t_of(n)
    return vol * (rng.standard_normal(n) * np.exp(-t * 20) * 0.7 + np.sin(2 * np.pi * 190 * t) * np.exp(-t * 25) * 0.5)


def crash(vol=0.4):
    n = int(SR * 2.2)
    t = t_of(n)
    return vol * np.diff(rng.standard_normal(n), prepend=0) * np.exp(-t * 2.2)


def note(m):
    return 440.0 * 2 ** ((m - 69) / 12)


# Am - F - C - G (epic minor progression), one chord per bar pair... one per bar here
PROG = [(57, [57, 60, 64]), (53, [53, 57, 60]), (48, [55, 60, 64]), (55, [55, 59, 62])]  # (bass root, chord)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "build", "trailer-music.wav")
    drums = np.zeros(N)
    bassb = np.zeros(N)
    harm = np.zeros(N)
    fx = np.zeros(N)
    side = np.zeros(N)  # sidechain envelope source (kick times)
    kicks = []

    for bar in range(BARS):
        t0 = bar * BAR
        root, ch = PROG[bar % 4]
        has_kick = 2 <= bar < 9 or 10 <= bar < 18
        drop = 10 <= bar < 18
        # harmony
        if bar < 2:
            mix(harm, pad([note(m + 12) for m in ch] + [note(root)], BAR, 0.22), t0)
        elif bar < 10:
            mix(harm, pad([note(m + 12) for m in ch], BAR, 0.16 + 0.01 * bar), t0)
            if bar >= 6:  # arpeggio builds the tension
                for s in range(8):
                    mix(harm, chord([note(ch[s % 3] + 12)], BEAT / 2 * 0.9, 0.10, 2.0), t0 + s * BEAT / 2)
        elif drop:
            # off-beat stabs + a held lead pad
            for b in range(4):
                mix(harm, chord([note(m + 12) for m in ch], BEAT * 0.45, 0.24, 1.4), t0 + b * BEAT + BEAT / 2)
            mix(harm, pad([note(m + 24) for m in ch], BAR, 0.10), t0)
        else:  # outro
            mix(harm, pad([note(m + 12) for m in ch] + [note(root)], BAR, 0.24), t0)
        # drums
        if has_kick and not (bar == 9):
            for b in range(4):
                mix(drums, kick(0.95 if drop else 0.4 + 0.04 * bar), t0 + b * BEAT)
                kicks.append(t0 + b * BEAT)
        if 4 <= bar < 9 or drop:
            for s in range(8):
                mix(drums, hat(False, 0.20 if not drop else 0.28), t0 + s * BEAT / 2 + BEAT / 2 * 0.0 + (BEAT / 4 if s % 2 else 0) * 0)
            for b in range(4):
                mix(drums, hat(True, 0.22), t0 + b * BEAT + BEAT / 2)
        if drop or 6 <= bar < 9:
            for b in (1, 3):
                mix(drums, clap(0.45 if drop else 0.25), t0 + b * BEAT)
        # snare roll in bar 9 (accelerating), then silence on the last beat for the drop
        if bar == 8:
            pass
        if bar == 9:
            hits = [0, 0.5, 1, 1.5, 2, 2.25, 2.5, 2.75, 3, 3.125, 3.25, 3.375, 3.5, 3.625, 3.75, 3.875]
            for i, h in enumerate(hits):
                mix(drums, snare(0.18 + 0.35 * i / len(hits)), t0 + h * BEAT)
        # bass: rolling offbeat from bar 6, full on the drop
        if 6 <= bar < 9 or drop:
            for s in range(4):
                mix(bassb, bass(note(root - 12), BEAT * 0.9, 0.55 if drop else 0.35), t0 + s * BEAT + BEAT / 2)
        # fx
        if bar == 0:
            mix(fx, riser(BAR * 2, 0.16), 0)
        if bar == 8:
            mix(fx, riser(BAR * 2, 0.5), t0)
        if bar == 10 or bar == 18:
            mix(fx, crash(0.5), t0)
        if bar == 19:
            mix(fx, crash(0.25), t0)
    # sidechain: harmony and bass duck on every kick
    duck = np.ones(N)
    for k in kicks:
        i = int(k * SR)
        n = int(SR * 0.28)
        seg = 1 - 0.75 * np.exp(-t_of(n) * 12)
        duck[i:i + n] = np.minimum(duck[i:i + n], seg[: N - i][:n] if i + n > N else seg)
    song = drums + (bassb + harm) * duck + fx
    # fade in and out, normalise
    fade_in = np.minimum(1, t_of(N) / 1.2)
    fade_out = np.minimum(1, (N / SR - t_of(N)) / 2.5)
    song = song * fade_in * fade_out
    song = np.tanh(song * 1.4) / np.tanh(1.4)
    song = song / max(1e-9, np.max(np.abs(song))) * 0.85
    # light stereo: the arpeggio / stabs left-right via a tiny delay
    delay = int(0.012 * SR)
    left = song
    right = np.concatenate([np.zeros(delay), song[:-delay]]) * 0.97 + harm * 0.0
    pcm = (np.stack([left, right], axis=1) * 32767).astype("<i2")
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with wave.open(out, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    print("wrote", os.path.relpath(out), f"{N / SR:.1f}s")


if __name__ == "__main__":
    main()
