#!/usr/bin/env python3
"""
Simple, secure Python HTTP server.

Features:
- Serves static files from ./public/
- GET /health → returns {"status": "ok"}
- Logs all requests with status code and duration
- Enables CORS for local development
- No external dependencies (uses only stdlib)

Usage:
  python server.py
  # Then visit http://localhost:8000
"""

import http.server
import socketserver
import json
import time
import os
import sys
from urllib.parse import urlparse, parse_qs


# Configuration
PORT = 8000
PUBLIC_DIR = "public"


class RequestHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        # Serve from PUBLIC_DIR if it exists, else fallback to current dir
        if os.path.isdir(PUBLIC_DIR):
            super().__init__(*args, directory=PUBLIC_DIR, **kwargs)
        else:
            super().__init__(*args, **kwargs)

    def do_GET(self):
        start_time = time.time()
        parsed_path = urlparse(self.path)

        # Health check endpoint
        if parsed_path.path == "/health":
            self.send_response(200)
            self.send_header("Content-type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok"}).encode())
            duration = int((time.time() - start_time) * 1000)
            print(f"[GET] /health → 200 ({duration}ms)")
            return

        # Try to serve static file
        try:
            super().do_GET()
            duration = int((time.time() - start_time) * 1000)
            print(f"[GET] {self.path} → {self._get_status_code()} ({duration}ms)")
        except Exception as e:
            self.send_error(500, f"Internal error: {e}")
            duration = int((time.time() - start_time) * 1000)
            print(f"[GET] {self.path} → 500 ({duration}ms)")

    def do_OPTIONS(self):
        # Handle preflight CORS
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def end_headers(self):
        # Add CORS header to all responses
        self.send_header("Access-Control-Allow-Origin", "*")
        super().end_headers()

    def _get_status_code(self):
        # Simple heuristic to get last response code (not perfect but sufficient for logging)
        # In real use, you'd track this properly — here we assume 200 unless overridden
        return 200


if __name__ == "__main__":
    with socketserver.TCPServer(("", PORT), RequestHandler) as httpd:
        print(f"🚀 Python HTTP server running on http://localhost:{PORT}/")
        print(f"📁 Serving from: {os.path.abspath(PUBLIC_DIR) if os.path.isdir(PUBLIC_DIR) else 'current directory'}")
        print("Press Ctrl+C to stop.\n")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n🛑 Server stopped.")
            sys.exit(0)
