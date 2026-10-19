<div align="center">

<img src="Logo.png" alt="Extrumz" height="72" />

# Extrumz

**Your Telegram, as a music library.**

[فارسی](README.fa.md) · [Build it](#build-it) · [The proxy](#the-part-that-matters) · [Licence](#licence)

</div>



---

## The situation

Telegram already holds your music. It is sitting in channels, in saved
messages, in a group a friend sent it to years ago. It is yours, it is
already there, and there is no way to press play on it.

Extrumz is a player for that. It indexes the audio in the chats and channels
you point it at, and gives you a queue, playlists, liked tracks, history,
search, lyrics, album art, and a now-playing screen worth leaving open.

Nothing is uploaded. Nothing is copied. The files never move — they are just
finally playable.

---

## What it does

| | |
|---|---|
| **📥 Indexes** | Scans chats and channels for audio, with progress you can watch, resumable, and a bounded wait per page so one slow chat cannot stall the rest |
| **▶️ Plays** | Background playback over a real media session, gapless, with crossfade |
| **📚 Organises** | Playlists, liked tracks, history, search across everything indexed, sortable six ways |
| **🎤 Lyrics** | Word-synced, from several providers, with a manual picker when the automatic one guesses wrong |
| **🎨 Artwork** | Telegram's own thumbnails first, external databases as a fallback, and a palette pulled from the cover for the now-playing screen |
| **🌍 Persian** | Real right-to-left layout, not a mirrored afterthought. English too. |
| **🛡 Proxy** | A screen, not a buried setting — see below |
| **🎧 Local files** | Your own music, alongside the Telegram library |

---

## The part that matters

**If Telegram is not reachable from where you are, none of the above works.**

So the proxy is not a setting buried three menus deep. It is a screen, it is
offered on the welcome page next to the login buttons, and it behaves the
way a proxy should:

- **MTProto, SOCKS5 and HTTP.**
- **A list, not a field.** Proxies are kept, named, and switchable. Adding a
  second one does not delete the first.
- **Tested before trusted.** A proxy is checked against Telegram itself, on
  the datacentre your account actually uses, and the result sits next to the
  direct connection — so *"does this help?"* has an answer instead of a
  feeling.
- **A shield on the home screen** while one is active, because a proxy that
  is on and forgotten is how people get stuck.

> **The honest limit:** MTProto is a Telegram protocol, so it carries
> Telegram — sessions, chats, audio, thumbnails. Cover art fetched from
> Deezer or iTunes, and lyrics fetched over HTTP, take the ordinary route
> and are not tunnelled. If those matter where you are, run a system-wide
> VPN alongside it.

---

## Build it

```bash
git clone https://github.com/P4rsw/Extrumz.git
cd Extrumz
JAVA_HOME=/usr/lib/jvm/java-21-temurin ./gradlew :app:assembleProdDebug
```

**You need your own Telegram `api_id` and `api_hash` first.** Telegram
requires every application to identify itself with credentials registered to
*that* application. Shipping someone else's — including the well-known ones
that are in every fork and every tutorial — gets the app rate-limited and
then blocked, and the people who feel that are the users, not the developer.

1. [my.telegram.org](https://my.telegram.org) → sign in with your phone number
2. **API development tools** → create an application (any name, platform *Android*)
3. Put the two values in `local.properties`:

```properties
TG_API_ID=1234567
TG_API_HASH=0123456789abcdef0123456789abcdef
```

`local.properties` is gitignored. Keep it that way.

**Tests:** `./gradlew :app:testProdDebugUnitTest`
**Signed release:** `./tools/make-keystore.sh` once, then `./release.sh`

---

## How it is put together

```
app/        the Android application — Kotlin, Compose, Room, TDLib
backend/    the lyrics backend that LyricsPlus queries, in Go
native/     the audio analysis the beat-matched transitions run on, in C++
tools/      keystore creation, and the setup script for a release
```

Single module, Material 3, edge to edge. `R8` in release builds, and every
library that reaches for a class by name — TDLib's JNI, ONNX's JNI,
protobuf-lite — is kept whole in `proguard-rules.pro`.

**One thing worth knowing:** the release build is not the debug build. `R8`
only runs in release, and it can remove something the debug build never
would. A release-only failure found its way out of this repository twice
before it was caught by installing a release APK on a phone and using it —
not by a test, because tests do not run `R8`. If you fork this, do that step.

---

## What is not finished

This is used daily and it works, but it is early, and it is more honest to
say so than to let a reader find out:

- The beat-matched transitions and the vocal unmixing are inherited from the
  original project and carry a lot of weight in the APK.
- Cover art and lyrics do not travel through an MTProto proxy.
- Translation is Persian and English only, and the Persian is the one that
  gets attention.

---

## Licence

**GPL-3.0.** See [LICENSE](LICENSE).

Extrumz is a fork of [**BitChord**](https://github.com/kushagrasinghx/BitChord)
by **Kushagra Singh** and its contributors — a YouTube Music client — rebuilt
around Telegram as the source. The playback engine, much of the UI language
and a great deal of the code are theirs; the Telegram layer, the proxy
handling, the library scanner and the right-to-left work belong to this fork.
Under the GPL that lineage stays visible, and it should.

Lyrics animation is credited to [binimum/am-lyrics](https://github.com/binimum/am-lyrics).
