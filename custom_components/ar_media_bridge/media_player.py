"""Media player entity for the box."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from homeassistant.components.media_player import (
    MediaPlayerDeviceClass,
    MediaPlayerEntity,
    MediaPlayerEntityFeature,
    MediaPlayerState,
    MediaType,
)
from homeassistant.core import HomeAssistant, callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.util import dt as dt_util

from . import BridgeConfigEntry
from .client import BridgeClient, BridgeError
from .const import CONF_DEVICE_ID, CONF_MANUFACTURER, CONF_MODEL, DOMAIN

STATE_MAP = {
    "playing": MediaPlayerState.PLAYING,
    "paused": MediaPlayerState.PAUSED,
    "buffering": MediaPlayerState.BUFFERING,
    "idle": MediaPlayerState.IDLE,
}

BASE_FEATURES = (
    MediaPlayerEntityFeature.PLAY
    | MediaPlayerEntityFeature.PAUSE
    | MediaPlayerEntityFeature.STOP
    | MediaPlayerEntityFeature.NEXT_TRACK
    | MediaPlayerEntityFeature.PREVIOUS_TRACK
    | MediaPlayerEntityFeature.SEEK
    | MediaPlayerEntityFeature.SELECT_SOURCE
)
VOLUME_FEATURES = (
    MediaPlayerEntityFeature.VOLUME_STEP
    | MediaPlayerEntityFeature.VOLUME_SET
    | MediaPlayerEntityFeature.VOLUME_MUTE
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: BridgeConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    async_add_entities([BridgeMediaPlayer(entry)])


class BridgeMediaPlayer(MediaPlayerEntity):
    """What the box is doing right now."""

    _attr_has_entity_name = True
    _attr_name = None
    _attr_should_poll = False
    _attr_device_class = MediaPlayerDeviceClass.TV

    def __init__(self, entry: BridgeConfigEntry) -> None:
        self._client: BridgeClient = entry.runtime_data
        device_id = entry.data.get(CONF_DEVICE_ID) or entry.entry_id
        self._attr_unique_id = device_id
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, device_id)},
            name=entry.title,
            manufacturer=entry.data.get(CONF_MANUFACTURER) or "Android TV",
            model=entry.data.get(CONF_MODEL),
        )

    async def async_added_to_hass(self) -> None:
        self.async_on_remove(self._client.add_listener(self._handle_update))

    @callback
    def _handle_update(self) -> None:
        self.async_write_ha_state()

    # ------------------------------------------------------------ state

    @property
    def _s(self) -> dict[str, Any]:
        return self._client.state

    @property
    def available(self) -> bool:
        return self._client.connected

    @property
    def state(self) -> MediaPlayerState | None:
        return STATE_MAP.get(self._s.get("state"), MediaPlayerState.ON)

    @property
    def supported_features(self) -> MediaPlayerEntityFeature:
        if self._s.get("volume_fixed"):
            return BASE_FEATURES
        return BASE_FEATURES | VOLUME_FEATURES

    @property
    def app_id(self) -> str | None:
        return self._s.get("app_package")

    @property
    def app_name(self) -> str | None:
        return self._s.get("app_name")

    @property
    def source(self) -> str | None:
        return self._s.get("app_name")

    @property
    def source_list(self) -> list[str]:
        return [a["name"] for a in self._client.apps]

    @property
    def media_content_type(self) -> MediaType | None:
        if not self._s.get("title"):
            return MediaType.APP if self.app_id else None
        return MediaType.MUSIC if self._s.get("album") else MediaType.VIDEO

    @property
    def media_title(self) -> str | None:
        return self._s.get("title") or (
            self._s.get("app_name") if self._s.get("state") != "idle" else None
        )

    @property
    def media_artist(self) -> str | None:
        return self._s.get("artist")

    @property
    def media_album_name(self) -> str | None:
        return self._s.get("album")

    @property
    def media_duration(self) -> int | None:
        ms = self._s.get("duration_ms")
        return int(ms / 1000) if ms else None

    @property
    def media_position(self) -> int | None:
        ms = self._s.get("position_ms")
        return int(ms / 1000) if ms is not None else None

    @property
    def media_position_updated_at(self) -> datetime | None:
        ts = self._s.get("position_updated_at")
        return dt_util.utc_from_timestamp(ts / 1000) if ts else None

    @property
    def volume_level(self) -> float | None:
        vmax = self._s.get("volume_max")
        vol = self._s.get("volume")
        if not vmax or vol is None:
            return None
        return vol / vmax

    @property
    def is_volume_muted(self) -> bool | None:
        return self._s.get("muted")

    @property
    def media_image_hash(self) -> str | None:
        return self._client.artwork_hash if self._client.artwork else None

    async def async_get_media_image(self) -> tuple[bytes | None, str | None]:
        return self._client.artwork, self._client.artwork_type

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        return {
            "foreground_app": self._s.get("foreground_package"),
            "media_app": self._s.get("media_package"),
            "media_state": self._s.get("media_state"),
            "is_home_screen": self._s.get("is_home"),
        }

    # ------------------------------------------------------------ commands

    async def _cmd(self, command: str, **params: Any) -> None:
        try:
            await self._client.command(command, **params)
        except BridgeError as err:
            raise HomeAssistantError(f"Box refused {command}: {err}") from err

    async def async_media_play(self) -> None:
        await self._cmd("play")

    async def async_media_pause(self) -> None:
        await self._cmd("pause")

    async def async_media_play_pause(self) -> None:
        await self._cmd("play_pause")

    async def async_media_stop(self) -> None:
        await self._cmd("stop")

    async def async_media_next_track(self) -> None:
        await self._cmd("next")

    async def async_media_previous_track(self) -> None:
        await self._cmd("previous")

    async def async_media_seek(self, position: float) -> None:
        await self._cmd("seek", position_ms=int(position * 1000))

    async def async_volume_up(self) -> None:
        await self._cmd("volume_up")

    async def async_volume_down(self) -> None:
        await self._cmd("volume_down")

    async def async_set_volume_level(self, volume: float) -> None:
        await self._cmd("volume_set", level=volume)

    async def async_mute_volume(self, mute: bool) -> None:
        await self._cmd("mute", mute=mute)

    async def async_select_source(self, source: str) -> None:
        for app in self._client.apps:
            if app["name"] == source or app["package"] == source:
                await self._cmd("launch", package=app["package"])
                return
        raise HomeAssistantError(f"App '{source}' not found on the box")
