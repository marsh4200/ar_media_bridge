"""AR Media Bridge - shows what an Android / Google TV box is playing."""

from __future__ import annotations

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_HOST, CONF_PORT, Platform
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .client import BridgeClient
from .const import CONF_TOKEN

PLATFORMS = [Platform.MEDIA_PLAYER]

type BridgeConfigEntry = ConfigEntry[BridgeClient]


async def async_setup_entry(hass: HomeAssistant, entry: BridgeConfigEntry) -> bool:
    """Start the live connection to the box."""
    client = BridgeClient(
        async_get_clientsession(hass),
        entry.data[CONF_HOST],
        entry.data[CONF_PORT],
        entry.data[CONF_TOKEN],
    )

    @callback
    def _auth_failed() -> None:
        entry.async_start_reauth(hass)

    client.on_auth_failed = _auth_failed
    entry.runtime_data = client

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)

    entry.async_create_background_task(
        hass, client.run(), f"ar_media_bridge_{entry.entry_id}"
    )
    return True


async def async_unload_entry(hass: HomeAssistant, entry: BridgeConfigEntry) -> bool:
    """Disconnect from the box."""
    await entry.runtime_data.stop()
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
