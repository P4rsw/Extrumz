<div align="center">

<img src="Logo.png" alt="Extrumz" height="72" />

# Extrumz

**Your Telegram, as a music library.**

[فارسی](README.fa.md) · [Features](#what-it-does) · [The Proxy](#the-part-that-matters) · [Building](#build-it) · [Architecture](#how-it-is-put-together) · [Licence](#licence)

</div>

<img src="social-preview.png" alt="Extrumz — your Telegram, as a music library" width="100%" />

---

## The situation

Telegram already holds your music. It is sitting in channels, in saved messages, in a group where a friend shared a rare track years ago. It is yours, it is already there, and until now there was no proper way to press play on it.

Extrumz is built for exactly that. It indexes the audio in the chats and channels you choose, turning a messy messaging stream into a full-fledged music player: queue management, custom playlists, liked tracks, playback history, instant search, word-synced lyrics, album artwork, and a now-playing screen designed to look great while playing.

Nothing is uploaded. Nothing is copied to external servers. The audio files never leave Telegram's servers until they stream to your phone — they are just finally playable the way music should be.

---

## What it does

| Feature | Details |
|---|---|
| **📥 Fast Indexing** | Scans chats and channels for audio files with real-time visible progress, resumable state, and a bounded wait per page so one slow chat never stalls the rest. |
| **▶️ Real Playback** | Background audio over an Android MediaSession with lock-screen controls, gapless playback, and smooth crossfade. |
| **📚 Library Organisation** | Playlists, liked songs, listening history, and instant full-text search across your indexed library, sortable by date, title, or artist. |
| **🎤 Synced Lyrics** | Word-by-word and line-by-line synchronized lyrics from multiple providers, with an in-app manual selector when automatic matching guesses wrong. |
| **🎨 Dynamic Artwork** | Telegram native thumbnails first, high-resolution external databases as fallback, and a dynamic mesh color palette extracted directly from cover art. |
| **🌍 First-Class Persian & RTL** | Authentic right-to-left layout and typography tailored for Persian, alongside standard English. |
| **🛡 Built-in Proxy** | A dedicated, full-screen proxy manager right on the welcome screen — not hidden away in settings. |
| **🎧 Local Audio** | Seamlessly play offline local audio files right next to your indexed Telegram tracks. |

---

## The part that matters

**If Telegram is unreachable from where you are, none of the above matters.**

In heavily filtered environments, a proxy cannot be a hidden toggle buried three submenus deep. Extrumz places proxy management directly on the welcome screen right beside the login buttons, built around how people actually connect:

- **MTProto, SOCKS5, and HTTP:** Support for all three major proxy protocols used with Telegram.
- **A managed list, not a single input:** Proxies are saved, named, and easily toggled. Adding a new proxy never overwrites your existing backup.
- **Tested before trusted:** Every proxy is ping-tested directly against Telegram's data centers — specifically the DC your account is assigned to — displaying latency alongside the direct connection. You see concrete numbers instead of guessing.
- **Persistent visual shield:** An active shield indicator remains visible on the home screen whenever a proxy is running, avoiding forgotten active connections.

> [!NOTE]
> **The honest limit:** MTProto is Telegram's proprietary protocol; it tunnels Telegram traffic exclusively (sessions, messages, audio streams, and channel thumbnails). Cover art fetched from external providers (like Deezer or iTunes) and lyrics queried over HTTP travel over direct network connections. If those external services are restricted on your network, run a system-wide VPN alongside Extrumz.

---

## Build it

```bash
git clone https://github.com/P4rsw/Extrumz.git
cd Extrumz
JAVA_HOME=/usr/lib/jvm/java-21-temurin ./gradlew :app:assembleProdDebug
```

### Telegram API Credentials

**You must supply your own Telegram `api_id` and `api_hash`.** Telegram requires every client application to identify itself with credentials registered specifically for that application. Shipping shared or well-known credentials — such as those copied from public tutorials or Telegram Desktop — leads to aggressive rate limits and account/app bans. When that happens, **users suffer the consequences, not the developer.**

1. Visit [my.telegram.org](https://my.telegram.org) and log in with your Telegram phone number.
2. Navigate to **API development tools** and create an application (any title, platform *Android*).
3. Add your credentials to `local.properties` in the project root:

```properties
TG_API_ID=1234567
TG_API_HASH=0123456789abcdef0123456789abcdef
```

`local.properties` is ignored by git. Keep it private and never commit it.

- **Run unit tests:** `./gradlew :app:testProdDebugUnitTest`
- **Build signed release:** `./tools/make-keystore.sh` (one-time setup), then `./release.sh`

---

## How it is put together

```
app/        The Android application — Kotlin, Jetpack Compose, Room, TDLib
tools/      Keystore generation and release verification automation
docs/       the lyrics translation specification
```

One module, Material 3, edge to edge. `R8` shrinks release builds, and every
library that reaches for a class by name — TDLib's JNI — is kept whole in
`proguard-rules.pro`.

**One thing worth knowing before you fork this.** A release build is not a
debug build. `R8` only runs in release, and it can remove something the debug
build never would. Two release-only failures left this repository before they
were caught — not by a test, because tests do not run `R8`, but by installing
a release APK on a phone and using it. Do that step.

---

## What is not finished

This is used daily and it works, but it is early, and it is more honest to
say so than to let a reader find out:

- **Typography needs a free alternative.** Eleven megabytes of Apple's
  proprietary SF Pro typeface are bundled — a third of the APK — and San
  Francisco's licence does not permit embedding it in Android software.
- **Cover art and lyrics do not travel through an MTProto proxy.** They take
  the ordinary route, so a network that blocks those blocks them too.
- **Translation is Persian and English only**, and the Persian is the one that
  gets the attention.

---

## Licence

**GPL-3.0.** See [LICENSE](LICENSE) for the full text.

Extrumz is a fork of [**BitChord**](https://github.com/kushagrasinghx/BitChord) by **Kushagra Singh** and contributors — originally a YouTube Music client — completely re-architected around Telegram as its audio backend. The core playback foundation, audio pipeline, and foundational visual components are their work; the Telegram integration, proxy engine, library scanner, and Persian RTL implementation were built for this fork. Under the terms of the GNU General Public License v3.0, that lineage remains preserved and acknowledged.

Lyrics animations are credited to [binimum/am-lyrics](https://github.com/binimum/am-lyrics).
