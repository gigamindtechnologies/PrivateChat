# PrivateChat

A private messenger for Android with **no servers, no database and no accounts**.
Messages go directly from one phone to the other through the Tor network. A message written
while your friend is offline waits on *your* phone and goes out as soon as they are online.

> **Status: early prototype (v0.1).** Automated tests pass, including over a real Tor network,
> and the app has been tested end to end on an Android 14 emulator. It has not been tested on
> real phones yet, and it has **not** had an independent security audit. Do not rely on it where
> your safety depends on it until it has.

## How it works

1. **Create your private ID.** Type a name. The phone creates a secret key. There is no phone
   number, email or sign-up, and nothing is uploaded anywhere.
2. **Add someone in person.** One person taps **Show my code**, the other taps **Scan a code**.
   Both screens then show the same 5 symbols (for example 🦊 🍕 🚀 🌙 🔑). If they match, the
   person showing the code taps **They match**. This is the only way to add a contact.
3. **Chat.** Messages travel phone → Tor → phone. If the other person is offline, the message
   waits on your phone and is sent automatically when they come online. Nothing waits on a
   server, because there is no server.
4. **Ticks, like WhatsApp:** 🕓 waiting on your phone · ✓ sent · ✓✓ delivered to their phone ·
   green ✓✓ read. Read receipts can be turned off in Settings (then you don't see theirs either).
5. **Online and last seen, like WhatsApp.** A chat shows "online" or "last seen today at 10:42".
   "Last seen" is worked out and stored only on your phone. Hide your status from everyone
   (Settings → Privacy) or from one person (chat menu); then you don't see theirs either.
   In a chat's menu, **Tell me when … is online** sends you a notification when they come online.
6. **App lock.** Settings → App lock asks for a PIN (or fingerprint) whenever the app is opened.
   Messages keep arriving while it is locked.

## Privacy features

| Feature | What it protects you from |
| --- | --- |
| No phone number, email or account | Nobody can look you up, and there is no account to hack |
| No servers at all | No company that can be hacked, ordered to hand over data, or shut down |
| Every phone runs its own Tor onion service | Neither person learns the other's IP address or location |
| Contacts-only onion address (Tor client authorization) | Strangers can't even tell whether you exist or are online. Only your contacts hold the key to find you |
| One-time QR codes | The code holds only a temporary address and key, deleted after one use or 5 minutes. A photo of it is useless later |
| QR code looks like random letters | Other scanner apps see meaningless text. No name, no ID |
| Safety symbols when pairing | Stops someone who secretly photographed your code from pretending to be your friend |
| End-to-end encryption, fresh keys every connection (Noise protocol) | Even if a key is stolen later, past conversations stay secret (forward secrecy) |
| Message padding | Observers can't guess what was said from the message size |
| Encrypted storage, key in Android Keystore | A copied phone backup or file dump is unreadable |
| No cloud backups | Nothing is copied to Google |
| Screenshots blocked, app hidden in recent apps | Other apps and onlookers can't capture your chats |
| Message notifications say only "N new messages" | Your lock screen never shows names or text |
| "Last seen" is stored only on your phone, and can be hidden | No server keeps a record of when you were online |
| App lock with PIN or fingerprint | Someone holding your unlocked phone can't open your chats. The PIN pad is drawn by the app, so no keyboard app sees it |
| Removing a contact removes it on both phones | They can no longer reach or find you |
| Delete everything | Wipes identity, contacts, messages and destroys the encryption key |
| No analytics, ads, crash reporting or Google services | Nothing reports on how you use the app |

## Things to know (honest limitations)

- **Both phones must be online at the same time for a message to arrive.** Until then it waits
  on the sender's phone (🕓). There is no server to hold messages, by design.
- **The app keeps a connection open** (you will see a "PrivateChat is online" notification) so
  contacts can reach you. This uses some battery and data. Turn it off in Settings → Connection
  to become unreachable.
- **Allow background activity.** The app asks once to "run in the background" (Android's battery
  optimization exemption). Without it, Android cuts the app off the network when it is closed and
  messages stop arriving. On Xiaomi, Oppo, Realme, Vivo, OnePlus and some Samsung phones, also turn
  on "Autostart" for PrivateChat in the phone's own settings. The app restarts by itself after the
  phone reboots, and reconnects as soon as the internet comes back.
- **Connecting takes a few seconds.** Tor starts in about 10–30 seconds, and pairing usually
  takes 10–60 seconds.
- **Android only for now.** iPhones don't allow apps to stay reachable in the background.
- **Hiding your online status** hides "online" and "last seen". As on WhatsApp, ticks still show
  when a message has reached a phone. Hiding relies on your contact's app respecting it.
- **"… is online" alerts** name the contact, but not on the lock screen. They wait about 20
  seconds, only come if the person was away for at least 5 minutes, and at most once every 30
  minutes, so a shaky connection doesn't flood you.
- **If you forget your app lock PIN**, the only way back in is to uninstall the app, which deletes
  your messages and contacts. After 5 wrong PINs, each further try waits longer (up to 15 minutes).
- **Your name is only shared at pairing.** If you change it later, existing contacts keep the
  old one (they can rename you on their phone).
- **If you lose your phone or delete the app**, your identity and messages are gone. There is
  no backup on purpose, and you will need to pair again.

## Project layout

```
core/   Pure Kotlin engine: crypto, Tor control, pairing, connections, storage (tested on the JVM)
app/    Android app: Jetpack Compose UI, foreground service, Tor runtime, Keystore, camera/QR
tools/testnet/  Scripts to run the end-to-end test over a private Tor network
docs/SECURITY.md  Technical security design and threat model
```

Key libraries: [tor-android](https://github.com/guardianproject/tor-android) (Tor built into the
app), [Bouncy Castle](https://www.bouncycastle.org/) (X25519, ChaCha20-Poly1305),
[ZXing](https://github.com/zxing/zxing) (QR codes, fully on-device), Jetpack Compose and CameraX.

## Building

Requirements: JDK 17 or newer and the Android SDK (Android Studio installs both).

```bash
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # unsigned, minified release APK
```

Install the debug APK on a phone with `adb install app/build/outputs/apk/debug/app-debug.apk`,
or open the project in Android Studio and press Run. To try it, you need two phones.

To publish a release, create a signing key and add a `signingConfig` to `app/build.gradle.kts`.
Never commit the key: `*.jks` and `keystore.properties` are git-ignored.

The APK is about 32 MB because it includes Tor for 4 CPU types. Publishing an App Bundle
(`./gradlew :app:bundleRelease`) lets Google Play deliver only the one each phone needs.

## Testing

```bash
./gradlew :core:test
```

This runs:

- **Crypto**: the Noise implementation against the official
  [cacophony test vectors](https://github.com/haskell-cryptography/cacophony), plus tamper tests.
- **Tor control and SOCKS clients** against scripted fakes. If `tor` is installed
  (`apt install tor` / `brew install tor`), they also run against a **real Tor process** to check
  that Tor accepts our keys and computes the same onion addresses.
- **Encrypted storage**: round trips, crash safety, files can't be swapped or read with another key.
- **Two complete engines** pairing and chatting over a simulated Tor network that enforces client
  authorization: pairing, rejection, expiry, offline sending, restart with saved history,
  strangers being refused, and removing contacts.

**End to end over real Tor.** This starts a private Tor network on your computer (8 relays,
using Tor's testing tool chutney) and runs two engines through it. They pair with a QR invite,
connect through their contacts-only onion addresses and exchange messages. A third Tor user
then checks that strangers are refused with Tor's "missing client authorization" error:

```bash
./tools/testnet/run-e2e.sh        # needs tor, tor-gencert, python3, git
```

**On a device.** The app has been run on an Android 14 emulator, with its built-in Tor connected
to the private test network through `adb reverse`. That run covered setup, Tor start-up, showing
a code, the pairing request with matching safety symbols, chatting both ways with delivery
receipts, and the contact going offline. A second run covered ticks up to green "read",
"N new messages" notifications while the app is in the background, unread counts, messages
waiting (🕓) while the contact is offline and then delivered automatically, recovery after Android
kills the app, and going back online by itself after a reboot. A third run covered "online" and
"last seen today at …" in the chat header, a contact hiding and showing their status (both
directions), the "… is online" notification and opening the chat from it, the chat list with the
latest message, and the app lock (locking when the app is left, messages arriving while locked,
wrong and right PIN). The first run found a bug the JVM
tests could not: on Android,
`InetAddress.getLoopbackAddress()` is `::1`, so Tor (which forwards to `127.0.0.1`) could not
reach the app. The engine now binds to `127.0.0.1` explicitly, and `core` tests run with
`java.net.preferIPv6Addresses=true` to catch this on machines with IPv6.

## Roadmap ideas

- A "panic PIN" that wipes everything
- Voice messages
- Disappearing messages
- Sending photos and files (directly between phones, like messages)
- Group chats (each message sent to each member directly)
- Tor bridges, for countries where Tor is blocked
- An independent security audit and reproducible builds
