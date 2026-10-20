# PrivateChat security design

This document describes how PrivateChat protects its users, for developers and reviewers.
Source references are relative to the repository root.

## Goals

- **No infrastructure.** No servers, relays or databases operated by anyone. Two phones talk
  to each other directly through Tor.
- **No identifiers.** No phone number, email or username. A user is a key pair made on the phone.
- **Only people you met can reach you.** Contacts are added only by scanning a QR code in person.
  To everyone else, your onion address does not even resolve.
- **Confidentiality and forward secrecy** for messages in transit, and encryption at rest.
- **Minimal metadata.** Tor hides IP addresses. Padding hides message sizes. Nothing is logged,
  backed up or shown in notifications.

## Non-goals and limits

- Messages are only delivered while both phones are online. There is no store-and-forward.
- A compromised phone (malware, root, a forensic tool on an unlocked device) defeats everything.
- Contacts can see when you are online, because they can connect to you.
- An adversary watching both ends of the Tor network at once may correlate traffic timing.
  This is a general Tor limitation.
- No independent audit yet.

## Keys

| Key | Type | Purpose | Lifetime |
| --- | --- | --- | --- |
| Static key | X25519 | Noise identity; contacts pin it | Permanent (until wipe) |
| Onion key | Ed25519 seed | The user's onion service address | Permanent (until wipe) |
| Invite static key | X25519 | Noise identity of one QR code | One invite (≤5 min) |
| Invite onion key | Ed25519 | Address of one QR code | One invite (≤5 min) |
| Client-auth keys | X25519 | Tor v3 client authorization, one pair per contact and direction | Per contact |
| Session keys | ChaCha20-Poly1305 | Noise transport keys | One connection |
| Vault data key | AES-256 | Encrypts files | Permanent, wrapped by the Keystore key |
| Vault wrapping key | AES-256-GCM in Android Keystore | Encrypts the data key; never extractable | Permanent |

The onion key is generated locally. We pass Tor the 64-byte expanded secret key
(`clamp(SHA-512(seed))`) in `ADD_ONION ED25519-V3:…` and check that the address Tor reports
equals the one we computed (`core/.../tor/Onion.kt`, `core/.../node/TorAdapters.kt`).
`RealTorIntegrationTest` verifies this against a real Tor binary.

## Transport: Noise IK

All peer traffic uses `Noise_IK_25519_ChaChaPoly_SHA256` (`core/.../crypto/Noise.kt`), an
implementation verified against the cacophony test vectors. IK gives mutual authentication and
forward secrecy in one round trip. The initiator must already know the responder's static key.

- **Prologues** separate the two uses: `PrivateChat/1 contact` and `PrivateChat/1 invite`.
  A handshake meant for one cannot complete as the other.
- **Silent refusal.** The responder learns the initiator's static key from message 1. If it is not
  a contact, the responder closes the connection without replying (`Handshake.respond`). An
  unknown caller learns nothing, not even whether the key it used was right.
- **Empty handshake payloads.** IK message 1 payloads are not forward secret, so all application
  data is sent after the handshake.
- **Framing and padding.** Frames carry a 2-byte length. Every plaintext gets a 2-byte length
  prefix and is zero-padded to a multiple of 256 bytes before encryption (`SecureChannel.kt`).
- **Keep-alive** pings every 30 s. A peer silent for 90 s is considered offline.
- **Duplicate connections.** When both phones dial at once, both keep the connection initiated by
  the phone with the lexicographically smaller static key. Messages that were not yet
  acknowledged on a dropped connection are resent on the surviving one. Receivers de-duplicate
  by message ID.

## Tor usage

- Tor runs inside the app (tor-android). We open a second control connection over its private
  Unix socket and use `ADD_ONION`, `DEL_ONION`, `ONION_CLIENT_AUTH_ADD/REMOVE` and `GETINFO`
  (`core/.../tor/TorControlClient.kt`). Every argument is validated against a strict character
  set, and onion addresses received from peers are checksum-verified and re-encoded before use.
- Outgoing connections go through Tor's SOCKS port with the host name sent unresolved, so there
  are no DNS lookups (`core/.../net/Socks5.kt`).
- **Contacts-only onion service.** The permanent onion service is published with `Flags=V3Auth`
  and one `ClientAuthV3` key per contact. Without a matching private key, a client cannot decrypt
  the descriptor, so the service is unreachable and indistinguishable from offline. Tor reports
  this as SOCKS error F4, which `TorTestnetEndToEndTest` checks. With zero contacts the service
  is not published at all. Adding or removing a contact re-publishes it with the new key list.
- Onion services and client-auth keys are added at runtime. Tor never writes them to disk; they
  live only in our encrypted vault.

## Pairing

Alice shows a code; Bob scans it.

1. Alice creates a fresh invite onion key and invite static key, and publishes the invite onion
   service (no client authorization). The QR code is
   `base32(version || invite-onion-pubkey || invite-static-pubkey || SHA-256(label || …)[0:4])`
   (`core/.../invite/Invite.kt`). It holds no name, no permanent key and no permanent address.
2. Bob connects through Tor and runs Noise IK as initiator. He authenticates Alice's *invite* key
   and proves possession of his *permanent* static key.
3. Bob sends `PairRequest{name, his permanent onion address, x25519 private key that lets Alice
   reach his onion}`.
4. Both phones show 5 symbols derived from the Noise handshake hash (30 bits). Alice compares
   them with Bob's screen. Someone who photographed the code and raced Bob would produce a
   different handshake hash, and the symbols would not match.
5. When Alice accepts, she sends `PairAccept{name, her onion address, a client-auth private key
   for Bob, her permanent static key}`. This is trustworthy because the channel is authenticated
   to the invite key only Alice holds.
6. Both sides store the contact, register the client-auth key with Tor and re-publish their
   onion service. The invite onion service is deleted. The invite also expires after 5 minutes
   and is single-use.

Every connection after that uses Noise IK between the permanent static keys over the
contacts-only onion services.

## Storage

- Files live in `noBackupFilesDir/vault`. `identity.bin` and `contacts.bin` are sealed blobs
  written atomically (temp file + fsync + rename). Each conversation is an append-only log of
  individually sealed records, so a crash mid-write loses at most the last record
  (`core/.../store/SecureStore.kt`).
- Sealing is AES-256-GCM with a random IV and a purpose label as associated data
  (`identity`, `contacts`, `messages:<contactId>`), so files cannot be swapped.
- The data key is wrapped by a non-exportable Android Keystore key (`app/.../security/VaultKey.kt`).
  The Keystore key is not bound to device unlock, so messages can still be received while the
  screen is locked. "Delete everything" removes the files and the Keystore key.

## Android hardening

- `FLAG_SECURE` on the activity: no screenshots or screen recording, and the app is blank in recent apps.
- `allowBackup=false` plus data-extraction rules that exclude every domain.
- Notifications contain no names or message text.
- Release builds log nothing. Debug logs never include names, addresses or message contents.
- No analytics, crash reporting, advertising, Firebase or Google Play services. QR decoding is
  ZXing on the device.
- The only network permission is used by the built-in Tor client.

## Ideas for hardening next

- Periodic rekeying within long-lived connections (Noise `Rekey()`), for post-compromise
  security within a session.
- App lock (PIN or biometrics) and a duress PIN that wipes the vault.
- Optionally bind the Keystore key to user authentication.
- Tor `ExtendedErrors` in the app, to tell users *why* a contact can't be reached.
- Reproducible builds and an external audit.
