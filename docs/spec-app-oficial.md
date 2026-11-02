# Fire TV LAN remote protocol, as implemented by the official Android app

**Source:** `com.amazon.storm.lightning.client.aosp` 4.9.1. The Hermes bundle is decompiled to `the decompiled bundle`. References written `Lnnnn` are line numbers in that file.

The JS code does not send HTTP itself. It passes every request to a native Expo module called `FeniksNetworking`. That module also handles voice streaming, so I disassembled it from `the APK's classes4.dex` (package `expo.modules.feniksnetworking`). References written `dex:Class.method` point there. The disassembly listing stayed with the local analysis files (not in this repo).

This is static analysis only; nothing was sent over the network.

**Labels used below:**
- **CONFIRMED:** I read it in the code.
- **INFERRED:** it follows from the code plus the documented behaviour of a library (OkHttp, Android).
- **UNCONFIRMED:** this app doesn't show it.

---

## 0. Corrections to the previously known behaviour

| # | Previously known | What the app actually does |
|---|---|---|
| 1 | scan `direction: "backward"` | `"back"`. MediaCommand is `{Play:'play', Forward:'forward', Back:'back'}` (L576884-576889). |
| 2 | scan `{"speed":1,"durationInSeconds":10,...}` | The values are **strings**: `{"direction":"forward","durationInSeconds":"10","speed":"1"}` (L1025470). Speed is always `"1"`. The ScanSpeed enum (`'1'`, `'2'`, `'3'`, L576890-576897) is never used. |
| 3 | Hold uses `{"keyActionType":...}` | For `/v1/media` the hold flag is a **nested** object: `"keyAction":{"keyActionType":"keyDown"}` (L1025410-1025450). For `/v1/FireTV` it is flat: `{"keyActionType":"keyDown"}`. |
| 4 | `MEDIA_NEXT`, `MEDIA_PREVIOUS` | Not present anywhere in the bundle. Only `play` and `scan` are sent. There is no pause or play_pause action. |
| 5 | A D-pad tap is a bare press | A tap sends **keyDown, then keyUp 200 ms later** (L1025930-1025955). Holding the D-pad ring sends keyDown on touch-down and keyUp on touch-up. |
| 6 | Headers are x-api-key and x-client-token | Every request also carries `x-amzn-request-id: <uuid v4>` and `Content-Type: application/json; charset=utf-8`, GETs included. |
| 7 | Action list | Also `sleep`, `wakeup`, `mute`, `epg`, `power` (L576817-576851). |
| 8 | DIAL POST | The connect/pair DIAL POST has **no** x-api-key. The automatic-recovery DIAL POST **does** send x-api-key. |
| 9 | Home | A Home tap sends two requests: `action=wakeup`, then `action=home`. |
| 10 | Self-signed certificate | The app pins one specific "Turnstile Server" certificate and disables hostname checking (§1.5). |
| 11 | Voice uses wss 9090 with PCM | CONFIRMED in native code. The WebSocket opens **after** the HTTP `start` response. It sends the same headers as that HTTP request. Audio is 16 kHz, mono, 16-bit PCM, from AudioSource VOICE_RECOGNITION. |

---

## 1. Client core

### 1.1 URLs (RequestBuilder, L591293-591610)
- **Turnstile** (`EndpointType.Turnstile = 'turnstile'`, module 1744, L591130-591137): `https://<ip>:8080/v1/` + path segments joined with `/` (buildBaseURL L591559-591610, build L591467-591557).
- **DIAL** (`EndpointType.Dial = 'dial'`): `http://<ip>:8009/apps/FireTVRemote`.
  - If the device's DIAL Application-URL is known (`dialBaseUrl`, from SSDP), the URL is the literal string `dialBaseUrl + "FireTVRemote"`. Path joining is skipped (build L591467-591480, L591924-591930, L970920-970930, L971099-971109).
- **Query string:** `?k=v&k2=v2`, built by plain string concatenation with no percent-encoding (L591490-591532).
- **Methods:** `ApiOperations` = GET, POST, PUT, DELETE (L576808-576816). PUT and DELETE are never used.

### 1.2 Headers (Networking.internalPerform, L577969-578165)
Header-name constants are at L591166-591173.

| Header | When it is sent | Value |
|---|---|---|
| `x-amzn-request-id` | Always, regenerated on every attempt | `uuid.v4()` (L578071-578077; uuid module 1719 with the crypto `getRandomValues` polyfill, module 1717) |
| `x-client-token` | When the request has the `authorized` modifier and a token is set | The token from pin/verify (L578101-578119) |
| `Content-Type` | Always | `text/plain` if `textPlain`, otherwise `application/json; charset=utf-8` (L578121-578140) |
| `x-api-key` | Unless the request has `noApiKey` | `0987654321`. Hard-coded at L577421; `configureAPIKey` is never called. Logic at L578141-578155. |

### 1.3 Modifiers (L591208-591223)

| Modifier | Effect |
|---|---|
| `authorized` (0) | Adds x-client-token. If the token is `undefined` the request throws `requestCouldNotBeAuthorized`. |
| `retriable` (1) | `perform()` wraps the request in retryWithBackOff (L577602-577625). |
| `textPlain` (2) | Content-Type is `text/plain`. |
| `noApiKey` (3) | Omits x-api-key. |
| `wakeOnLan` (4) | Before **each attempt**, sends a WOL magic packet if a MAC is known (L578031-578057, §1.9). |

### 1.4 Native transport
Evidence: `dex:DeviceConnection$processRequest$2`, `startProcessingQueueRequests`.

- **Hand-off:** JS calls `FeniksNetworking.request(JSON.stringify({url, method, timeoutInterval, headers, body, bodyString, endpointType}))` (L591533-591556, L578157-578167). The client is OkHttp.
- **Body:**
  - If `body` (an object) is present, it is serialized with Gson.
  - Otherwise, if `bodyString` is present, it is sent as-is.
  - Otherwise the body is the **empty string**.
  - The media type is the request's Content-Type. GET requests have no body.
  - So a POST "without body" goes out with `Content-Length: 0` and the JSON content type.
- **Charset (INFERRED):** OkHttp appends `; charset=utf-8` to a media type that has none, so DIAL requests go out as `text/plain; charset=utf-8`.
- **Timeouts:** connect, write and read timeouts are all set to `timeoutInterval` **seconds**, per request. The native default is 10.
- **Serialization:** requests are executed **strictly one at a time, in order**, from a single unbounded queue.
- **Queue reset:** `cleanQueue()` cancels in-flight and queued requests; they are rejected with `REQUEST_CANCELLED_BY_RESET`. JS calls it after **any** failed request (L578242).
- **Response:** the module returns `{status, message, data}` to JS. JS parses `data`, and an empty body becomes `"{}"` (L578371-578380).

### 1.5 TLS
Evidence: `dex:DeviceConnection.<init>`, `createBaseOkHttpClient`, `addCertificateToTrustManager`, `createBaseOkHttpClient$1.verify`.

- The SSLContext is `"TLSv1.2"`. Its TrustManager is built from a KeyStore that contains **only** the embedded certificate below, under alias `turnstile`.
- `HostnameVerifier.verify()` always returns `true`.
- The certificate is self-signed: CN=Turnstile Server, OU=Lab 126, O=Amazon, L=Austin, ST=Texas, C=US. Key is EC P-256 with CA:TRUE. Valid 2020-09-29 to 2040-09-29. SHA-256 of the DER: `42e4e4644c5c58a034a8c34ecb764157f2c7704828b44a5c8750196a42b9967a`.
- For your app, pin this certificate (or trust all certificates) and skip hostname verification.

```
-----BEGIN CERTIFICATE-----
MIICGDCCAb6gAwIBAgIQMCbLRSMSZeJ8CrNo77s8HzAKBggqhkjOPQQDAjBsMQsw
CQYDVQQGEwJVUzEPMA0GA1UECgwGQW1hem9uMRAwDgYDVQQLDAdMYWIgMTI2MQ4w
DAYDVQQIDAVUZXhhczEZMBcGA1UEAwwQVHVybnN0aWxlIFNlcnZlcjEPMA0GA1UE
BwwGQXVzdGluMB4XDTIwMDkyOTAwNTYzMloXDTQwMDkyOTAxNTYzMlowbDELMAkG
A1UEBhMCVVMxDzANBgNVBAoMBkFtYXpvbjEQMA4GA1UECwwHTGFiIDEyNjEOMAwG
A1UECAwFVGV4YXMxGTAXBgNVBAMMEFR1cm5zdGlsZSBTZXJ2ZXIxDzANBgNVBAcM
BkF1c3RpbjBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABIpSQp54uGLOXZSLqFrX
YRr9FQfr7NLLIuBAwjuqd3UmoRGaNSmwWkK76l0x5X51Wpl61SEAg19SmxQsIK39
njmjQjBAMA8GA1UdEwEB/wQFMAMBAf8wHQYDVR0OBBYEFCQD+GE4x4I+++GOe4LI
Dfn9cRqvMA4GA1UdDwEB/wQEAwIBhjAKBggqhkjOPQQDAgNIADBFAiAlhAjUdehX
shT/1YGqirPCKDdcdkOSd0zoQIz+0CNeiQIhAM+zsJV1s09va971oxmvA1N4c7Uj
FIW4fxlgaJwQD+Jl
-----END CERTIFICATE-----
```

### 1.6 Endpoint table
- "empty" in the body column means a zero-length body (§1.4).
- Timeouts are in seconds. The default is 10 (RequestBuilder L591303).

| Purpose | Method and path | Modifiers | Timeout | Request body | What the app reads | Lines |
|---|---|---|---|---|---|---|
| DIAL launch (connect/pair) | POST `:8009/apps/FireTVRemote` | retriable, textPlain, **noApiKey**, wakeOnLan | 10 (controller may override) | empty | nothing | L971021-971110 |
| DIAL wake (auto-recovery) | POST same URL | textPlain, retriable, wakeOnLan | **2** | empty | nothing | L591873-591935 |
| DIAL "heartbeat" (never scheduled) | GET same URL | retriable, textPlain, wakeOnLan | 10 | – | nothing | L970857-970935 |
| Status | GET `/v1/FireTV/status` | authorized, retriable | 10 (2 in auto-recovery) | – | §7 | L971189-971290, L591691-591780 |
| Device info, v1 | GET `/v1/FireTV` | authorized | 10 | – | §7 | L970181-970250 |
| Device info, v2 | GET `/v1/FireTV2` | authorized | 10 | – | §7 | L970334-970397 |
| Properties | GET `/v1/FireTV/properties` | authorized | 10 | – | §7 | L970487-970550 |
| Capability feedback | POST `/v1/FireTV/properties` | authorized | 10 | `{"volumeSupport":..,"muteSupport":..,"powerSupport":..}` | nothing | L966230-966290, L966518-966580 |
| Apps, v1 | GET `/v1/FireTV/apps` | authorized, retriable | 10 | – | §5 | L969869-969935 |
| Apps, v2 | GET `/v1/FireTV/appsV2` | authorized, retriable | 10 | – | §5 | L970025-970090 |
| Launch app | POST `/v1/FireTV/app/<appId>` (not URL-encoded) | authorized | 10 | empty | nothing | L974724-974810 |
| Launch shortcut | POST `/v1/FireTV/shortcut/<encodeURIComponent(x)>` | authorized | 10 | empty | nothing | L974808-974812 |
| Intent | POST `/v1/FireTV/intent` | authorized | 10 | `{"postData":"<base64(JSON)>"}` | nothing | L1034989-1035060 |
| Key | POST `/v1/FireTV?action=<a>` | authorized | **3** | §2 | nothing | L1021697-1021845 |
| Media | POST `/v1/media?action=play\|scan` | authorized | 10 | §3 | nothing | L1025277-1025475 |
| Keyboard state | GET `/v1/FireTV/keyboard` | authorized | 10 | – | `state` | L1029956-1030020 |
| Text, v2 (whole string) | POST `/v1/FireTV/keyboard` | authorized | 10 | `{"text":"<full text>"}` | nothing | L1030260-1030320 |
| Text, v1 (one char) | POST `/v1/FireTV/text` | authorized | 10 | `{"text":"<1 char>"}` | nothing | L1030113-1030175 |
| Voice | POST `/v1/FireTV/voiceCommand?action=start\|stop` | authorized | 10 | empty | nothing; also triggers the native WebSocket | L1017193-1017250 |
| PIN display | POST `/v1/FireTV/pin/display` | retriable (**no token**) | **3** | `{"friendlyName":"<name>"}` | nothing | L970716-970775 |
| PIN verify | POST `/v1/FireTV/pin/verify` | none (no token) | 10 | `{"pin":"1234"}` | `description` | L1041103-1041150 |
| PIN cancel | POST `/v1/FireTV/pin/verify` | none | 10 | `{"pin":"-1"}` | nothing | L1040944-1041025 |
| Find my remote | POST `/v1/FireTV/ringRemotes` | authorized | 10 | empty | nothing | L962757-962840 |
| Unpair all apps | POST `/v1/FireTV/clearTokens` | authorized | 10 | empty | nothing | L962918-963000 |

There are 25 path literals in the bundle; all of them are in this table (L591755 … L1041132).

### 1.7 Response and error handling (L578175-578460)
- **Status below 400:** the request's `parse()` runs.
  - A **405 on a path containing "status"** is treated as success (L578405-578420). The body then fails to parse as JSON and falls back to `{"turnstileVersion":"","osVersion":"","platformType":""}` (L971286). This is how older TVs end up in "v1" mode.
- **Status 400 or above:** NetworkError(message, status, data).
  - On port 8080, a **403** calls `onUnauthorized()`. The app logs "Resetting device" and goes back to PIN pairing (L578292, L578451, L981757).
- **Automatic recovery:** a **non-retriable** port-8080 request triggers `wakeUpTurnstile()` (§1.9b) when either:
  - it fails at the transport level (no HTTP status: timeout, refused), or
  - its HTTP status is not in `[403, 429, 405, 404, 409]` (L578250-578310).
  
  Afterwards the **original error is rethrown**; the command is not replayed.

### 1.8 Retry policy
- **RetryOptions defaults** (L591083-591089): `maxRetries` 15 (16 attempts), `interval` 500 ms, `factor` 1.1, `maxDelayInMilliseconds` 1000.
- **Delay** after attempt n = `min(500·1.1^n, 1000)` ms (exponentialDelay L590486; loop around L590650).
- **TurnstileController** adds `shouldRetry`: no retry on 403, 429 or user cancel (L969734-969770, L969690-969730).
- Only requests with the `retriable` modifier retry at all.

### 1.9 DIAL wake and Wake-on-LAN
The app sends DIAL in three ways.

**(a) Connect/pair DIAL** (DialRequest)
- **Request:** POST to the URL in §1.1.
  - Headers: `Content-Type: text/plain`, `x-amzn-request-id`. No x-api-key, no token.
  - Empty body, 10 s timeout, retried with the controller policy, with WOL first.
- **When it is sent:**
  1. As step 1 of every connection to a known TV (getStatusInfo L968453-968530). GET status follows immediately, with no delay.
  2. Before showing the PIN. `execute({withDial:true})` runs DIAL, waits **500 ms** (L967773), then runs `pin/display`.
  3. After a non-429 pin/verify failure, before the PIN is retried (L1039424-1039460).
- **If it fails**, the connect or pair flow is aborted.

**(b) Automatic recovery** (Networking.wakeUpTurnstile, L577652-577960)
- **Request:** POST to the same URL, using WakeUpTurnstileRequest (L591873-591935).
  - Headers: `Content-Type: text/plain`, **`x-api-key: 0987654321`**, `x-amzn-request-id`.
  - 2 s timeout.
  - Retried with the **default** policy, so up to 16 tries, with WOL.
- Only one recovery runs at a time (`isWakingUpTurnstile`, L577690).
- **Sequence:**
  1. The DIAL POST.
  2. A wait of **200 ms** (L577794).
  3. If a token is set: `GET /v1/FireTV/status` (authorized, retriable, 2 s timeout) (L577800-577815).

**(c) DialGetRequest** (GET, with x-api-key)
- Used only by the `heartbeat` operation (L967380).
- Nothing in the app adds that operation, so it is **unused**.

**WOL packet** (module 1735, L582146-582300)
- 102 bytes: `FF×6` followed by the MAC 16 times. The MAC must be 12 hex digits once `:` and `-` are stripped.
- Sent over UDP with react-native-udp (`NativeModules.UdpSockets`) from a socket bound to port 0, to `255.255.255.255:9`.
- The MAC comes from the SSDP `WAKEUP` header (§11).
- The app never calls `setBroadcast`. Enable SO_BROADCAST yourself.

---

## 2. Key actions: `POST /v1/FireTV?action=<action>`
**Actions** (RemoteTapCommand, L576817-576851):

| Constant | Action string |
|---|---|
| Up | `dpad_up` |
| Down | `dpad_down` |
| Left | `dpad_left` |
| Right | `dpad_right` |
| Select | `select` |
| Back | `back` |
| Home | `home` |
| Details | `menu` |
| Sleep | `sleep` |
| Wake | `wakeup` |
| Mute | `mute` |
| VolumeUp | `volume_up` |
| VolumeDown | `volume_down` |
| Backspace | `backspace` |
| Epg | `epg` |
| Power | `power` |

`keyDownUp` exists in the enums (L576864) but is never sent.

**Body** (SendCommandRequest.body, L1021797-1021840):

| Arguments | Body |
|---|---|
| `isLongPress === true` | `{"keyActionType":"keyDown"}` |
| `isLongPress === false` | `{"keyActionType":"keyUp"}` |
| `isLongPress` null or undefined, explicit `keyActionType` given | `{"keyActionType":"<keyActionType>"}` |
| otherwise | no body |

**Timing and UI behaviour.** There is **no client-side auto-repeat** anywhere; holds rely on keyDown/keyUp.

| Control | Requests sent | Lines |
|---|---|---|
| D-pad ring (Dpad and NewDpad PanResponder) | Touch-down: `dpad_*` or `select` + keyDown. Touch-up or terminate: same + keyUp. | L1026821-1026910, NewDpad L1046689 |
| D-pad tap path: accessibility buttons, short touchpad tap or swipe | keyDown, then **200 ms** later keyUp (two requests). Also vibrates. | useRemoteControl L1025835-1025960, COMMAND_MAP L1025805-1025832 |
| Touchpad | Swipe threshold 30 px, long-press delay 300 ms. Long press: keyDown (`select` if no movement, else the direction), then keyUp on release. "Multi-tile" swipes: one tap sequence per tile step. | L1027903-1028370 |
| Volume +/-, Home, Play/Pause, Rewind, FF, Channel +/- (sent as `dpad_up`/`dpad_down` in ChannelsPanel), EPG | `useLongPress` with a **300 ms** threshold. Short press: one request **on release**, no body. Long press: keyDown when 300 ms elapses, keyUp on release. | useLongPress L1022767-1022925 (300 at L1022781); volume L1022540-1022570; ChannelsPanel L1046055+; player L1024597+ |
| Home specifically | Short press or long-press start: first `action=wakeup` (no body; errors ignored), then `action=home` (no body, or keyDown). Long-press end: only `home` + keyUp. | SendHomeCommandRequest L1025557-1025660 |
| Back, Menu, Mute, Power, Sleep | Single request, no body. | Back L1025041, Menu L1025090, Mute L1022572, Power L1022605, Sleep L1026063-1026072 / L1051890 |
| Hardware volume keys (useVolumeInterceptor) | `volume_up` or `volume_down`, no body. | L1022685-1022705 |

Keys share the serial native queue (§1.4), so keyDown and keyUp keep their order. The timeout is 3 s and keys are not retried.

---

## 3. Media: `POST /v1/media?action=…`
Source: MediaCommandRequest, L1025277-1025475.

- **Query:** `action=play` if the command is Play, otherwise `action=scan` (L1025340-1025362).
- **Body** is sent as `bodyString` (pre-serialized JSON). It is omitted when it would be `{}`.

| Gesture | Request |
|---|---|
| Play tap | `?action=play`, no body |
| Play hold start / end | `?action=play` with `{"keyAction":{"keyActionType":"keyDown"}}` / `{"keyAction":{"keyActionType":"keyUp"}}` |
| FF tap | `?action=scan` with `{"direction":"forward","durationInSeconds":"10","speed":"1"}` |
| FF hold start / end | Same as the tap, plus `"keyAction":{"keyActionType":"keyDown"}` / `"keyAction":{"keyActionType":"keyUp"}` |
| Rewind | As FF, with `"direction":"back"` |

Holds use the 300 ms `useLongPress` (L1024620-1024960). There is no separate pause action; `play` acts as the toggle.

---

## 4. Keyboard and text
Source: useKeyboardText, L1029394-1029930.

- **Opening the keyboard:** when the user opens the keyboard sheet, the app sends **one** `GET /v1/FireTV/keyboard` (L1029747). A failure is treated as `{"state":"hidden"}`.
- **No polling:** there is no polling and no push channel. The app does not detect TV text-field focus; the user opens the keyboard.
- **Response:** only the field **`state`** is read.
  - `"visible"` or `"text"` selects V2 mode (L1029825-1029845).
  - Anything else selects legacy mode.
- **V2 mode:** on every change of the input, `POST /v1/FireTV/keyboard` with `{"text":"<entire current field text>"}` (L1029575-1029600).
- **Legacy mode** handles each key event:
  - `"Backspace"` sends `POST /v1/FireTV?action=backspace`.
  - Otherwise the app takes the last character of the key string and sends `POST /v1/FireTV/text` with `{"text":"<that char>"}`. An empty string sends backspace instead (L1029420-1029520).
- **Prefill:** the local text box starts empty; it is not filled from the server.

---

## 5. Apps, shortcuts, intents
### 5.1 Listing apps
- **Endpoint choice:** if status `turnstileVersion` is non-empty the app uses `GET /v1/FireTV/appsV2`; otherwise `GET /v1/FireTV/apps` (addGetApps L967500-967550, getApps L969158-969314).
- **Response:** a JSON **array**. The app maps each element as follows (L969225-969265):

```
id          = array index                 // order is exactly as the TV returns it; no client-side sort in this layer
content     = tvIconArt ? addImageSizeSuffix(tvIconArt) : iconArtSmallUri
intent      = isShortcutApp ? (appShortcutLaunchIntent ?? appId) : appId
isShortcut  = isShortcutApp ?? false
name        = name
yacItemId   = appId
options     = options        // passed through (YAC per-app options)
```

- **addImageSizeSuffix(url, 210)** (L970594-970640): if the URL ends in `.jpg|.jpeg|.png|.gif`, insert `._SX210_` before the extension. Example: `x.png` becomes `x._SX210_.png`.

### 5.2 Launching (LaunchAppRequest(name, isShortcut=false), L974724-974810)
- **Normal app:** `POST /v1/FireTV/app/<appId>`. The app ID is raw, not URL-encoded. No body.
- **Shortcut:** `POST /v1/FireTV/shortcut/<encodeURIComponent(intent)>`. No body.
- **System shortcuts** (SystemIntents, L974810-974822):

| Use | Request | Lines |
|---|---|---|
| Settings (old remote) | `POST /v1/FireTV/app/settings` | L1010912, L1037687 |
| Quick-action panel (new remote) | `POST /v1/FireTV/shortcut/amazon.intent.action.SETTING_BUTTON_PRESSED` | L1051851 |
| Check for updates | `POST /v1/FireTV/shortcut/com.amazon.device.settings.about.action.CHECK_FOR_UPDATES` | L1033444 |
| Start TV setup | `POST /v1/FireTV/shortcut/com.amazon.tv.dcoobe.ACTION_START_DC_OOBE` | L974221 |

### 5.3 Intents (IntentRequest(payload), L1034989-1035060)
- **Request:** `POST /v1/FireTV/intent` with body `{"postData": btoa(JSON.stringify(payload))}`.
- **Payloads seen:**

| Payload | Lines |
|---|---|
| `{"action":"InternalAmazonIntent","ref":<ref>,"profileId":<directedId>,"switchAccounts":bool,"switchProfiles":bool}` | L1033080-1033120 |
| `{"action":<a>,"ref":<r>,"profileId":..,"switchProfiles":..,"switchAccounts":..,"extras":..}` | L1033860-1033915 |
| YAC: `{"action":..,"extras":..,"profileId":currentFireTvProfileId}` | L1035639 |
| `{"action":"OpenQuickActionPanel","ref":"","switchProfiles":false}` | L1043488 |

---

## 6. Pairing
Source: usePinScreen L1039150-1039765; requests L970716, L1040944, L1041103.

1. **Request the PIN.** The app runs a TurnstileController with `addShowPinRequest(deviceName ?? "Mobile device")` (L1039200-1039216). `deviceName` is the phone's name from expo-device.
   - It calls `execute()` with the default `withDial=true`, which sends DIAL POST (§1.9a), waits 500 ms, then sends `POST /v1/FireTV/pin/display` with `{"friendlyName":"<name>"}`.
   - pin/display has **no x-client-token**, sends x-api-key, has a 3 s timeout, and is retried.
2. **Enter the PIN.** The PIN is **4 digits**; the app auto-submits as soon as the length is 4 (L1039270-1039285).
3. **Verify.** `POST /v1/FireTV/pin/verify` with `{"pin":"<4 digits>"}` (a string). No token is sent, and the request is not retried at the HTTP layer.
4. **Handle the response** (L1039355-1039380):

| Outcome | What the app does |
|---|---|
| `response.description` non-empty | Success. `description` is the token; the app stores it and calls `configureToken`. |
| `description` empty (`""`) | Treated as a **wrong PIN**. The app shows a WrongPin error and immediately re-runs show-PIN, so the TV displays a new PIN (L1039650-1039690). |
| HTTP error 429 (`isThrottleError`, L1039118) | Shows a "Throttled" error. No retry. |
| Any other HTTP or network error | DIAL wake, then retry verify with the same PIN, up to **2** retries (L1039305-1039460). After that, shows ConnectionError. |

5. **Cancel.** Leaving the PIN screen sends `POST /v1/FireTV/pin/verify` with `{"pin":"-1"}` (L1041018, L1039560-1039600).

---

## 7. Status, device info, properties
### 7.1 Status
- **Request:** `GET /v1/FireTV/status`.
- **Fields used:** `turnstileVersion`, `osVersion`, `platformType` (parse fallback L971286).
- **Mode:** a non-empty `turnstileVersion` puts the app in **"v2"** mode (L967440-967520, L968580-968600). In v2 the app uses `/v1/FireTV2` and `/v1/FireTV/appsV2`; in v1 it uses `/v1/FireTV` and `/v1/FireTV/apps`.

### 7.2 Device info (`GET /v1/FireTV` or `GET /v1/FireTV2`)
The app maps the response at L969078-969125. The mapped result is merged with the status fields.

| Response field | App field | Default if missing |
|---|---|---|
| `isVolumeControlsSupported` | `isVolumeSupported` | false |
| `volumeCapabilityLevelOfSupport` | `volumeSupportLevel` | null |
| `powerCapabilityLevelOfSupport` | `powerSupportLevel` | null |
| `ringableRemoteCount` | `myRemote` (true when the count is above 0) | – |
| `isEpgSupported` | `epg` | false |
| `isInternalIntentSupported` | `internalIntent` | false |
| `isPropertiesApiSupported` | `isPropertiesApiSupported` | false |

### 7.3 Properties (`GET /v1/FireTV/properties`)
Fetched only if `isPropertiesApiSupported` is true (L982221-982245). Mapping at L969350-969410:

`codiVersion`, `epgSupport`, `internalIntentSupport`, `osVersion`, `pfm`, `platformType`, `powerSupport` (stored as powerSupportLevel), `ringMySupport`, `turnstileVersion`, `volumeSupport` (stored as volumeSupportLevel), `muteSupport` (stored as muteSupportLevel), `volumeTroubleshootingAction`, `dsn`, `deviceType`, `clearTokensSupport`, `areYACOptionsAvailable`, `currentFireTvProfileId`.

YAC options are enabled when `areYACOptionsAvailable` is true and `currentFireTvProfileId` is not null.

**Support-level values:** `"Supported"`, `"MaybeSupported"`, `"NotSupported"` (CapabilitySupportLevel L963034-963039).

### 7.4 How the app uses these flags

| Flag | Effect |
|---|---|
| `isVolumeSupported` false | Volume and mute controls are hidden (L986415-986440). |
| Volume or power level `MaybeSupported` / `NotSupported` | Shows TV-setup or troubleshooting prompts instead of assuming the control works (L986282-986320). |
| `myRemote` | Shows "Find my remote" (§9). |
| `epg` | Shows the channels panel: EPG plus channel up/down (L1046055). |
| `clearTokensSupport` | Shows "Unpair all apps" (L962434). |

### 7.5 Capability feedback
`POST /v1/FireTV/properties` with a subset of `{"volumeSupport":"Supported|NotSupported","muteSupport":…,"powerSupport":…}`. The app sends it after the user confirms in TV-setup whether volume, mute or power work (L964830-964870, L966518-966580). It is optional for a remote.

While a TV-setup is pending, the app polls `GET /v1/FireTV/properties` every 8 s (L1026500).

---

## 8. Voice
- **JS side** (useAlexaVoiceSearchHandler L1016951-1017110; VoiceSearchRequest L1017193):
  - `POST /v1/FireTV/voiceCommand?action=start` (authorized, empty body), and later `…?action=stop`.
  - The button is a **tap toggle** (`alexa_button_start` / `alexa_button_stop`, L1010737, L1051588). The microphone permission is required. JS has no automatic stop timer.
- **Native side** (`dex:FeniksNetworkingModule.isVoiceSearchStartCommand`) matches any URL that contains `/FireTV/voiceCommand?action=start`.
- **Start sequence:**
  1. The HTTP POST is queued and sent normally.
  2. When the HTTP response arrives, whatever its status, the module opens a WebSocket (`DeviceConnection.openWebSocket` → `FeniksWebSocket.createSocket`).
     - URL: **`wss://<host>:9090/`** (`transformURLIfNeeded`: `"wss://" + host + ":9090/"`).
     - Headers: the same as the HTTP request (`x-api-key`, `x-client-token`, `x-amzn-request-id`, `Content-Type`).
     - TLS: the same pinned certificate and hostname-verifier bypass as §1.5.
  3. When the socket opens, `VoiceSearch.startStreamingAudio` starts recording.
- **Audio** (`VoiceSearch.<clinit>`, `VoiceStreamTask.doInBackground`):
  - `AudioRecord(source=6 VOICE_RECOGNITION, 16000 Hz, CHANNEL_IN_MONO(16), ENCODING_PCM_16BIT(2), bufferSize = minBuf×10)`, where `minBuf = AudioRecord.getMinBufferSize(16000, MONO, PCM_16BIT)`.
  - The loop is `read(buf, 0, minBuf)`, then `webSocket.send(ByteString)`. Each read is sent as one **binary** frame.
  - The frames are raw PCM s16le with no header.
  - Frame size equals the device's minBuf (device-dependent). Whether short reads are trimmed is UNCONFIRMED.
- **Stop sequence** (native `handleVoiceSearchStopCommand`):
  1. Stop recording.
  2. `webSocket.close(1000, "WebSocket closed")`.
  3. Send `POST …voiceCommand?action=stop`.

---

## 9. Find my remote and unpair all

| Feature | Request | Shown when | Lines |
|---|---|---|---|
| ringRemotes ("Find my remote") | `POST /v1/FireTV/ringRemotes`, authorized, empty body, 10 s, not retried | `ringableRemoteCount > 0` | L962757-962840; UI L962553, L1026132 |
| clearTokens ("Unpair all apps") | `POST /v1/FireTV/clearTokens`, authorized, empty body; the app disconnects on success | `clearTokensSupport` | L962918-963000; UI L962596 |

---

## 10. Power, sleep, wake, mute, settings
All of these are plain `POST /v1/FireTV?action=…` with no body:

| Action | When the app sends it |
|---|---|
| `power` | Power button (L1022595-1022610) |
| `sleep` | Sleep button (L1026063-1026072, L1051890) |
| `wakeup` | Automatically before Home (§2); there is no standalone button |
| `mute` | Mute button (L1022572) |
| `epg` | EPG button |

Settings use `/v1/FireTV/app/settings` or the shortcut intents in §5.2.

Power and mute visibility depend on the properties and device-info levels in §7.4.

---

## 11. Discovery
Discovery is done in **JS**, with SSDP over react-native-udp (`NativeModules.UdpSockets`) and RN `fetch`. There is no NsdManager, mDNS, zeroconf or whisperplay code in the bundle.

Source: SSDPDiscovery, L976348-977400.

1. **Socket:** create a `udp4` socket with `reusePort:true` and `bind(0)` (L976666-976980).
2. **M-SEARCH:** sent immediately and then **every 1000 ms** (L976978) to `239.255.255.250:1900`. The search target `ssdpFilter` is `urn:dial-multiscreen-org:service:dial:1` (L977538). Exact bytes (L976505-976540):

```
M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: "ssdp:discover"\r\nMX: 1\r\nST: urn:dial-multiscreen-org:service:dial:1\r\n\r\n
```

3. **Stopping:** the scan stops after `timeoutInMs`, which defaults to **10000 ms** (L989850, L990448-990500).
4. **Filtering replies:** the app requires `HTTP/1.x 200`. Header names are upper-cased. Each source IP is processed once per session.
5. **Device description:** `fetch(LOCATION)` (plain HTTP GET).
   - The response header **`Application-URL`** becomes `dialBaseUrl`. The fallback is `http://<ip>:8009/apps/` (L977160-977190).
   - From the XML the app reads `friendlyName`, `deviceType`, `modelName`, `UDN`, `manufacturer` (L977251-977400).
6. **MAC:** parsed from the SSDP **`WAKEUP`** header with regex `/MAC=([\da-f]{2}(:[\da-f]{2}){5})/i` (L976393).
7. **Fire TV check** (isFireTVDevice, L977040-977111):
   - The port of `dialBaseUrl` must be **8009 or 8019** (L976391).
   - `fetch(dialBaseUrl + "FireTVRemote")` (GET) must return status below 400.
8. **Device record:** `{udn, name: friendlyName, ip, deviceTypeId: modelName, mac, dialBaseUrl}`, deduplicated by UDN. On select, the app calls `configureIpAddress`, `configureDialBaseUrl` and `configureMacAddress` (L982031-982038).

---

## 12. End-to-end sequences
**Connecting to an already-paired TV.** Triggered by `addStatusInfoRequest` + `execute({withDial:false})` (L982189-982351).
1. DIAL POST (§1.9a).
2. `GET /v1/FireTV/status`.
3. `GET /v1/FireTV` or `GET /v1/FireTV2`.
4. If `isPropertiesApiSupported`: `GET /v1/FireTV/properties`.

The apps screen later sends `GET /v1/FireTV/apps` or `GET /v1/FireTV/appsV2`.

**Pairing a new TV.**
1. DIAL POST.
2. Wait 500 ms.
3. `pin/display`.
4. The user types the 4-digit PIN.
5. `pin/verify`.
6. Store `description` as the token.
7. Run the sequence above.

**Any later 403 on port 8080:** the stored token is dropped and the app goes back to pairing.

---

## 13. UNCONFIRMED
- Whether the TV accepts `MEDIA_NEXT`, `MEDIA_PREVIOUS`, `pause`, `keyDownUp`, or scan speeds `"2"` and `"3"`. They exist only as unused enums or not at all.
- Whether numeric JSON values (e.g. `10` instead of `"10"`) are accepted. The app always sends strings.
- The full response schemas; only the fields listed above are read.
- The fields in `GET /v1/FireTV/keyboard` other than `state`.
- The exact wrong-PIN response. The code implies HTTP 2xx with `"description":""`; any HTTP error is treated as a connection problem.
- The `; charset=utf-8` suffix on DIAL's `text/plain`. It is INFERRED from OkHttp; the JS sets plain `text/plain`.
- Whether `x-amzn-request-id` is required by the TV. The app always sends it.
- The voice frame size (device-dependent minBuf) and whether short reads are trimmed.
- The iOS client. Only the Android native code was examined.
