"""Run lights, scenes, scripts or switches when the box plays, pauses or stops.

Set up from the integration's Configure screen, so no YAML automation is needed.
"""

from __future__ import annotations

import logging
from typing import Any

from homeassistant.core import CALLBACK_TYPE, HomeAssistant, callback
from homeassistant.helpers.event import async_call_later

from .client import BridgeClient
from .const import (
    CONF_DARK_ONLY,
    CONF_DELAY,
    CONF_IDLE_BRIGHTNESS,
    CONF_IDLE_TARGETS,
    CONF_PAUSED_BRIGHTNESS,
    CONF_PAUSED_TARGETS,
    CONF_PLAYING_BRIGHTNESS,
    CONF_PLAYING_TARGETS,
    DEFAULT_DELAY,
    DEFAULT_IDLE_BRIGHTNESS,
    DEFAULT_PAUSED_BRIGHTNESS,
    DEFAULT_PLAYING_BRIGHTNESS,
)

_LOGGER = logging.getLogger(__name__)

# box state -> (targets option, brightness option, default brightness)
STATE_OPTIONS = {
    "playing": (CONF_PLAYING_TARGETS, CONF_PLAYING_BRIGHTNESS, DEFAULT_PLAYING_BRIGHTNESS),
    "paused": (CONF_PAUSED_TARGETS, CONF_PAUSED_BRIGHTNESS, DEFAULT_PAUSED_BRIGHTNESS),
    "idle": (CONF_IDLE_TARGETS, CONF_IDLE_BRIGHTNESS, DEFAULT_IDLE_BRIGHTNESS),
}


class PlaybackReactions:
    """Watches the box and fires the chosen targets on play / pause / stop."""

    def __init__(self, hass: HomeAssistant, client: BridgeClient, options: dict[str, Any]) -> None:
        self._hass = hass
        self._client = client
        self._options = options
        self._last_state: str | None = None   # last state we acted on (or saw at startup)
        self._pending_state: str | None = None
        self._cancel_timer: CALLBACK_TYPE | None = None
        self._remove_listener: CALLBACK_TYPE | None = None

    @property
    def enabled(self) -> bool:
        return any(self._options.get(opt[0]) for opt in STATE_OPTIONS.values())

    @callback
    def start(self) -> None:
        if self.enabled:
            self._remove_listener = self._client.add_listener(self._on_update)

    @callback
    def stop(self) -> None:
        self._cancel()
        if self._remove_listener:
            self._remove_listener()
            self._remove_listener = None

    @callback
    def _cancel(self) -> None:
        if self._cancel_timer:
            self._cancel_timer()
            self._cancel_timer = None
        self._pending_state = None

    @callback
    def _on_update(self) -> None:
        if not self._client.connected:
            self._cancel()
            return

        state = self._client.state.get("state")
        if state not in STATE_OPTIONS:
            # buffering / unknown: leave the lights as they are
            return

        if self._last_state is None:
            # First state after HA starts (or the options are saved): remember
            # it but don't flick the lights just because HA restarted.
            self._last_state = state
            return

        if state == self._last_state:
            self._cancel()  # bounced back (e.g. seek) before the delay ran out
            return
        if state == self._pending_state:
            return  # already counting down for this state

        self._cancel()
        self._pending_state = state
        delay = float(self._options.get(CONF_DELAY, DEFAULT_DELAY))
        self._cancel_timer = async_call_later(self._hass, delay, self._fire)

    @callback
    def _fire(self, _now: Any) -> None:
        state = self._pending_state
        self._cancel_timer = None
        self._pending_state = None
        if state is None or self._client.state.get("state") != state:
            return
        self._last_state = state
        if self._options.get(CONF_DARK_ONLY) and not self._is_dark():
            _LOGGER.debug("Box is %s but it's daytime, skipping", state)
            return
        self._hass.async_create_task(self._run(state))

    def _is_dark(self) -> bool:
        sun = self._hass.states.get("sun.sun")
        return sun is None or sun.state == "below_horizon"

    async def _run(self, state: str) -> None:
        targets_key, brightness_key, default_brightness = STATE_OPTIONS[state]
        targets: list[str] = self._options.get(targets_key) or []
        brightness = int(self._options.get(brightness_key, default_brightness))

        by_domain: dict[str, list[str]] = {}
        for entity_id in targets:
            by_domain.setdefault(entity_id.split(".", 1)[0], []).append(entity_id)

        for domain, entity_ids in by_domain.items():
            if domain == "light":
                if brightness <= 0:
                    service, data = "turn_off", {"transition": 1}
                else:
                    service, data = "turn_on", {"brightness_pct": brightness, "transition": 1}
            elif domain in ("scene", "script", "switch", "input_boolean"):
                service, data = "turn_on", {}
            else:
                continue
            try:
                await self._hass.services.async_call(
                    domain, service, {"entity_id": entity_ids, **data}, blocking=False
                )
            except Exception:  # noqa: BLE001 - a missing entity shouldn't break the bridge
                _LOGGER.warning("Couldn't run %s.%s for %s", domain, service, entity_ids, exc_info=True)
