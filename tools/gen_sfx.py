#!/usr/bin/env python3
"""
Pramod AI Video Editor — original SFX asset generator.

Synthesizes 14 original, copyright-free sound effects from scratch
(sine/noise primitives + envelopes + filters). No third-party samples.

Output: 44.1 kHz mono 16-bit, peak-normalized to -3 dBFS, DC-free,
click-free ends, each file < 500 KB, encoded to Ogg Vorbis via ffmpeg.

Final assets are written DIRECTLY to the app repo:
    <repo-root>/feature-audio/src/main/assets/sfx/<category>/1.ogg
WAV intermediates go to a temporary directory (cleaned up afterwards).

This script also runs in CI (.github/workflows/build-apk.yml) so the repo
never needs to store binary audio: the APK build always regenerates the
exact same deterministic sounds (seeded RNG).
"""
import math
import os
import shutil
import subprocess
import sys
import tempfile
import wave

import numpy as np
import scipy.signal

SR = 44100
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO_SFX = os.path.join(
    REPO_ROOT, "feature-audio", "src", "main", "assets", "sfx")
OUT = tempfile.mkdtemp(prefix="sfxgen_")

rng = np.random.default_rng(7)        # deterministic builds


# ---------------- helpers ----------------

def sec(s):
    return int(round(s * SR))


def T(n):
    return np.arange(n, dtype=np.float64) / SR


def exp_chirp(f0, f1, n):
    """Sine with exponential pitch glide f0 -> f1 over n samples."""
    t = T(n)
    dur = n / SR
    # instantaneous freq f(t) = f0 * (f1/f0)^(t/dur)
    phase = 2.0 * math.pi * f0 * dur * (np.exp(np.log(f1 / f0) * t / dur) - 1.0) / np.log(f1 / f0)
    return np.sin(phase)


def ar_env(n, a_s, r_s, curve=1.0):
    """Raised-cosine attack / release envelope (1.0 sustain in the middle)."""
    env = np.ones(n)
    na, nr = sec(a_s), sec(r_s)
    if na > 1:
        env[:na] = 0.5 * (1.0 - np.cos(math.pi * np.arange(na) / (na - 1))) ** curve
    if nr > 1:
        env[-nr:] = 0.5 * (1.0 + np.cos(math.pi * np.arange(nr) / (nr - 1))) ** curve
    return env


def exp_decay(n, tau):
    return np.exp(-T(n) / tau)


def crush_samples(x, factor):
    """Downsample-and-hold 'sample rate crush' effect."""
    n = len(x)
    pad = (-n) % factor
    xp = np.pad(x, (0, pad), mode="edge")
    return np.repeat(xp.reshape(-1, factor).mean(axis=1), factor)[:n]


def svf_bandpass(x, f0, f1, q=1.1):
    """Time-varying resonant bandpass (Chamberlin SVF), center sweeps f0->f1."""
    n = len(x)
    fc = f0 * (f1 / f0) ** (np.arange(n) / n)
    f = 2.0 * np.sin(math.pi * fc / SR)
    qq = 1.0 / q
    low = 0.0
    band = 0.0
    out = np.empty(n)
    for i in range(n):
        fi = f[i]
        low += fi * band
        high = x[i] - low - qq * band
        band += fi * high
        out[i] = band
    return out


def butter_low(x, cutoff, order=4):
    sos = scipy.signal.butter(order, cutoff, btype="low", fs=SR, output="sos")
    return scipy.signal.sosfilt(sos, x)


def butter_high(x, cutoff, order=4):
    sos = scipy.signal.butter(order, cutoff, btype="high", fs=SR, output="sos")
    return scipy.signal.sosfilt(sos, x)


def finalize(x, end_a=0.005, end_r=0.015, target_db=-3.0):
    """DC removal, click-free ends, peak-normalize to target_db dBFS."""
    x = np.asarray(x, dtype=np.float64)
    x = np.nan_to_num(x - np.mean(x))
    n = len(x)
    x *= ar_env(n, end_a, end_r)
    peak = float(np.max(np.abs(x)))
    if peak < 1e-9:
        raise RuntimeError("silent sound generated")
    x *= (10.0 ** (target_db / 20.0)) / peak
    return x.astype(np.float32)


def write_wav(path, x):
    pcm = np.clip(np.round(x * 32767.0), -32768, 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def encode_ogg(wav_path, ogg_path):
    cmd = ["ffmpeg", "-y", "-v", "error", "-i", wav_path,
           "-c:a", "libvorbis", "-q:a", "5", ogg_path]
    try:
        subprocess.run(cmd, check=True)
    except subprocess.CalledProcessError:
        cmd[cmd.index("libvorbis")] = "vorbis"   # fall back to native encoder
        subprocess.run(cmd, check=True)


def analyze(path):
    """Decode back and measure real shipped-file stats."""
    tmp = path + ".check.wav"
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", path, tmp], check=True)
    with wave.open(tmp, "rb") as w:
        n = w.getnframes()
        raw = w.readframes(n)
    os.remove(tmp)
    x = np.frombuffer(raw, dtype="<i2").astype(np.float64) / 32768.0
    dur = n / SR
    peak_db = 20 * math.log10(max(1e-9, float(np.max(np.abs(x)))))
    dc = float(np.mean(x))
    return dur, peak_db, dc


# ---------------- the 14 sounds ----------------

def s_whoosh():
    n = sec(0.9)
    noise = rng.standard_normal(n)
    body = svf_bandpass(noise, 400.0, 7000.0, q=1.4)
    body *= ar_env(n, 0.35, 0.45)
    sub = exp_chirp(200.0, 3500.0, n) * ar_env(n, 0.35, 0.45) * 0.25
    return finalize(body + sub)


def s_pop():
    n = sec(0.35)
    tone = exp_chirp(420.0, 110.0, n)
    x = tone * exp_decay(n, 0.06) * ar_env(n, 0.004, 0.05)
    x += 0.3 * exp_chirp(840.0, 220.0, n) * exp_decay(n, 0.04) * ar_env(n, 0.004, 0.03)
    return finalize(x)


def s_impact():
    n = sec(1.0)
    boom = exp_chirp(65.0, 32.0, n) * exp_decay(n, 0.35) * ar_env(n, 0.002, 0.4)
    noise = rng.standard_normal(n)
    thump = butter_low(noise, 400.0) * exp_decay(n, 0.05) * 0.8
    metal = np.sin(2 * math.pi * 197.0 * T(n)) * exp_decay(n, 0.18) * 0.25
    return finalize(boom + thump + metal)


def s_riser():
    n = sec(1.6)
    tone = exp_chirp(180.0, 2200.0, n)
    noise = rng.standard_normal(n)
    air = svf_bandpass(noise, 300.0, 6000.0, q=0.9) * 0.9
    swell = (0.5 * (1.0 - np.cos(math.pi * np.minimum(1.0, T(n) / 1.45)))) ** 1.5
    x = (tone * 0.5 + air + 0.2 * exp_chirp(360.0, 4400.0, n)) * swell
    x *= ar_env(n, 0.0, 0.12)
    return finalize(x)


def s_digital_glitch():
    n = sec(0.7)
    x = np.zeros(n)
    starts = [0.0, 0.09, 0.17, 0.26, 0.38, 0.47, 0.58]
    freqs = [1200.0, 800.0, 2100.0, 500.0, 1600.0, 900.0, 2400.0]
    for st, fr in zip(starts, freqs):
        m = sec(0.07)
        t = T(m)
        blip = np.sign(np.sin(2 * math.pi * fr * t))          # square
        blip = crush_samples(blip, 6)                         # sample-crush
        blip = np.round(blip * 8.0) / 8.0                    # bit-crush
        blip *= ar_env(m, 0.003, 0.03)
        i = sec(st)
        x[i:i + m] += blip * 0.8
    # harsh HF content overshoots slightly after Vorbis encode; leave headroom
    return finalize(x, target_db=-4.5)


def s_sparkle():
    n = sec(1.1)
    x = np.zeros(n)
    for st, fr in [(0.0, 2093.0), (0.12, 2637.0), (0.24, 3136.0), (0.36, 4186.0)]:
        m = sec(0.7)
        t = T(m)
        note = np.sin(2 * math.pi * fr * t) + 0.35 * np.sin(2 * math.pi * 2 * fr * t)
        note *= exp_decay(m, 0.30) * ar_env(m, 0.005, 0.2)
        i = sec(st)
        x[i:i + m] += note * 0.7
    shimmer = np.sin(2 * math.pi * 8500.0 * T(n)) * (0.5 + 0.5 * np.sin(2 * math.pi * 6 * T(n)))
    x += shimmer * exp_decay(n, 0.5) * 0.08
    return finalize(x)


def s_notification():
    n = sec(0.6)
    x = np.zeros(n)
    for st, fr in [(0.0, 880.0), (0.16, 1174.66)]:
        m = sec(0.4)
        t = T(m)
        tone = np.sin(2 * math.pi * fr * t) + 0.3 * np.sin(2 * math.pi * 2 * fr * t)
        tone *= exp_decay(m, 0.16) * ar_env(m, 0.008, 0.15)
        i = sec(st)
        x[i:i + m] += tone * 0.8
    return finalize(x)


def s_cinematic_hit():
    n = sec(1.5)
    boom = exp_chirp(58.0, 28.0, n) * exp_decay(n, 0.6) * ar_env(n, 0.003, 0.7)
    noise = rng.standard_normal(n)
    crash = butter_high(noise, 1200.0) * exp_decay(n, 0.45) * 0.5
    crack = butter_low(butter_high(noise, 500.0), 1200.0) * exp_decay(n, 0.1) * 0.9
    return finalize(boom + crash + crack)


def s_ambient_atmosphere():
    n = sec(3.5)
    t = T(n)
    x = np.zeros(n)
    for fr in (110.0, 164.81, 220.0, 277.18):
        for det in (-3.0, 3.0):  # cents
            f = fr * 2.0 ** (det / 1200.0)
            x += np.sin(2 * math.pi * f * t) + 0.25 * np.sin(2 * math.pi * 2 * f * t)
    x *= 0.25
    bed = butter_low(rng.standard_normal(n), 300.0) * 0.15
    x = (x + bed) * ar_env(n, 1.2, 1.5)
    return finalize(x)


def s_victory_accent():
    n = sec(1.2)
    x = np.zeros(n)
    notes = [(0.0, 523.25), (0.13, 659.25), (0.26, 783.99), (0.39, 1046.5)]
    for st, fr in notes:
        m = sec(0.7)
        t = T(m)
        note = (np.sin(2 * math.pi * fr * t) + 0.5 * np.sin(2 * math.pi * 2 * fr * t)
                + 0.25 * np.sin(2 * math.pi * 3 * fr * t))
        note *= exp_decay(m, 0.30) * ar_env(m, 0.006, 0.25)
        i = sec(st)
        x[i:i + m] += note * 0.55
    return finalize(x)


def s_soft_transition():
    n = sec(0.8)
    glide = exp_chirp(520.0, 260.0, n)
    air = butter_low(rng.standard_normal(n), 800.0) * 0.5
    x = (glide * 0.6 + air) * ar_env(n, 0.25, 0.4)
    return finalize(x)


def s_educational_cue():
    n = sec(0.5)
    t = T(n)
    x = (np.sin(2 * math.pi * 1046.5 * t) + 0.5 * np.sin(2 * math.pi * 1568.0 * t)
         + 0.2 * np.sin(2 * math.pi * 2093.0 * t))
    x *= exp_decay(n, 0.22) * ar_env(n, 0.008, 0.18)
    return finalize(x)


def s_gaming_accent():
    n = sec(0.7)
    x = np.zeros(n)
    for st, fr in [(0.0, 987.77), (0.09, 1318.51)]:
        m = sec(0.35)
        t = T(m)
        coin = np.sin(2 * math.pi * fr * t) + 0.4 * np.sin(2 * math.pi * 3 * fr * t)
        coin *= exp_decay(m, 0.09) * ar_env(m, 0.004, 0.12)
        i = sec(st)
        x[i:i + m] += coin * 0.8
    return finalize(x)


def s_emotional_accent():
    n = sec(1.8)
    t = T(n)
    x = np.zeros(n)
    for fr, g in ((220.0, 0.5), (329.63, 0.4), (440.0, 0.35), (554.37, 0.25)):
        vib = 1.0 + 0.003 * np.sin(2 * math.pi * 5.0 * t)   # gentle vibrato
        x += g * (np.sin(2 * math.pi * fr * vib * t)
                  + 0.3 * np.sin(2 * math.pi * 2 * fr * t))
    x *= ar_env(n, 0.6, 0.8)
    return finalize(x)


CATALOG = [
    ("whoosh", s_whoosh),
    ("pop", s_pop),
    ("impact", s_impact),
    ("riser", s_riser),
    ("digital_glitch", s_digital_glitch),
    ("sparkle", s_sparkle),
    ("notification", s_notification),
    ("cinematic_hit", s_cinematic_hit),
    ("ambient_atmosphere", s_ambient_atmosphere),
    ("victory_accent", s_victory_accent),
    ("soft_transition", s_soft_transition),
    ("educational_cue", s_educational_cue),
    ("gaming_accent", s_gaming_accent),
    ("emotional_accent", s_emotional_accent),
]


def main():
    os.makedirs(OUT, exist_ok=True)
    print(f"{'category':<20}{'dur':>7}{'peak':>9}{'dc':>11}{'ogg KB':>9}  status")
    ok = True
    for cat, fn in CATALOG:
        x = fn()
        wav = os.path.join(OUT, f"{cat}.wav")
        write_wav(wav, x)
        dest_dir = os.path.join(REPO_SFX, cat)
        os.makedirs(dest_dir, exist_ok=True)
        ogg = os.path.join(dest_dir, "1.ogg")
        encode_ogg(wav, ogg)
        dur, peak_db, dc = analyze(ogg)
        kb = os.path.getsize(ogg) / 1024.0
        good = kb < 500 and abs(peak_db + 3.0) < 1.5 and abs(dc) < 0.01
        ok = ok and good
        print(f"{cat:<20}{dur:>6.2f}s{peak_db:>8.2f}dB{dc:>+10.4f}{kb:>8.1f}  "
              f"{'OK' if good else 'CHECK'}")
    print("\nALL OK" if ok else "\nSOME CHECKS FAILED")
    shutil.rmtree(OUT, ignore_errors=True)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
