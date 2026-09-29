#!/usr/bin/env python3
"""An independent Mulberry32, written from the published algorithm rather than
from src/ccg/Rng.kt, so the vector it prints is evidence about the Kotlin port
and not a copy of it. `python3 test/golden/mulberry32.py > test/golden/rng.txt`.

Each line: seed (signed 32-bit, as GameState.rngState holds it) then the first
eight raw outputs as unsigned 32-bit integers. A port checks its own Rng against
these before anything else: the algorithm relies on 32-bit wrapping."""

M = 0xFFFFFFFF

def imul(a, b):
    return (a * b) & M

def mulberry32(seed):
    a = seed & M
    while True:
        a = (a + 0x6D2B79F5) & M
        t = a
        t = imul(t ^ (t >> 15), t | 1)
        t = (t ^ ((t + imul(t ^ (t >> 7), t | 61)) & M)) & M
        yield (t ^ (t >> 14)) & M

def signed(x):
    return x - (1 << 32) if x & 0x80000000 else x

for seed in [0, 1, 7, -1, 20260925, signed(0x9E3779B9), 2147483647, -2147483648]:
    g = mulberry32(seed)
    print(seed, " ".join(str(next(g)) for _ in range(8)))

# shuffle: Fisher-Yates from the top index down, j = floor(u32 / 2^32 * (i+1)),
# over the list 0..n-1. The engine's only use of the RNG.
for seed, n in [(7, 10), (20260925, 40)]:
    g = mulberry32(seed)
    a = list(range(n))
    for i in range(n - 1, -1, -1):
        j = int(next(g) / 4294967296.0 * (i + 1))
        a[i], a[j] = a[j], a[i]
    print("shuffle", seed, n, " ".join(map(str, a)))
