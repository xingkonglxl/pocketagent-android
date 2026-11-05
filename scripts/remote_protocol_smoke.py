#!/usr/bin/env python3
"""Verify the installed Codex app-server protocol without credentials or model calls.

Uses a temporary, empty Codex profile and starts no turn. This can be run on a
remote host before enabling live supplementary instructions in /workspace.
"""

import argparse
import json
import os
import selectors
import shutil
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codex", default=shutil.which("codex"), help="Path to the Codex CLI")
    args = parser.parse_args()
    if not args.codex:
        parser.error("Codex CLI was not found; pass --codex /path/to/codex")

    with tempfile.TemporaryDirectory(prefix="pocketagent-protocol-") as profile:
        protocol_env = os.environ.copy()
        # CODEX_HOME is used for its documented purpose: an isolated CLI profile.
        protocol_env["CODEX_HOME"] = profile
        process = subprocess.Popen(
            [args.codex, "app-server"], env=protocol_env,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        )
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ)
        buffer = b""
        responses = {}
        sequence = 0

        def send(message):
            process.stdin.write((json.dumps(message) + "\n").encode())
            process.stdin.flush()

        def rpc(method, params):
            nonlocal buffer, sequence
            sequence += 1
            request_id = sequence
            send({"id": request_id, "method": method, "params": params})
            deadline = time.monotonic() + 12
            while time.monotonic() < deadline:
                if request_id in responses:
                    return responses.pop(request_id)
                if not selector.select(0.2):
                    continue
                chunk = os.read(process.stdout.fileno(), 65536)
                if not chunk:
                    raise RuntimeError("app-server closed before returning a response")
                buffer += chunk
                while b"\n" in buffer:
                    line, buffer = buffer.split(b"\n", 1)
                    if line:
                        message = json.loads(line)
                        if "id" in message:
                            responses[message["id"]] = message
            raise RuntimeError("app-server protocol response timed out")

        try:
            initialized = rpc("initialize", {
                "clientInfo": {"name": "pocketagent_protocol_smoke", "version": "1"},
                "capabilities": {"optOutNotificationMethods": [
                    "item/commandExecution/outputDelta", "item/reasoning/textDelta",
                ]},
            })
            assert "result" in initialized, "initialize rejected the notification capabilities"
            send({"method": "initialized"})
            print("PASS initialize with notification filtering; experimentalApi disabled")

            started = rpc("thread/start", {"cwd": profile})
            assert "result" in started, "thread/start failed"
            thread_id = started["result"]["thread"]["id"]
            print("PASS thread/start in an empty temporary profile (no turn started)")

            steer = rpc("turn/steer", {
                "threadId": thread_id,
                "expectedTurnId": "00000000-0000-0000-0000-000000000001",
                "input": [{"type": "text", "text": "Protocol validation only"}],
            })
            assert steer.get("error", {}).get("code") != -32601, "turn/steer is unsupported"
            assert "error" in steer, "steering a thread with no active turn must be rejected"
            print("PASS turn/steer exists and rejects a missing active turn")

            turns = rpc("thread/turns/list", {
                "threadId": thread_id, "limit": 1, "sortDirection": "desc", "itemsView": "full",
            })
            assert turns.get("error", {}).get("code") != -32601, "thread/turns/list is unsupported"
            assert turns.get("error", {}).get("code") != -32602, "bounded turn lookup parameters are unsupported"
            print("PASS thread/turns/list accepts bounded turn lookup parameters")
            items = rpc("thread/items/list", {"threadId": thread_id, "limit": 32})
            print("INFO thread/items/list available: " + str("result" in items).lower())
            print("PASS no credentials loaded and no model request sent")
        finally:
            process.stdin.close()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.terminate()
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=3)
            selector.close()


if __name__ == "__main__":
    main()
