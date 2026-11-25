"""Config flow: discover the box, show a code on the TV, pair."""

from __future__ import annotations

import logging
from collections.abc import Mapping
from typing import Any

import voluptuous as vol

from homeassistant.config_entries import (
    ConfigEntry,
    ConfigFlow,
    ConfigFlowResult,
    OptionsFlow,
)
from homeassistant.const import CONF_HOST, CONF_NAME, CONF_PORT
from homeassistant.core import callback
from homeassistant.helpers import selector
from homeassistant.helpers.aiohttp_client import async_get_clientsession

try:  # HA 2025.1+
    from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo
except ImportError:  # pragma: no cover - older HA
    from homeassistant.components.zeroconf import ZeroconfServiceInfo

from .client import BridgeClient, CannotConnect, InvalidCode, PairingLocked
from .const import (
    CONF_CODE,
    CONF_DARK_ONLY,
    CONF_DELAY,
    CONF_DEVICE_ID,
    CONF_IDLE_BRIGHTNESS,
    CONF_IDLE_TARGETS,
    CONF_MANUFACTURER,
    CONF_MODEL,
    CONF_PAUSED_BRIGHTNESS,
    CONF_PAUSED_TARGETS,
    CONF_PLAYING_BRIGHTNESS,
    CONF_PLAYING_TARGETS,
    CONF_TOKEN,
    DEFAULT_DELAY,
    DEFAULT_IDLE_BRIGHTNESS,
    DEFAULT_PAUSED_BRIGHTNESS,
    DEFAULT_PLAYING_BRIGHTNESS,
    DEFAULT_PORT,
    DOMAIN,
)

_LOGGER = logging.getLogger(__name__)


class ARMediaBridgeConfigFlow(ConfigFlow, domain=DOMAIN):
    """Handle a config flow for AR Media Bridge."""

    VERSION = 1

    @staticmethod
    @callback
    def async_get_options_flow(config_entry: ConfigEntry) -> OptionsFlow:
        """The Configure button: lights / scenes for play, pause and stop."""
        return ARMediaBridgeOptionsFlow()

    def __init__(self) -> None:
        self._host: str | None = None
        self._port: int = DEFAULT_PORT
        self._name: str = "Media box"
        self._device: dict[str, Any] = {}
        self._pin_requested = False

    def _client(self) -> BridgeClient:
        return BridgeClient(async_get_clientsession(self.hass), self._host, self._port)

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Manual setup by IP address."""
        errors: dict[str, str] = {}
        if user_input is not None:
            self._host = user_input[CONF_HOST].strip()
            self._port = user_input[CONF_PORT]
            try:
                self._device = await self._client().fetch_hello()
            except CannotConnect:
                errors["base"] = "cannot_connect"
            else:
                device_id = self._device.get("id")
                if not device_id:
                    return self.async_abort(reason="no_device_id")
                await self.async_set_unique_id(device_id)
                self._abort_if_unique_id_configured(
                    updates={CONF_HOST: self._host, CONF_PORT: self._port}
                )
                self._name = self._device.get("name") or self._name
                return await self.async_step_pair()

        return self.async_show_form(
            step_id="user",
            data_schema=vol.Schema(
                {
                    vol.Required(CONF_HOST, default=self._host or ""): str,
                    vol.Required(CONF_PORT, default=self._port): int,
                }
            ),
            errors=errors,
        )

    async def async_step_zeroconf(self, discovery_info: ZeroconfServiceInfo) -> ConfigFlowResult:
        """Box found on the network."""
        device_id = discovery_info.properties.get("id")
        if not device_id:
            return self.async_abort(reason="no_device_id")
        self._host = str(discovery_info.host)
        self._port = discovery_info.port or DEFAULT_PORT
        await self.async_set_unique_id(device_id)
        self._abort_if_unique_id_configured(
            updates={CONF_HOST: self._host, CONF_PORT: self._port}
        )
        self._name = discovery_info.name.split("._", 1)[0] or self._name
        self._device = {
            "id": device_id,
            "name": self._name,
            "model": discovery_info.properties.get("model"),
            "manufacturer": discovery_info.properties.get("manufacturer"),
        }
        self.context["title_placeholders"] = {CONF_NAME: self._name}
        return await self.async_step_pair()

    async def async_step_pair(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Show the code on the TV and ask for it."""
        errors: dict[str, str] = {}

        if user_input is not None:
            try:
                token, device = await self._client().pair(user_input[CONF_CODE])
            except InvalidCode:
                errors[CONF_CODE] = "invalid_code"
            except PairingLocked:
                errors["base"] = "locked"
            except CannotConnect:
                errors["base"] = "cannot_connect"
            else:
                self._device.update({k: v for k, v in device.items() if v})
                data = {
                    CONF_HOST: self._host,
                    CONF_PORT: self._port,
                    CONF_TOKEN: token,
                    CONF_DEVICE_ID: self._device.get("id"),
                    CONF_MODEL: self._device.get("model"),
                    CONF_MANUFACTURER: self._device.get("manufacturer"),
                }
                if self.source == "reauth":
                    return self.async_update_reload_and_abort(
                        self._get_reauth_entry(), data_updates=data
                    )
                return self.async_create_entry(
                    title=self._device.get("name") or self._name, data=data
                )

        if not self._pin_requested:
            self._pin_requested = True
            try:
                await self._client().request_pin()
            except CannotConnect:
                _LOGGER.debug("Could not ask %s to show its code", self._host)

        return self.async_show_form(
            step_id="pair",
            data_schema=vol.Schema({vol.Required(CONF_CODE): str}),
            errors=errors,
            description_placeholders={CONF_NAME: self._name},
        )

    async def async_step_reauth(self, entry_data: Mapping[str, Any]) -> ConfigFlowResult:
        """Token rejected - pairing was reset on the box."""
        entry = self._get_reauth_entry()
        self._host = entry_data[CONF_HOST]
        self._port = entry_data[CONF_PORT]
        self._name = entry.title
        self._device = {
            "id": entry_data.get(CONF_DEVICE_ID),
            "model": entry_data.get(CONF_MODEL),
            "manufacturer": entry_data.get(CONF_MANUFACTURER),
        }
        return await self.async_step_pair()


TARGETS = selector.EntitySelector(
    selector.EntitySelectorConfig(
        domain=["light", "scene", "script", "switch", "input_boolean"],
        multiple=True,
    )
)
BRIGHTNESS = selector.NumberSelector(
    selector.NumberSelectorConfig(
        min=0, max=100, step=5, unit_of_measurement="%", mode=selector.NumberSelectorMode.SLIDER
    )
)
DELAY = selector.NumberSelector(
    selector.NumberSelectorConfig(
        min=0, max=30, step=1, unit_of_measurement="s", mode=selector.NumberSelectorMode.BOX
    )
)

OPTIONS_SCHEMA = vol.Schema(
    {
        vol.Optional(CONF_PLAYING_TARGETS): TARGETS,
        vol.Optional(CONF_PLAYING_BRIGHTNESS, default=DEFAULT_PLAYING_BRIGHTNESS): BRIGHTNESS,
        vol.Optional(CONF_PAUSED_TARGETS): TARGETS,
        vol.Optional(CONF_PAUSED_BRIGHTNESS, default=DEFAULT_PAUSED_BRIGHTNESS): BRIGHTNESS,
        vol.Optional(CONF_IDLE_TARGETS): TARGETS,
        vol.Optional(CONF_IDLE_BRIGHTNESS, default=DEFAULT_IDLE_BRIGHTNESS): BRIGHTNESS,
        vol.Optional(CONF_DELAY, default=DEFAULT_DELAY): DELAY,
        vol.Optional(CONF_DARK_ONLY, default=False): selector.BooleanSelector(),
    }
)


class ARMediaBridgeOptionsFlow(OptionsFlow):
    """Pick what runs when the box plays, pauses or stops."""

    async def async_step_init(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        if user_input is not None:
            return self.async_create_entry(data=user_input)

        return self.async_show_form(
            step_id="init",
            data_schema=self.add_suggested_values_to_schema(
                OPTIONS_SCHEMA, self.config_entry.options
            ),
            description_placeholders={"name": self.config_entry.title},
        )
