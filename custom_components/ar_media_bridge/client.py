"""WebSocket client for the AR Media Bridge Android app.

Kept free of Home Assistant imports so it can be tested on its own
(see tools/mock_box.py).
"""

from __future__ import annotations

import asyncio
import base64
import itertools
import logging
from collections.abc import Callable
from typing import Any

import aiohttp

_LOGGER = logging.getLogger(__name__)

CONNECT_TIMEOUT = 10
REPLY_TIMEOUT = 10


class BridgeError(Exception):
    """Base error."""


class CannotConnect(BridgeError):
    """Box not reachable."""


class InvalidCode(BridgeError):
    """Wrong pairing code."""


class PairingLocked(BridgeError):
    """Too many wrong codes, box locked pairing for a minute."""


class AuthFailed(BridgeError):
    """Stored token rejected (pairing was reset on the box)."""


class BridgeClient:
    """Keeps a live connection to one box and mirrors its state."""

    def __init__(
        self,
        session: aiohttp.ClientSession,
        host: str,
        port: int,
        token: str | None = None,
    ) -> None:
        self._session = session
        self.host = host
        self.port = port
        self.token = token

        self.connected = False
        self.device: dict[str, Any] = {}
        self.state: dict[str, Any] = {}
        self.apps: list[dict[str, str]] = []
        self.artwork: bytes | None = None
        self.artwork_hash: str | None = None
        self.artwork_type = "image/jpeg"

        self.on_auth_failed: Callable[[], None] | None = None

        self._ws: aiohttp.ClientWebSocketResponse | None = None
        self._listeners: list[Callable[[], None]] = []
        self._closing = False
        self._ids = itertools.count(1)
        self._pending: dict[int, asyncio.Future] = {}

    @property
    def url(self) -> str:
        return f"ws://{self.host}:{self.port}/"

    # ------------------------------------------------------------ one-shot calls

    async def _open(self) -> tuple[aiohttp.ClientWebSocketResponse, dict]:
        try:
            async with asyncio.timeout(CONNECT_TIMEOUT):
                ws = await self._session.ws_connect(self.url, heartbeat=20)
                hello = await ws.receive_json()
        except (aiohttp.ClientError, OSError, TimeoutError, ValueError, TypeError) as err:
            raise CannotConnect(str(err)) from err
        if hello.get("type") != "hello":
            await ws.close()
            raise CannotConnect("unexpected greeting")
        return ws, hello

    async def _wait_for(self, ws, types: set[str]) -> dict:
        try:
            async with asyncio.timeout(REPLY_TIMEOUT):
                while True:
                    msg = await ws.receive_json()
                    if msg.get("type") in types:
                        return msg
        except (TimeoutError, TypeError, ValueError, aiohttp.ClientError) as err:
            # TypeError: socket closed (receive_json got a close frame)
            raise CannotConnect(f"no reply: {err!r}") from err

    async def fetch_hello(self) -> dict:
        """Connect, read the greeting and disconnect. Returns device info."""
        ws, hello = await self._open()
        await ws.close()
        return hello.get("device", {})

    async def request_pin(self) -> None:
        """Ask the box to show its pairing code on screen."""
        ws, _ = await self._open()
        try:
            await ws.send_json({"type": "request_pin"})
            await self._wait_for(ws, {"pin_shown"})
        except (TimeoutError, aiohttp.ClientError):
            pass
        finally:
            await ws.close()

    async def pair(self, code: str, client_name: str = "Home Assistant") -> tuple[str, dict]:
        """Pair with the box. Returns (token, device info)."""
        ws, hello = await self._open()
        try:
            await ws.send_json({"type": "pair", "code": code.strip(), "client_name": client_name})
            reply = await self._wait_for(ws, {"paired", "error"})
        except (TimeoutError, aiohttp.ClientError, ValueError) as err:
            raise CannotConnect(str(err)) from err
        finally:
            await ws.close()
        if reply["type"] == "paired":
            self.token = reply["token"]
            return reply["token"], reply.get("device") or hello.get("device", {})
        if reply.get("error") == "locked":
            raise PairingLocked
        raise InvalidCode

    # ------------------------------------------------------------ live link

    def add_listener(self, cb: Callable[[], None]) -> Callable[[], None]:
        self._listeners.append(cb)

        def remove() -> None:
            if cb in self._listeners:
                self._listeners.remove(cb)

        return remove

    def _notify(self) -> None:
        for cb in list(self._listeners):
            try:
                cb()
            except Exception:  # noqa: BLE001
                _LOGGER.exception("Listener error")

    async def run(self) -> None:
        """Connect and stay connected until stop() is called."""
        backoff = 2
        while not self._closing:
            try:
                await self._session_once()
                backoff = 2
            except AuthFailed:
                _LOGGER.warning("Box %s rejected our token - pairing was reset", self.host)
                self._set_connected(False)
                if self.on_auth_failed:
                    self.on_auth_failed()
                return
            except CannotConnect as err:
                _LOGGER.debug("Box %s unreachable: %s", self.host, err)
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001
                _LOGGER.exception("Unexpected error talking to %s", self.host)
            self._set_connected(False)
            if self._closing:
                break
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 60)

    async def _session_once(self) -> None:
        ws, hello = await self._open()
        self._ws = ws
        try:
            self.device = hello.get("device", {})
            await ws.send_json({"type": "auth", "token": self.token})
            reply = await self._wait_for(ws, {"auth_ok", "auth_failed"})
            if reply["type"] == "auth_failed":
                raise AuthFailed
            self.device = reply.get("device") or self.device
            self._set_connected(True)
            _LOGGER.debug("Connected to box %s", self.host)

            async for msg in ws:
                if msg.type == aiohttp.WSMsgType.TEXT:
                    try:
                        self._handle(msg.json())
                    except ValueError:
                        _LOGGER.debug("Bad JSON from box: %s", msg.data)
                elif msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                    break
        finally:
            self._ws = None
            for fut in self._pending.values():
                if not fut.done():
                    fut.set_exception(CannotConnect("disconnected"))
            self._pending.clear()
            if not ws.closed:
                await ws.close()

    def _handle(self, msg: dict) -> None:
        kind = msg.get("type")
        if kind == "state":
            self.state = msg
            self._notify()
        elif kind == "artwork":
            data = msg.get("data")
            self.artwork_hash = msg.get("hash")
            self.artwork = base64.b64decode(data) if data else None
            if self.artwork and self.artwork[:4] == b"\x89PNG":
                self.artwork_type = "image/png"
            else:
                self.artwork_type = "image/jpeg"
            self._notify()
        elif kind == "apps":
            self.apps = msg.get("apps", [])
            self._notify()
        elif kind == "result":
            fut = self._pending.pop(msg.get("id"), None)
            if fut and not fut.done():
                fut.set_result(msg)
        elif kind == "error":
            _LOGGER.debug("Box error: %s", msg.get("error"))

    def _set_connected(self, value: bool) -> None:
        if self.connected != value:
            self.connected = value
            self._notify()

    async def command(self, command: str, **params: Any) -> dict:
        """Send a command and wait for the box to confirm."""
        ws = self._ws
        if ws is None or ws.closed or not self.connected:
            raise CannotConnect("not connected")
        msg_id = next(self._ids)
        fut: asyncio.Future = asyncio.get_running_loop().create_future()
        self._pending[msg_id] = fut
        await ws.send_json({"type": "command", "id": msg_id, "command": command, **params})
        try:
            async with asyncio.timeout(REPLY_TIMEOUT):
                result = await fut
        finally:
            self._pending.pop(msg_id, None)
        if not result.get("success"):
            raise BridgeError(result.get("error", "failed"))
        return result

    async def stop(self) -> None:
        self._closing = True
        if self._ws is not None and not self._ws.closed:
            await self._ws.close()
