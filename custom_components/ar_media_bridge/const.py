"""Constants for AR Media Bridge."""

DOMAIN = "ar_media_bridge"
DEFAULT_PORT = 8765

CONF_TOKEN = "token"
CONF_DEVICE_ID = "device_id"
CONF_MODEL = "model"
CONF_MANUFACTURER = "manufacturer"
CONF_CODE = "code"

# Configure screen: what to run when the box plays / pauses / stops
CONF_PLAYING_TARGETS = "playing_targets"
CONF_PLAYING_BRIGHTNESS = "playing_brightness"
CONF_PAUSED_TARGETS = "paused_targets"
CONF_PAUSED_BRIGHTNESS = "paused_brightness"
CONF_IDLE_TARGETS = "idle_targets"
CONF_IDLE_BRIGHTNESS = "idle_brightness"
CONF_DELAY = "delay"
CONF_DARK_ONLY = "dark_only"

DEFAULT_PLAYING_BRIGHTNESS = 10
DEFAULT_PAUSED_BRIGHTNESS = 60
DEFAULT_IDLE_BRIGHTNESS = 100
DEFAULT_DELAY = 2
