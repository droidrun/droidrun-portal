#!/usr/bin/env python3
"""
Automated smoke test for Portal device event streaming.

What it covers:
1) Local WebSocket default unsolicited device events (legacy raw frames)
2) Local WebSocket RPC opt-in events (`?eventFormat=rpc`)
3) Reverse connection unsolicited device events (`events/device`)
4) Local raw PING/PONG compatibility
5) A small automated event set: notification post + app enter
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlencode, unquote_plus, urlparse


APP_AUTHORITY = "com.mobilerun.portal"
DEFAULT_LOCAL_PORT = 8081
DEFAULT_REVERSE_PORT = 8765
DEFAULT_TIMEOUT_SEC = 15.0
REVERSE_SERVICE_COMPONENT = "com.mobilerun.portal/.service.ReverseConnectionService"
websockets = None


class SmokeError(RuntimeError):
    pass


def require_websockets() -> Any:
    global websockets
    if websockets is not None:
        return websockets
    try:
        import websockets as websockets_module
    except ModuleNotFoundError as exc:
        raise SmokeError(
            "python package 'websockets' is required; install it with "
            "`python3 -m pip install websockets`"
        ) from exc
    websockets = websockets_module
    return websockets


def log(message: str) -> None:
    print(message, flush=True)


def run_subprocess(cmd: list[str]) -> tuple[int, str, str]:
    result = subprocess.run(cmd, capture_output=True, text=True)
    return result.returncode, result.stdout.strip(), result.stderr.strip()


def adb_prefix(device: str | None) -> list[str]:
    return ["adb", "-s", device] if device else ["adb"]


def adb_forward(device: str | None, port: int) -> None:
    code, out, err = run_subprocess(
        adb_prefix(device) + ["forward", f"tcp:{port}", f"tcp:{port}"]
    )
    if code != 0:
        raise SmokeError(f"adb forward failed: {err or out or 'unknown error'}")
    log(f"[ok] adb forward tcp:{port} -> tcp:{port}")


def adb_forward_remove(device: str | None, port: int) -> None:
    run_subprocess(adb_prefix(device) + ["forward", "--remove", f"tcp:{port}"])


def adb_reverse(device: str | None, port: int) -> None:
    code, out, err = run_subprocess(
        adb_prefix(device) + ["reverse", f"tcp:{port}", f"tcp:{port}"]
    )
    if code != 0:
        raise SmokeError(f"adb reverse failed: {err or out or 'unknown error'}")
    log(f"[ok] adb reverse tcp:{port} -> tcp:{port}")


def adb_reverse_remove(device: str | None, port: int) -> None:
    run_subprocess(adb_prefix(device) + ["reverse", "--remove", f"tcp:{port}"])


def parse_content_provider_output(raw_output: str) -> Any | None:
    lines = raw_output.strip().splitlines()
    for line in lines:
        line = line.strip()
        if "result=" in line:
            json_str = line.split("result=", 1)[1].strip()
            try:
                return json.loads(json_str)
            except json.JSONDecodeError:
                return json_str
        if line.startswith("{") or line.startswith("["):
            try:
                return json.loads(line)
            except json.JSONDecodeError:
                continue

    raw_output = raw_output.strip()
    if not raw_output:
        return None
    try:
        return json.loads(raw_output)
    except json.JSONDecodeError:
        return raw_output


def ensure_provider_insert_success(raw_output: str) -> None:
    lowered = raw_output.lower()
    if "status=error" not in lowered:
        return

    message = "unknown provider error"
    match = re.search(r"status=error&message=([^\s]+)", raw_output)
    if match:
        message = unquote_plus(match.group(1))
    raise SmokeError(message)


def encode_base64_string(value: str) -> str:
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def append_provider_string_bind(
    binds: list[tuple[str, str, Any]],
    key: str,
    value: str | None,
) -> bool:
    if value is None:
        return False
    binds.append((f"{key}_base64", "s", encode_base64_string(value)))
    return True


def fetch_token_from_adb(device: str | None) -> str:
    code, out, err = run_subprocess(
        adb_prefix(device)
        + ["shell", "content", "query", "--uri", f"content://{APP_AUTHORITY}/auth_token"]
    )
    if code != 0:
        raise SmokeError(
            f"failed to fetch auth token via adb: {err or out or 'unknown error'}"
        )

    parsed = parse_content_provider_output(out)
    if isinstance(parsed, dict):
        token = parsed.get("result") or parsed.get("token")
        if isinstance(token, str) and token.strip():
            log("[ok] fetched auth token from content provider")
            return token.strip()
    if isinstance(parsed, str) and parsed.strip():
        log("[ok] fetched auth token from content provider")
        return parsed.strip().strip('"')
    raise SmokeError(f"unable to parse auth token from adb output: {out}")


def provider_insert(device: str | None, uri: str, binds: list[tuple[str, str, Any]]) -> str:
    cmd = adb_prefix(device) + ["shell", "content", "insert", "--uri", uri]
    for key, kind, value in binds:
        if kind == "b":
            rendered = "true" if bool(value) else "false"
        else:
            rendered = str(value)
        cmd += ["--bind", f"{key}:{kind}:{rendered}"]

    code, out, err = run_subprocess(cmd)
    if code != 0:
        raise SmokeError(f"provider insert failed for {uri}: {err or out or 'unknown error'}")
    ensure_provider_insert_success(out)
    return out


def is_reverse_service_running(device: str | None) -> bool:
    code, out, err = run_subprocess(
        adb_prefix(device)
        + ["shell", "dumpsys", "activity", "services", REVERSE_SERVICE_COMPONENT]
    )
    if code != 0:
        log(f"[warn] unable to inspect reverse service state: {err or out or 'unknown error'}")
        return False
    return "ReverseConnectionService" in out


@dataclass
class ReverseConfigSnapshot:
    url: str | None
    token: str | None
    service_was_running: bool
    can_restore_values: bool


def snapshot_reverse_config(device: str | None) -> ReverseConfigSnapshot:
    service_was_running = is_reverse_service_running(device)
    code, out, err = run_subprocess(
        adb_prefix(device)
        + ["shell", "run-as", APP_AUTHORITY, "cat", "shared_prefs/mobilerun_config.xml"]
    )
    if code != 0:
        detail = err or out or "run-as unavailable"
        log(
            "[warn] unable to snapshot reverse URL/token via run-as; "
            f"cleanup will disable reverse but cannot fully restore config ({detail})"
        )
        return ReverseConfigSnapshot(
            url=None,
            token=None,
            service_was_running=service_was_running,
            can_restore_values=False,
        )

    try:
        root = ET.fromstring(out)
    except ET.ParseError as exc:
        log(f"[warn] unable to parse reverse config snapshot: {exc}")
        return ReverseConfigSnapshot(
            url=None,
            token=None,
            service_was_running=service_was_running,
            can_restore_values=False,
        )

    values: dict[str, str] = {}
    for child in root:
        name = child.attrib.get("name")
        if not name or child.tag != "string":
            continue
        values[name] = child.text or ""

    log("[ok] captured previous reverse config for cleanup")
    return ReverseConfigSnapshot(
        url=values.get("reverse_connection_url"),
        token=values.get("reverse_connection_token"),
        service_was_running=service_was_running,
        can_restore_values=True,
    )


def ensure_websocket_server_enabled_via_adb(device: str | None, port: int) -> None:
    provider_insert(
        device,
        f"content://{APP_AUTHORITY}/toggle_websocket_server",
        [("enabled", "b", True), ("port", "i", port)],
    )
    log(f"[ok] requested local WebSocket enable on port {port}")


def configure_reverse_connection(
    device: str | None,
    *,
    url: str | None = None,
    token: str | None = None,
    service_key: str | None = None,
    enabled: bool | None = None,
) -> None:
    binds: list[tuple[str, str, Any]] = []
    base64_keys: list[str] = []
    if append_provider_string_bind(binds, "url", url):
        base64_keys.append("url")
    if append_provider_string_bind(binds, "token", token):
        base64_keys.append("token")
    if append_provider_string_bind(binds, "service_key", service_key):
        base64_keys.append("service_key")
    if enabled is not None:
        binds.append(("enabled", "b", enabled))
    if not binds:
        return

    provider_insert(
        device,
        f"content://{APP_AUTHORITY}/configure_reverse_connection",
        binds,
    )
    rendered: list[str] = []
    if url is not None:
        rendered.append(f"url={url!r}")
    if token is not None:
        rendered.append("token='***'")
    if service_key is not None:
        rendered.append("service_key='***'")
    if enabled is not None:
        rendered.append(f"enabled={enabled}")
    if base64_keys:
        rendered.append(f"strings=base64[{', '.join(base64_keys)}]")
    log(f"[ok] configured reverse connection ({', '.join(rendered)})")


def restore_reverse_config(device: str | None, snapshot: ReverseConfigSnapshot) -> None:
    if snapshot.can_restore_values:
        configure_reverse_connection(
            device,
            url=snapshot.url or "",
            token=snapshot.token or "",
            enabled=snapshot.service_was_running,
        )
        state = "running" if snapshot.service_was_running else "disabled"
        log(f"[ok] restored previous reverse config ({state})")
        return

    configure_reverse_connection(device, enabled=False)
    log("[warn] reverse config values were not restorable; reverse service disabled for cleanup")


def post_notification(device: str | None, tag: str, title: str, text: str) -> None:
    code, out, err = run_subprocess(
        adb_prefix(device)
        + [
            "shell",
            "cmd",
            "notification",
            "post",
            "-t",
            title,
            tag,
            text,
        ]
    )
    if code != 0:
        raise SmokeError(f"failed to post notification: {err or out or 'unknown error'}")
    log("[ok] posted notification via adb shell cmd notification post")


def launch_settings(device: str | None) -> None:
    code, out, err = run_subprocess(
        adb_prefix(device)
        + ["shell", "am", "start", "-a", "android.settings.SETTINGS"]
    )
    if code != 0:
        raise SmokeError(f"failed to launch Settings: {err or out or 'unknown error'}")
    log("[ok] launched Android Settings via adb")


def go_home(device: str | None) -> None:
    run_subprocess(adb_prefix(device) + ["shell", "input", "keyevent", "KEYCODE_HOME"])
    log("[info] sent HOME keyevent")


def truncate_json(value: Any, limit: int = 180) -> str:
    text = json.dumps(value, ensure_ascii=False, default=str)
    if len(text) <= limit:
        return text
    return text[:limit] + "..."


@dataclass
class CheckResult:
    name: str
    ok: bool
    detail: str
    required: bool = True


@dataclass
class EventRecord:
    transport: str
    envelope: str
    event_type: str | None
    timestamp: Any
    payload: Any
    raw: Any
    category: str
    received_at: float
    received_at_ms: int

    def to_jsonl(self) -> str:
        return json.dumps(
            {
                "transport": self.transport,
                "envelope": self.envelope,
                "eventType": self.event_type,
                "timestamp": self.timestamp,
                "payload": self.payload,
                "raw": self.raw,
                "category": self.category,
                "receivedAtMs": self.received_at_ms,
            },
            ensure_ascii=False,
            default=str,
        )


class EventRecorder:
    def __init__(self, transport: str, expect_legacy: bool) -> None:
        self.transport = transport
        self.expect_legacy = expect_legacy
        self.event_queue: asyncio.Queue[EventRecord] = asyncio.Queue()
        self.pong_queue: asyncio.Queue[EventRecord] = asyncio.Queue()
        self.records: list[EventRecord] = []

    async def record_text(self, text: str) -> None:
        received_at = time.monotonic()
        received_at_ms = int(time.time() * 1000)
        try:
            parsed = json.loads(text)
        except json.JSONDecodeError:
            record = EventRecord(
                transport=self.transport,
                envelope="nonjson",
                event_type=None,
                timestamp=None,
                payload=None,
                raw=text,
                category="nonjson",
                received_at=received_at,
                received_at_ms=received_at_ms,
            )
            self.records.append(record)
            log(f"[info][{self.transport}] non-json frame: {text[:200]}")
            return

        if not isinstance(parsed, dict):
            record = EventRecord(
                transport=self.transport,
                envelope="json",
                event_type=None,
                timestamp=None,
                payload=None,
                raw=parsed,
                category="other",
                received_at=received_at,
                received_at_ms=received_at_ms,
            )
            self.records.append(record)
            return

        if parsed.get("type") == "PONG":
            record = EventRecord(
                transport=self.transport,
                envelope="legacy",
                event_type="PONG",
                timestamp=parsed.get("timestamp"),
                payload=parsed.get("payload"),
                raw=parsed,
                category="pong",
                received_at=received_at,
                received_at_ms=received_at_ms,
            )
            self.records.append(record)
            await self.pong_queue.put(record)
            log(f"[ok][{self.transport}] received raw PONG")
            return

        event_record = self._normalize_device_event(parsed, received_at, received_at_ms)
        if event_record is not None:
            self.records.append(event_record)
            await self.event_queue.put(event_record)
            log(
                f"[event][{self.transport}][{event_record.envelope}] "
                f"{event_record.event_type} {truncate_json(event_record.payload)}"
            )
            return

        category = "rpc_response" if parsed.get("id") is not None else "other"
        record = EventRecord(
            transport=self.transport,
            envelope="json",
            event_type=None,
            timestamp=None,
            payload=None,
            raw=parsed,
            category=category,
            received_at=received_at,
            received_at_ms=received_at_ms,
        )
        self.records.append(record)
        if category != "rpc_response":
            log(f"[info][{self.transport}] ignored message: {truncate_json(parsed)}")

    def _normalize_device_event(
        self,
        message: dict[str, Any],
        received_at: float,
        received_at_ms: int,
    ) -> EventRecord | None:
        if self.expect_legacy:
            event_type = message.get("type")
            if not isinstance(event_type, str):
                return None
            return EventRecord(
                transport=self.transport,
                envelope="legacy",
                event_type=event_type,
                timestamp=message.get("timestamp"),
                payload=message.get("payload"),
                raw=message,
                category="device_event",
                received_at=received_at,
                received_at_ms=received_at_ms,
            )

        if message.get("method") != "events/device":
            return None
        params = message.get("params")
        if not isinstance(params, dict):
            return None
        event_type = params.get("type")
        if not isinstance(event_type, str):
            return None
        return EventRecord(
            transport=self.transport,
            envelope="rpc",
            event_type=event_type,
            timestamp=params.get("timestamp"),
            payload=params.get("payload"),
            raw=message,
            category="device_event",
            received_at=received_at,
            received_at_ms=received_at_ms,
        )

    async def wait_for_event(
        self,
        accepted_types: set[str],
        *,
        since: float,
        timeout: float,
    ) -> EventRecord:
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                accepted = ", ".join(sorted(accepted_types))
                raise SmokeError(
                    f"{self.transport}: timed out waiting for event types [{accepted}]"
                )
            record = await asyncio.wait_for(self.event_queue.get(), timeout=remaining)
            if record.received_at < since:
                continue
            if record.event_type in accepted_types:
                return record

    async def wait_for_pong(self, *, since: float, timeout: float) -> EventRecord:
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise SmokeError(f"{self.transport}: timed out waiting for raw PONG")
            record = await asyncio.wait_for(self.pong_queue.get(), timeout=remaining)
            if record.received_at >= since:
                return record


class LocalEventClient:
    def __init__(self, transport: str, uri: str, expect_legacy: bool) -> None:
        self.transport = transport
        self.uri = uri
        self.recorder = EventRecorder(transport, expect_legacy=expect_legacy)
        self.websocket: Any = None
        self.recv_task: asyncio.Task | None = None

    async def start(self) -> None:
        ws = require_websockets()
        self.websocket = await ws.connect(
            self.uri,
            ping_interval=20,
            ping_timeout=20,
            max_size=8 * 1024 * 1024,
        )
        self.recv_task = asyncio.create_task(self._recv_loop())
        log(f"[ok] connected {self.transport}: {self.uri}")

    async def stop(self) -> None:
        if self.recv_task:
            self.recv_task.cancel()
            with contextlib_suppress(asyncio.CancelledError):
                await self.recv_task
            self.recv_task = None
        if self.websocket is not None:
            await self.websocket.close()
            self.websocket = None

    async def send_json(self, payload: dict[str, Any]) -> None:
        if self.websocket is None:
            raise SmokeError(f"{self.transport}: websocket is not connected")
        await self.websocket.send(json.dumps(payload))

    async def _recv_loop(self) -> None:
        assert self.websocket is not None
        async for raw_msg in self.websocket:
            if isinstance(raw_msg, bytes):
                log(f"[info][{self.transport}] ignored binary frame ({len(raw_msg)} bytes)")
                continue
            await self.recorder.record_text(raw_msg)


class ReverseEventServer:
    def __init__(self, bind_host: str, port: int) -> None:
        self.bind_host = bind_host
        self.port = port
        self.recorder = EventRecorder("reverse", expect_legacy=False)
        self.connected_event = asyncio.Event()
        self.server: Any = None

    async def start(self) -> None:
        ws = require_websockets()
        self.server = await ws.serve(self._handler, self.bind_host, self.port)
        log(f"[ok] reverse listener started on ws://{self.bind_host}:{self.port}")

    async def stop(self) -> None:
        if self.server is not None:
            self.server.close()
            await self.server.wait_closed()
            self.server = None

    async def _handler(self, websocket: Any) -> None:
        ws = require_websockets()
        peer = getattr(websocket, "remote_address", None)
        log(f"[ok] reverse device connected from {peer}")
        self.connected_event.set()
        try:
            async for raw_msg in websocket:
                if isinstance(raw_msg, bytes):
                    log(f"[info][reverse] ignored binary frame ({len(raw_msg)} bytes)")
                    continue
                await self.recorder.record_text(raw_msg)
        except ws.exceptions.ConnectionClosed:
            log("[info][reverse] device disconnected")


class contextlib_suppress:
    def __init__(self, *exceptions: type[BaseException]) -> None:
        self.exceptions = exceptions

    def __enter__(self) -> None:
        return None

    def __exit__(self, exc_type, exc, tb) -> bool:
        if exc_type is None:
            return False
        return issubclass(exc_type, self.exceptions)


def build_local_ws_uri(port: int, token: str, event_format: str | None) -> str:
    params: dict[str, str] = {"token": token}
    if event_format:
        params["eventFormat"] = event_format
    return f"ws://localhost:{port}/?{urlencode(params)}"


def resolve_reverse_url(args: argparse.Namespace) -> tuple[str, str, int, bool]:
    if args.reverse_url:
        parsed = urlparse(args.reverse_url)
        if parsed.scheme != "ws":
            raise SmokeError("--reverse-url must use ws:// for this smoke script")
        if not parsed.hostname:
            raise SmokeError("--reverse-url must include a hostname")
        port = parsed.port or args.reverse_port
        bind_host = (
            parsed.hostname if parsed.hostname in {"127.0.0.1", "localhost"} else "0.0.0.0"
        )
        return args.reverse_url, bind_host, port, False

    url = f"ws://127.0.0.1:{args.reverse_port}"
    return url, "127.0.0.1", args.reverse_port, True


def write_jsonl_log(repo_root: Path, recorders: list[EventRecorder]) -> Path:
    log_dir = repo_root / "build" / "event-smoke"
    log_dir.mkdir(parents=True, exist_ok=True)
    log_path = log_dir / f"event_smoke_{time.strftime('%Y%m%d_%H%M%S')}.jsonl"
    records = sorted(
        (record for recorder in recorders for record in recorder.records),
        key=lambda record: record.received_at,
    )
    with log_path.open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(record.to_jsonl())
            handle.write("\n")
    return log_path


async def connect_local_client(
    transport: str,
    uri: str,
    expect_legacy: bool,
    timeout: float,
) -> LocalEventClient:
    deadline = time.monotonic() + timeout
    last_error: Exception | None = None
    while True:
        try:
            client = LocalEventClient(transport, uri, expect_legacy)
            await client.start()
            return client
        except Exception as exc:
            last_error = exc
            if time.monotonic() >= deadline:
                break
            await asyncio.sleep(0.75)
    raise SmokeError(f"failed to connect {transport}: {last_error}")


async def expect_event(
    recorder: EventRecorder,
    transport_label: str,
    event_label: str,
    accepted_types: set[str],
    since: float,
    timeout: float,
) -> CheckResult:
    try:
        record = await recorder.wait_for_event(
            accepted_types,
            since=since,
            timeout=timeout,
        )
        return CheckResult(
            name=f"{transport_label} {event_label}",
            ok=True,
            detail=f"{record.event_type} via {record.envelope}",
        )
    except Exception as exc:
        return CheckResult(
            name=f"{transport_label} {event_label}",
            ok=False,
            detail=str(exc),
        )


def print_summary(results: list[CheckResult], log_path: Path) -> None:
    log("\nSummary")
    for result in results:
        status = "PASS" if result.ok else "FAIL"
        suffix = "" if result.required else " (observational)"
        log(f"- [{status}] {result.name}{suffix}: {result.detail}")
    log(f"- [INFO] Raw log: {log_path}")


async def main_async(args: argparse.Namespace) -> int:
    repo_root = Path(__file__).resolve().parent.parent
    mode = args.mode
    results: list[CheckResult] = []
    recorders: list[EventRecorder] = []
    local_default: LocalEventClient | None = None
    local_rpc: LocalEventClient | None = None
    reverse_server: ReverseEventServer | None = None
    reverse_snapshot: ReverseConfigSnapshot | None = None
    local_forwarded = False
    reverse_mapped = False
    reverse_enabled = False

    try:
        if mode in {"local", "both"}:
            ensure_websocket_server_enabled_via_adb(args.adb_device, args.local_port)
            token = fetch_token_from_adb(args.adb_device)
            adb_forward(args.adb_device, args.local_port)
            local_forwarded = True

            local_default = await connect_local_client(
                "local_default",
                build_local_ws_uri(args.local_port, token, event_format=None),
                expect_legacy=True,
                timeout=args.timeout_sec,
            )
            local_rpc = await connect_local_client(
                "local_rpc",
                build_local_ws_uri(args.local_port, token, event_format="rpc"),
                expect_legacy=False,
                timeout=args.timeout_sec,
            )
            recorders.extend([local_default.recorder, local_rpc.recorder])

            ping_started = time.monotonic()
            await local_default.send_json({"type": "PING", "timestamp": int(time.time() * 1000)})
            await local_default.recorder.wait_for_pong(
                since=ping_started,
                timeout=args.timeout_sec,
            )
            results.append(CheckResult("local_default ping/pong", True, "raw PONG received"))

        if mode in {"reverse", "both"}:
            reverse_snapshot = snapshot_reverse_config(args.adb_device)
            reverse_url, bind_host, reverse_port, use_adb_reverse = resolve_reverse_url(args)
            reverse_server = ReverseEventServer(bind_host=bind_host, port=reverse_port)
            await reverse_server.start()
            recorders.append(reverse_server.recorder)

            if use_adb_reverse:
                adb_reverse(args.adb_device, reverse_port)
                reverse_mapped = True

            configure_reverse_connection(args.adb_device, enabled=False)
            configure_reverse_connection(
                args.adb_device,
                url=reverse_url,
                token=args.reverse_token,
                enabled=True,
            )
            reverse_enabled = True

            try:
                await asyncio.wait_for(
                    reverse_server.connected_event.wait(),
                    timeout=args.timeout_sec,
                )
                results.append(CheckResult("reverse connect", True, reverse_url))
            except asyncio.TimeoutError as exc:
                raise SmokeError(
                    f"reverse device did not connect within {args.timeout_sec:.1f}s"
                ) from exc

        await asyncio.sleep(1.0)

        notification_tag = f"portal_smoke_{int(time.time())}"
        notification_since = time.monotonic()
        post_notification(args.adb_device, notification_tag, "Portal Smoke", "event smoke")

        notification_waits: list[asyncio.Future[CheckResult]] = []
        if local_default is not None:
            notification_waits.append(
                asyncio.create_task(
                    expect_event(
                        local_default.recorder,
                        "local_default",
                        "notification",
                        {"NOTIFICATION", "NOTIFICATION_POSTED"},
                        notification_since,
                        args.timeout_sec,
                    )
                )
            )
        if local_rpc is not None:
            notification_waits.append(
                asyncio.create_task(
                    expect_event(
                        local_rpc.recorder,
                        "local_rpc",
                        "notification",
                        {"NOTIFICATION", "NOTIFICATION_POSTED"},
                        notification_since,
                        args.timeout_sec,
                    )
                )
            )
        if reverse_server is not None:
            notification_waits.append(
                asyncio.create_task(
                    expect_event(
                        reverse_server.recorder,
                        "reverse",
                        "notification",
                        {"NOTIFICATION", "NOTIFICATION_POSTED"},
                        notification_since,
                        args.timeout_sec,
                    )
                )
            )
        results.extend(await asyncio.gather(*notification_waits))

        app_since = time.monotonic()
        launch_settings(args.adb_device)

        app_waits: list[asyncio.Future[CheckResult]] = []
        if local_default is not None:
            app_waits.append(
                asyncio.create_task(
                    expect_event(
                        local_default.recorder,
                        "local_default",
                        "app_entered",
                        {"APP_ENTERED"},
                        app_since,
                        args.timeout_sec,
                    )
                )
            )
        if local_rpc is not None:
            app_waits.append(
                asyncio.create_task(
                    expect_event(
                        local_rpc.recorder,
                        "local_rpc",
                        "app_entered",
                        {"APP_ENTERED"},
                        app_since,
                        args.timeout_sec,
                    )
                )
            )
        if reverse_server is not None:
            app_waits.append(
                asyncio.create_task(
                    expect_event(
                        reverse_server.recorder,
                        "reverse",
                        "app_entered",
                        {"APP_ENTERED"},
                        app_since,
                        args.timeout_sec,
                    )
                )
            )
        results.extend(await asyncio.gather(*app_waits))

        go_home(args.adb_device)

        if args.manual_window_sec > 0:
            log(
                f"[info] manual observation window: {args.manual_window_sec:.1f}s "
                "(unlock / network / other manual events are observational only)"
            )
            await asyncio.sleep(args.manual_window_sec)
            results.append(
                CheckResult(
                    "manual observation window",
                    True,
                    f"listened for {args.manual_window_sec:.1f}s",
                    required=False,
                )
            )

        return_code = 0 if all(result.ok or not result.required for result in results) else 1
        log_path = write_jsonl_log(repo_root, recorders)
        print_summary(results, log_path)
        return return_code

    finally:
        if local_default is not None:
            await local_default.stop()
        if local_rpc is not None:
            await local_rpc.stop()
        if reverse_server is not None:
            await reverse_server.stop()

        if reverse_snapshot is not None:
            try:
                restore_reverse_config(args.adb_device, reverse_snapshot)
            except Exception as exc:
                log(f"[warn] failed to restore reverse connection during cleanup: {exc}")
        elif reverse_enabled:
            try:
                configure_reverse_connection(args.adb_device, enabled=False)
            except Exception as exc:
                log(f"[warn] failed to disable reverse connection during cleanup: {exc}")

        if reverse_mapped:
            adb_reverse_remove(args.adb_device, args.reverse_port)
        if local_forwarded:
            adb_forward_remove(args.adb_device, args.local_port)


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Automated Portal event smoke test.")
    parser.add_argument("--adb-device", default=None, help="adb serial for -s")
    parser.add_argument(
        "--mode",
        choices=("local", "reverse", "both"),
        default="both",
        help="which transport paths to verify",
    )
    parser.add_argument(
        "--local-port",
        type=int,
        default=DEFAULT_LOCAL_PORT,
        help=f"local WebSocket port (default: {DEFAULT_LOCAL_PORT})",
    )
    parser.add_argument(
        "--reverse-port",
        type=int,
        default=DEFAULT_REVERSE_PORT,
        help=f"reverse listener port (default: {DEFAULT_REVERSE_PORT})",
    )
    parser.add_argument(
        "--reverse-url",
        default=None,
        help="optional ws:// override for reverse connection; default uses adb reverse and ws://127.0.0.1:<port>",
    )
    parser.add_argument(
        "--reverse-token",
        default="",
        help="optional reverse connection token; blank clears any existing token for the smoke run",
    )
    parser.add_argument(
        "--timeout-sec",
        type=float,
        default=DEFAULT_TIMEOUT_SEC,
        help=f"timeout per required wait (default: {DEFAULT_TIMEOUT_SEC})",
    )
    parser.add_argument(
        "--manual-window-sec",
        type=float,
        default=0.0,
        help="optional extra passive observation window after automated checks",
    )
    return parser


def main() -> int:
    parser = build_arg_parser()
    args = parser.parse_args()

    try:
        return asyncio.run(main_async(args))
    except KeyboardInterrupt:
        log("\nInterrupted.")
        return 130
    except SmokeError as exc:
        log(f"[fail] {exc}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
