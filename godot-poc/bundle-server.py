#!/usr/bin/env python3
"""Loopback bundle server -- the Termux half of the Godot PoC's probe B.

WHY THIS EXISTS: the Godot app cannot read Termux's home (drwx------), and on
Android 11+ it may not be able to read a plain .json off shared storage either
without a broad permission. A loopback socket needs neither: both processes are
on the same device, so 127.0.0.1 is reachable from any app without INTERNET
being meaningful and without any storage permission at all.

This is deliberately the crudest thing that answers the question -- it is a
stage-0 probe, not the wire protocol. The real one carries
`Answer` objects both ways and is served by the Kotlin engine, not by Python.

    python3 godot-poc/bundle-server.py [bundle.json] [port]

Serves the bundle to any client that connects, then closes -- the close IS the
end-of-message marker the PoC's reader waits for.
"""
import socket
import sys
import os

# The bundled EPR Skirmish, from this repo.
DEFAULT_BUNDLE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "content", "epr-skirmish.json")
HOST = "127.0.0.1"


def main() -> int:
    bundle = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_BUNDLE
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 9944

    if not os.path.exists(bundle):
        print(f"no bundle at {bundle}", file=sys.stderr)
        return 1
    with open(bundle, "rb") as fh:
        payload = fh.read()
    print(f"serving {len(payload)} bytes from {bundle} on {HOST}:{port}")
    print("ctrl-c to stop")

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((HOST, port))
    srv.listen(4)
    try:
        while True:
            conn, addr = srv.accept()
            with conn:
                conn.settimeout(5.0)
                try:
                    request = conn.recv(256)
                except socket.timeout:
                    request = b""
                print(f"  {addr} asked: {request!r} -> sending {len(payload)} bytes")
                conn.sendall(payload)
            # closing is the end-of-message marker
    except KeyboardInterrupt:
        print("\nstopped")
    finally:
        srv.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
