# Privacy Policy — Extrumz

**Last updated: 21 October 2026**

Extrumz is an unofficial, open-source Android music player. It turns the audio
files in your own Telegram chats and channels into a music library. It is
published under the GPL-3.0 licence, its source is public, and it is not
affiliated with, endorsed by, or supported by Telegram FZ-LLC.

## The short version

**Extrumz has no server of its own, and it collects nothing.** Your Telegram
session never leaves your phone, and no information about you is sent to the
project's maintainer.

## What leaves your device, and where it goes

| | Where it goes | Why |
|---|---|---|
| Telegram messages, chats, channels, and audio files | **Telegram's own servers**, directly from your device | That is what the app does — it is a Telegram client. The connection is MTProto, from TDLib, straight to Telegram. It does not pass through any server belonging to this project. |
| Your Telegram session and the library database | **Your device only** | TDLib stores the session and the local database in the app's private storage. The app does not run background servers, does not read your contacts, and does not upload your library. |
| Album cover images and lyrics | **Third-party lyrics and artwork services** | When you open a song, the app asks a third-party service for its cover art and lyrics. **That request necessarily includes the song title, the artist name, and your IP address.** Those services are not part of this project and have their own policies. |
| A proxy, if you configure one | **The proxy you chose** | If you set a proxy, Telegram traffic and the requests above go through it, and the proxy operator sees the connection. If you set none, they go out directly. |
| Nothing else | — | No analytics, no crash reports sent to the maintainer, no device identifiers, no usage tracking. |

## What is stored on your device

```
the Telegram session and its database   (TDLib, in the app's private storage)
your library: audio files, covers, playlists, listening history, settings
```

All of it is deleted when you clear the app's data or uninstall it. The app
does not participate in Android's cloud backup, so the session database is not
copied to your Google account.

## Your choices

- Play anything from your own chats, or turn the library off.
- Use a proxy, or go direct.
- Uninstall, and everything goes with it.

## The Telegram API

Extrumz uses the official Telegram API through TDLib, under Telegram's own
[API Terms of Service](https://core.telegram.org/api/terms) and
[Security Guidelines](https://core.telegram.org/mtproto/security_guidelines).
Those terms are what require every published client to carry its own API
credentials, and they are also what govern how the API may be used.
Extrumz is an unofficial client and says so.

## Contact

Questions or a security problem: open an issue on
[github.com/P4rsw/Extrumz](https://github.com/P4rsw/Extrumz/issues).

---

This document is part of the source repository and is distributed under the
same licence as the project. See [LICENSE](LICENSE).
