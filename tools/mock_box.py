"""Fake AR Media Bridge box for developing the HA side without the TV.

    pip install aiohttp
    python tools/mock_box.py            # listens on 0.0.0.0:8765, code 123456

Add it in HA manually (Settings > Devices > Add > AR Media Bridge) with this
PC's IP. It cycles Netflix playing -> paused -> YouTube -> home.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import secrets
import time

from aiohttp import WSMsgType, web

DEVICE = {
    "id": "mockbox0001",
    "name": "Mock Google TV",
    "manufacturer": "Acer",
    "model": "Mock",
    "android": "12",
    "version": "0.1.0",
}
APPS = [
    {"package": "com.netflix.ninja", "name": "Netflix"},
    {"package": "com.google.android.youtube.tv", "name": "YouTube"},
]
SCENES = [
    dict(state="playing", app_package="com.netflix.ninja", app_name="Netflix",
         title="Stranger Things", artist="S4:E1", duration_ms=4_800_000),
    dict(state="paused", app_package="com.netflix.ninja", app_name="Netflix",
         title="Stranger Things", artist="S4:E1", duration_ms=4_800_000),
    dict(state="playing", app_package="com.google.android.youtube.tv", app_name="YouTube",
         title="Lofi beats", artist="Some channel", duration_ms=600_000),
    dict(state="idle", app_package="com.google.android.apps.tv.launcherx", app_name="Home",
         is_home=True),
]


class MockBox:
    def __init__(self, code: str, scene_seconds: float) -> None:
        self.code = code
        self.tokens: set[str] = set()
        self.authed: set[web.WebSocketResponse] = set()
        self.scene = 0
        self.scene_seconds = scene_seconds
        self.volume = 7

    def state(self) -> dict:
        s = {"type": "state", "media_state": "idle", "volume": self.volume,
             "volume_max": 15, "muted": False, "volume_fixed": False,
             "foreground_package": None, "media_package": None, "is_home": False}
        s.update(SCENES[self.scene])
        s["foreground_package"] = s.get("app_package")
        if s["state"] != "idle":
            s["position_ms"] = 60_000
            s["position_updated_at"] = int(time.time() * 1000)
        return s

    async def broadcast(self) -> None:
        for ws in list(self.authed):
            if not ws.closed:
                await ws.send_json(self.state())

    async def cycle(self) -> None:
        while True:
            await asyncio.sleep(self.scene_seconds)
            self.scene = (self.scene + 1) % len(SCENES)
            print("scene ->", SCENES[self.scene]["app_name"], SCENES[self.scene]["state"])
            await self.broadcast()

    async def handler(self, request: web.Request) -> web.WebSocketResponse:
        ws = web.WebSocketResponse(heartbeat=30)
        await ws.prepare(request)
        await ws.send_json({"type": "hello", "protocol": 1, "device": DEVICE})
        async for msg in ws:
            if msg.type != WSMsgType.TEXT:
                continue
            m = json.loads(msg.data)
            t = m.get("type")
            if t == "request_pin":
                print(f"*** TV would show pairing code {self.code} ***")
                await ws.send_json({"type": "pin_shown"})
            elif t == "pair":
                if m.get("code") == self.code:
                    tok = secrets.token_hex(24)
                    self.tokens.add(tok)
                    self.authed.add(ws)
                    await ws.send_json({"type": "paired", "token": tok, "device": DEVICE})
                    await self.initial(ws)
                else:
                    await ws.send_json({"type": "error", "error": "invalid_code"})
            elif t == "auth":
                if m.get("token") in self.tokens:
                    self.authed.add(ws)
                    await ws.send_json({"type": "auth_ok", "device": DEVICE})
                    await self.initial(ws)
                else:
                    await ws.send_json({"type": "auth_failed"})
            elif ws not in self.authed:
                await ws.send_json({"type": "error", "error": "not_authenticated"})
            elif t == "command":
                print("command:", m)
                cmd = m.get("command")
                if cmd in ("pause", "play", "play_pause") and SCENES[self.scene]["state"] != "idle":
                    self.scene = 1 if self.scene == 0 else 0
                elif cmd == "volume_up":
                    self.volume = min(15, self.volume + 1)
                elif cmd == "volume_down":
                    self.volume = max(0, self.volume - 1)
                await ws.send_json({"type": "result", "id": m.get("id"), "success": True})
                await self.broadcast()
            elif t == "get_state":
                await self.initial(ws)
        self.authed.discard(ws)
        return ws

    async def initial(self, ws: web.WebSocketResponse) -> None:
        await ws.send_json({"type": "apps", "apps": APPS})
        await ws.send_json(self.state())


async def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--code", default="123456")
    p.add_argument("--scene-seconds", type=float, default=15)
    p.add_argument("--token", action="append", default=[], help="pre-trusted token")
    a = p.parse_args()
    box = MockBox(a.code, a.scene_seconds)
    box.tokens.update(a.token)
    app = web.Application()
    app.router.add_get("/", box.handler)
    runner = web.AppRunner(app)
    await runner.setup()
    await web.TCPSite(runner, "0.0.0.0", a.port).start()
    print(f"Mock box on :{a.port}, pairing code {a.code}")
    await box.cycle()


if __name__ == "__main__":
    asyncio.run(main())
