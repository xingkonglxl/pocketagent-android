#!/usr/bin/env python3
"""Temporary loopback SSH server for the real SshRemoteShell duplex regression.

Requires Paramiko in an isolated Python environment. Only accepts the generated
test key, binds 127.0.0.1, and deletes all generated keys on exit. It never reads
the user's SSH keys, SSH config, or Codex credentials. Run with --state /tmp/...
and pass that path to Gradle as POCKETAGENT_SSH_LOOPBACK_STATE.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import tempfile
import threading

import paramiko
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", required=True)
    args = parser.parse_args()
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix="pocketagent-loopback-") as directory:
        directory = Path(directory)
        host_key = paramiko.RSAKey.generate(2048)
        client_key = paramiko.RSAKey.generate(2048)
        client_file = directory / "client.pem"
        client_key.write_private_key_file(str(client_file))
        encoded = host_key.key.public_key().public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)
        fingerprint = "SHA256:" + base64.b64encode(hashlib.sha256(encoded).digest()).decode().rstrip("=")
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(8)
        stopped = threading.Event()
        transports = []
        processes = []

        class Server(paramiko.ServerInterface):
            def __init__(self):
                self.requests = {}

            def check_auth_publickey(self, username, key):
                return paramiko.AUTH_SUCCESSFUL if username == "pocketagent-test" and key == client_key else paramiko.AUTH_FAILED

            def get_allowed_auths(self, username):
                return "publickey"

            def check_channel_request(self, kind, channel_id):
                return paramiko.OPEN_SUCCEEDED if kind == "session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

            def check_channel_exec_request(self, channel, command):
                event = self.requests.setdefault(channel.chanid, threading.Event())
                channel.test_command = command.decode()
                event.set()
                return True

        def bridge(channel):
            # Child inherits a clean, temporary Codex profile if a fixture invokes Codex.
            environment = dict(os.environ, CODEX_HOME=str(directory / "empty-codex-profile"))
            process = subprocess.Popen(["/bin/sh", "-c", channel.test_command], env=environment,
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            processes.append(process)

            def write_input():
                try:
                    while True:
                        chunk = channel.recv(65536)
                        if not chunk:
                            break
                        process.stdin.write(chunk)
                        process.stdin.flush()
                except (OSError, EOFError):
                    pass
                finally:
                    process.stdin.close()

            def read_output(stream, send):
                try:
                    while True:
                        chunk = os.read(stream.fileno(), 65536)
                        if not chunk:
                            break
                        send(chunk)
                except (OSError, EOFError):
                    pass

            threading.Thread(target=write_input, daemon=True).start()
            out = threading.Thread(target=read_output, args=(process.stdout, channel.sendall), daemon=True)
            err = threading.Thread(target=read_output, args=(process.stderr, channel.sendall_stderr), daemon=True)
            out.start()
            err.start()
            status = process.wait()
            out.join(2)
            err.join(2)
            try:
                channel.send_exit_status(status)
                channel.close()
            except (OSError, EOFError):
                pass

        def connect(connection):
            transport = paramiko.Transport(connection)
            transports.append(transport)
            transport.add_server_key(host_key)
            server = Server()
            try:
                transport.start_server(server=server)
                while transport.is_active() and not stopped.is_set():
                    channel = transport.accept(0.2)
                    if channel is not None:
                        event = server.requests.setdefault(channel.chanid, threading.Event())
                        if event.wait(5):
                            threading.Thread(target=bridge, args=(channel,), daemon=True).start()
            except (OSError, EOFError, paramiko.SSHException):
                pass
            finally:
                transport.close()

        def stop(*_):
            stopped.set()
            listener.close()

        signal.signal(signal.SIGTERM, stop)
        signal.signal(signal.SIGINT, stop)
        state = Path(args.state)
        state.write_text(json.dumps({"port": listener.getsockname()[1], "clientKeyFile": str(client_file), "fingerprint": fingerprint}))
        print("READY loopback SSH regression state: " + str(state), flush=True)
        try:
            while not stopped.is_set():
                try:
                    connection, _ = listener.accept()
                except OSError:
                    break
                threading.Thread(target=connect, args=(connection,), daemon=True).start()
        finally:
            for transport in transports:
                transport.close()
            for process in processes:
                if process.poll() is None:
                    process.terminate()
            state.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
