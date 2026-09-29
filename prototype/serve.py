"""Serve the Vaultaire UI prototype.

Usage:
    python prototype/serve.py [port]          this computer only (default port 8000)
    python prototype/serve.py --lan [port]    also reachable from phones on the same Wi-Fi

The prototype holds sample data only. LAN mode still refuses any client that is
not on a private network address.
"""
import functools
import http.server
import ipaddress
import pathlib
import socket
import sys

ROOT = pathlib.Path(__file__).resolve().parent
ARGS = [a for a in sys.argv[1:] if a != "--lan"]
LAN = "--lan" in sys.argv[1:]
PORT = int(ARGS[0]) if ARGS else 8000


class Handler(http.server.SimpleHTTPRequestHandler):
    # Explicit types: on Windows, mimetypes can pick up odd values from the registry.
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        ".html": "text/html; charset=utf-8",
        ".css": "text/css; charset=utf-8",
        ".js": "text/javascript; charset=utf-8",
        ".webmanifest": "application/manifest+json",
        ".woff2": "font/woff2",
        ".svg": "image/svg+xml",
    }

    def setup(self):
        super().setup()
        # In LAN mode, only answer devices on a private network (home Wi-Fi) or this computer.
        client = ipaddress.ip_address(self.client_address[0])
        self.allowed = client.is_private or client.is_loopback

    def handle_one_request(self):
        if not self.allowed:
            self.close_connection = True
            return
        super().handle_one_request()

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        super().end_headers()


def lan_address():
    """The address other devices on the Wi-Fi use to reach this computer. Sends no packets."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]


if __name__ == "__main__":
    handler = functools.partial(Handler, directory=str(ROOT))
    host = "0.0.0.0" if LAN else "127.0.0.1"
    with http.server.ThreadingHTTPServer((host, PORT), handler) as httpd:
        print(f"Vaultaire prototype: http://localhost:{PORT}/", flush=True)
        if LAN:
            print(f"On your phone (same Wi-Fi): http://{lan_address()}:{PORT}/", flush=True)
        print("Ctrl+C to stop.", flush=True)
        httpd.serve_forever()
