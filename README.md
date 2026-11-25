# AR Media Bridge

Shows what an Android TV / Google TV box is playing in Home Assistant: the app on screen (Netflix, YouTube, Showmax...), whether it's playing, paused or buffering, the title and episode when the app provides them, position and artwork. It also gives play/pause/seek, volume and app launching back from HA.

This repo has two parts:

| Folder | What it is |
|---|---|
| `android/` | The **AR Media Bridge** APK that runs on the box |
| `custom_components/ar_media_bridge/` | The Home Assistant integration (HACS) |
| `tools/mock_box.py` | A fake box for testing the HA side without a TV |

## How it works

```
 Google TV box                                   Home Assistant
┌────────────────────────────┐   WebSocket     ┌──────────────────────┐
│ AR Media Bridge APK        │◄──────────────► │ ar_media_bridge      │
│  • media sessions (state,  │   :8765 local   │  media_player entity │
│    title, artwork)         │   push          │                      │
│  • usage stats (app open)  │                 │                      │
│  • mDNS _armediabridge._tcp│ ──discovery───► │  config flow + PIN   │
└────────────────────────────┘                 └──────────────────────┘
```

## 1. Build the APK

Push the repo to GitHub. The **Build APK** action builds `ar-media-bridge.apk`. You can download it from the run's artifacts, or push a tag like `v0.1.0` to attach it to a release.

**Optional but recommended:** sign every build with the same key so that updates install over the previous version.

```bash
keytool -genkeypair -v -keystore ar-release.jks -alias ar -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 ar-release.jks   # paste into the KEYSTORE_BASE64 secret
```
Repo secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## 2. Install on the box

Enable developer options on the box (Settings → System → About → click *Build* 7 times), turn on USB/network debugging, then:

```bash
adb connect <box-ip>
adb install -r ar-media-bridge.apk

P=za.co.arsmarthome.mediabridge
adb shell cmd notification allow_listener $P/$P.MediaListenerService   # what's playing
adb shell appops set $P GET_USAGE_STATS allow                          # which app is open
adb shell appops set $P SYSTEM_ALERT_WINDOW allow                      # launch apps from HA
```

Google TV hides the settings pages for these permissions, which is why they're granted over ADB. Open **AR Media Bridge** from the apps row. All the ticks should be green, and the screen shows the box's IP and pairing code. The bridge runs in the background from then on and comes back by itself after a reboot.

## 3. Add to Home Assistant

1. Install through HACS (custom repository → Integration) and restart HA.
2. The box should appear under **Settings → Devices & services → Discovered**. If it doesn't, add **AR Media Bridge** and enter the IP.
3. A 6-digit code pops up on the TV. Type it into HA.

You get a `media_player` entity with:

| Attribute | Example |
|---|---|
| state | `playing` / `paused` / `buffering` / `idle` |
| `app_name` / `app_id` / `source` | `Netflix` / `com.netflix.ninja` |
| `media_title`, `media_artist` | `Stranger Things`, `S4:E1` (where the app provides them) |
| `media_position`, `media_duration` | seconds |
| entity picture | the session's artwork, or the app's banner |
| `foreground_app`, `media_app`, `is_home_screen` | extra attributes |

Services: play, pause, play/pause, stop, next, previous, seek, volume up/down/set/mute, and `select_source` (launches the app).

If you reset pairing on the box, HA asks you to re-enter the new code. You don't need to delete anything.

### Lights / scenes on play and pause (built in)

Go to **Settings → Devices & services → AR Media Bridge → Configure**. For **When playing**, **When paused** and **When stopped** you can pick any lights, scenes, scripts or switches:

- Lights go to the brightness slider under them (0% turns them off).
- Scenes, scripts and switches are turned on.
- **Wait before reacting** (default 2 s) stops seeking or short buffering from flickering the lights.
- **Only after sunset** skips it all during the day.

Saving applies the change straight away. You don't need a YAML automation, but the example below still works if you want more control.

### Automation example

```yaml
trigger:
  - platform: state
    entity_id: media_player.living_room_tv
    to: paused
condition:
  - condition: state
    entity_id: media_player.living_room_tv
    attribute: app_name
    state: Netflix
action:
  - service: light.turn_on
    target: { entity_id: light.lounge }
    data: { brightness_pct: 40 }
```

## Notes / limits

- How much detail you get depends on the app. Netflix reliably reports playing or paused, but whether it sends the show title depends on the Netflix version. YouTube, Plex, Kodi, Spotify and Prime Video usually send full metadata.
- If you leave a paused app and go to Home, the entity shows `idle` with the app you're now on.
- Volume controls the box's own audio stream. If HDMI-CEC hands volume to the TV, the box reports `volume_fixed` and the volume controls are hidden.

## Protocol (v1)

JSON over WebSocket at `ws://<box>:8765/`.

| Direction | Message |
|---|---|
| box → | `{"type":"hello","protocol":1,"device":{id,name,manufacturer,model,android,version}}` on connect |
| → box | `{"type":"request_pin"}` → shows the code on the TV, replies `pin_shown` |
| → box | `{"type":"pair","code":"123456","client_name":"..."}` → `paired` + `token`, or `error: invalid_code / locked` |
| → box | `{"type":"auth","token":"..."}` → `auth_ok` / `auth_failed` |
| box → | `apps`, `state`, `artwork` (base64, only when it changes), pushed live |
| → box | `{"type":"command","id":1,"command":"play|pause|play_pause|stop|next|previous|seek|volume_up|volume_down|volume_set|mute|launch", ...}` → `result` |

Five wrong codes lock pairing for 60 s and roll a new code.

## Dev: mock box

```bash
pip install aiohttp
python tools/mock_box.py        # code 123456, cycles Netflix/YouTube/Home every 15 s
```
Add it in HA by this PC's IP to work on cards and automations without the TV.
