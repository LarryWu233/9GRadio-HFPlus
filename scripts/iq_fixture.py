"""Deterministic IQ fixtures and a loopback-only rtl_tcp test signal source.

AM: carrier at +3 kHz with 1 kHz modulation (60%). NFM: +3 kHz carrier,
1 kHz tone / 2 kHz deviation. These are synthetic test signals, not RF data.
"""
import argparse
import math
from pathlib import Path
import socket
import struct
import threading
import time


def samples(rate, mode):
    for n in range(rate // math.gcd(rate, 1000)):
        t = n / rate
        phase = 2 * math.pi * 3000 * t
        tone = math.sin(2 * math.pi * 1000 * t)
        amplitude = 0.45
        if mode == "am":
            amplitude *= 1 + 0.6 * tone
        elif mode == "nfm":
            phase += 2 * tone
        elif mode == "silence":
            amplitude = 0
        yield amplitude * math.cos(phase), amplitude * math.sin(phase)


def period(rate, mode, fmt):
    if fmt == "cf32":
        return b"".join(struct.pack("<ff", i, q) for i, q in samples(rate, mode))
    return bytes(max(0, min(255, round(127.5 + value * 128)))
                 for pair in samples(rate, mode) for value in pair)


def serve(port, rate, mode):
    with socket.socket() as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("127.0.0.1", port))
        server.listen(1)
        print(f"Synthetic {mode} rtl_tcp listening on 127.0.0.1:{port}", flush=True)
        while True:
            client, _ = server.accept()
            with client:
                client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                client.sendall(struct.pack("!4sII", b"RTL0", 5, 29))
                state = {"rate": rate, "running": True}

                def commands():
                    pending = b""
                    try:
                        while chunk := client.recv(1024):
                            pending += chunk
                            while len(pending) >= 5:
                                code, value = struct.unpack("!BI", pending[:5])
                                pending = pending[5:]
                                if code == 2 and 225000 <= value <= 3200000:
                                    state["rate"] = value
                    except OSError:
                        pass
                    finally:
                        state["running"] = False

                reader = threading.Thread(target=commands, daemon=True)
                reader.start()
                active_rate, block, deadline = 0, b"", time.monotonic()
                try:
                    while state["running"]:
                        if active_rate != state["rate"]:
                            active_rate = state["rate"]
                            wave = period(active_rate, mode, "u8")
                            block = wave * max(1, 16384 // len(wave))
                            print(f"Streaming {active_rate} samples/s", flush=True)
                        client.sendall(block)
                        deadline += len(block) / (2 * active_rate)
                        time.sleep(max(0, deadline - time.monotonic()))
                except OSError:
                    pass
                finally:
                    try:
                        client.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
                    reader.join(timeout=1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sample-rate", type=int, default=1920000)
    parser.add_argument("--mode", choices=["am", "nfm", "silence"], default="am")
    parser.add_argument("--format", choices=["u8", "cf32"], default="u8")
    parser.add_argument("--write", type=Path)
    parser.add_argument("--seconds", type=int, default=2)
    parser.add_argument("--serve", type=int)
    args = parser.parse_args()
    if not 225000 <= args.sample_rate <= 3200000 or args.seconds < 1:
        parser.error("sample rate must be 225000..3200000; seconds must be positive")
    if args.write:
        wave = period(args.sample_rate, args.mode, args.format)
        args.write.parent.mkdir(parents=True, exist_ok=True)
        total = args.sample_rate * args.seconds * (8 if args.format == "cf32" else 2)
        with args.write.open("wb") as output:
            for _ in range(total // len(wave)):
                output.write(wave)
    elif args.serve:
        serve(args.serve, args.sample_rate, args.mode)
    else:
        parser.error("specify --write or --serve")
