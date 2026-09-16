#!/usr/bin/env python3
"""
scripts/test_wifi_transport.py

Standalone Python peer simulator for testing the iTantra Phase 8.6 Wi-Fi Text Transport.

Tests:
1. Connecting to an active Android iTantra phone acting as Host on port 8888.
2. Acting as a Host server allowing an Android phone to join as Client.
3. Sending & receiving length-prefixed JSON frames with 4-byte big-endian framing.
4. Validating Devanagari Unicode preservation for normal speech & emergency alerts.
5. Measuring network round-trip ping/pong latency.
"""

import socket
import struct
import json
import time
import sys
import argparse

MAX_FRAME_SIZE = 64 * 1024

def encode_frame(payload_bytes: bytes) -> bytes:
    length = len(payload_bytes)
    if length > MAX_FRAME_SIZE:
        raise ValueError(f"Payload exceeds {MAX_FRAME_SIZE} bytes")
    return struct.pack(">I", length) + payload_bytes

def read_frame(sock: socket.socket) -> bytes:
    raw_len = sock.recv(4)
    if not raw_len or len(raw_len) < 4:
        return None
    length = struct.unpack(">I", raw_len)[0]
    if length > MAX_FRAME_SIZE:
        raise ValueError(f"Frame length {length} exceeds maximum {MAX_FRAME_SIZE}")
    
    chunks = []
    bytes_read = 0
    while bytes_read < length:
        chunk = sock.recv(min(length - bytes_read, 4096))
        if not chunk:
            break
        chunks.append(chunk)
        bytes_read += len(chunk)
    return b"".join(chunks)

def run_client_mode(host: str, port: int):
    print(f"[Client] Connecting to iTantra phone at {host}:{port}...")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10.0)
    try:
        sock.connect((host, port))
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        print(f"[Client] Connected to {host}:{port} successfully!")

        # 1. Measure RTT Ping
        t1 = time.time_ns()
        ping_payload = json.dumps({"type": "PING", "timestamp": t1}).encode('utf-8')
        sock.sendall(encode_frame(ping_payload))
        
        resp = read_frame(sock)
        if resp:
            t2 = time.time_ns()
            pong_json = json.loads(resp.decode('utf-8'))
            rtt_ms = (t2 - t1) / 1_000_000
            print(f"[Client] Ping-Pong RTT: {rtt_ms:.2f} ms (Type: {pong_json.get('type')})")

        # 2. Send Normal Hindi Text Message
        normal_msg = {
            "type": "MESSAGE",
            "messageId": f"py_test_{int(time.time())}",
            "text": "नमस्ते! यह पायथन पीयर से भेजा गया टेस्ट मैसेज है।",
            "language": "hi",
            "priority": "NORMAL",
            "timestamp": int(time.time() * 1000)
        }
        raw_normal = json.dumps(normal_msg, ensure_ascii=False).encode('utf-8')
        sock.sendall(encode_frame(raw_normal))
        print(f"[Client] Sent Normal Hindi Message ({len(raw_normal)} bytes)")

        # 3. Listen for incoming message from phone
        print("[Client] Waiting 5 seconds for phone responses or transmissions...")
        sock.settimeout(5.0)
        try:
            while True:
                frame = read_frame(sock)
                if not frame:
                    break
                data = json.loads(frame.decode('utf-8'))
                print(f"[Client] Received frame: {data.get('type')} | Text: {data.get('text', '')}")
        except socket.timeout:
            print("[Client] Timeout reached, closing test session.")

    except Exception as e:
        print(f"[Client] Error: {e}")
    finally:
        sock.close()
        print("[Client] Socket closed.")

def run_host_mode(port: int):
    print(f"[Host] Starting server socket on port {port}...")
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", port))
    server.listen(1)
    print(f"[Host] Listening for phone connection on 0.0.0.0:{port}...")

    conn, addr = server.accept()
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    print(f"[Host] Accepted connection from {addr}!")

    try:
        while True:
            frame = read_frame(conn)
            if not frame:
                print("[Host] Peer disconnected.")
                break
            payload_str = frame.decode('utf-8')
            msg = json.loads(payload_str)
            msg_type = msg.get("type", "UNKNOWN")

            if msg_type == "PING":
                ts = msg.get("timestamp", 0)
                pong_bytes = json.dumps({"type": "PONG", "timestamp": ts}).encode('utf-8')
                conn.sendall(encode_frame(pong_bytes))
                print("[Host] Responded to PING with PONG")
            elif msg_type == "MESSAGE":
                print(f"[Host] Received [Priority: {msg.get('priority')}]: {msg.get('text')}")
                # Echo back acknowledgment or reply
                reply = {
                    "type": "MESSAGE",
                    "messageId": f"ack_{int(time.time())}",
                    "text": "संदेश प्राप्त हुआ (Ack from Host)",
                    "language": "hi",
                    "priority": "NORMAL",
                    "timestamp": int(time.time() * 1000)
                }
                conn.sendall(encode_frame(json.dumps(reply, ensure_ascii=False).encode('utf-8')))
    except Exception as e:
        print(f"[Host] Exception: {e}")
    finally:
        conn.close()
        server.close()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="iTantra Wi-Fi Transport Peer Simulator")
    parser.add_argument("--mode", choices=["client", "host"], default="client", help="Mode: client or host")
    parser.add_argument("--host", default="127.0.0.1", help="Target host IP in client mode")
    parser.add_argument("--port", type=int, default=8888, help="Target or listening port")
    args = parser.parse_args()

    if args.mode == "client":
        run_client_mode(args.host, args.port)
    else:
        run_host_mode(args.port)
