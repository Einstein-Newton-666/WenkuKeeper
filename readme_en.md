# Wenku8Plus — LightNovelReader Plugin

Brings [wenku8](https://www.wenku8.net/) into
[LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader), and backs up your local
data to **your own** WebDAV space.

Host plugin API version: **4** (`ApiMetadata.API_VERSION = 4`).

[简体中文](readme.md) | **English**

---

## What it does

### 1. Sync novel data from wenku8

Registers a `WebBookDataSource` named `Wenku8Plus` (identifier `wenku8plus:wenku8_plus`):

| Capability | Details |
| --- | --- |
| Book details | title, subtitle, cover, author, description, tags, imprint, word count, last update, completion |
| Volumes & index | chapter tree grouped by volume |
| Chapter content | paragraphs and illustrations, with previous/next chapter |
| Search | by title and by author, multi-page, with single-result shortcut |
| Explore | home recommendations, full novel list, four rankings, completed-only, browse by tag |
| Tag navigation | tapping a wenku8 tag opens the matching explore page |

Implementation notes:

- **No bundled credentials.** Everything readable without an account works; VIP chapters
  already require a login on the site, and this plugin will not redistribute someone's account.
- **Decoded as GB18030.** The site declares GBK but transmits characters such as `•` and `〜`
  as GB18030-only four-byte sequences, which mojibake under the declared charset.
- **Mirror failover.** `wenku8.net` / `wenku8.cc` / `wenku8.com` are probed in order.
- **Rate limiting.** The site rejects searches issued less than 5 s apart; the plugin waits and
  retries the same page.
- **Image hotlink protection.** A `Referer` is supplied through `imageHeader`.

### 2. Upload local data to the cloud (WebDAV)

Configure a WebDAV endpoint on the plugin page, then:

- **Upload now** — packs bookshelves, reading progress and plugin settings into one snapshot;
- **Restore latest** — downloads the newest snapshot and merges it back;
- **Test connection**;
- optional **auto upload / auto restore**, plus a **snapshot retention count**.

Snapshot files are self-describing, named like `lnr-20261003-201500.json.gz`:

```
"LNRW" (4-byte magic) + format version (1 byte) + gzip(JSON)
```

Restore only ever adds or advances state: reading progress takes the larger value, per-chapter
history and current position take the max, bookshelf book ids are unioned, and `StringList` user
data is unioned by comma-separated elements (matching the host's own merge semantics). A restore
never reduces local progress.

**Your WebDAV username and password are never written into a snapshot.** Snapshots are stored in
cleartext (gzip only, no encryption), so including the credentials would put a second plaintext
copy of your password on the server — and restoring on another device would silently switch your
account. Neither is collected nor applied; enter the credentials once per device.

> **Backup scope — please read**
>
> The plugin API exposes enumerable access to **bookshelves, reading data**, and the plugin's own
> settings only. No public API enumerates arbitrary `user_data` rows, cached book information, or
> cached chapter content. The cloud snapshot therefore **excludes** the host's full settings,
> downloaded chapter text, and reading statistics.
>
> For a **complete** database backup use the host's own *Settings → Data → Export*, whose file can
> be restored with *Import*. This plugin targets **automated off-device backup and cross-device
> sync of the bookshelf and reading progress**.

---

## Install

### Prebuilt plugin

1. Obtain `plugin-debug.apk.lnrp`;
2. In LightNovelReader open *Settings → Plugin management → Install from file* and pick the
   `.lnrp` file (renaming it back to `.apk` and installing it with the system installer also works);
3. Enable **Wenku8Plus**;
4. Switch the active data source to **Wenku8Plus** under *Settings → Data source*.

### Build it yourself

Requires **JDK 17** and the Android SDK (`compileSdk 37`, `targetSdk 37`, `minSdk 24`).

```bash
./gradlew :plugin:assembleDebug
# -> plugin/build/outputs/apk/debug/plugin-debug.apk.lnrp

./gradlew runReleaseHostWithDebugPlugin   # build, install into the release host, restart it
```

Artifact size is about 15.9 MB for the debug variant; minification and resource shrinking are off
there, so a release build is considerably smaller.

`io.nightfish.lightnovelreader:api:0.4-SNAPSHOT` and `:compiler:0.4-SNAPSHOT` come from
`https://maven.nariko.org/release`, already configured in `settings.gradle.kts`.

---

## Usage

### Cloud backup (Nutstore example)

1. Enable third-party app management in Nutstore and create an **app password**;
2. On the plugin page → Cloud sync:
   - Server URL: `https://dav.jianguoyun.com/dav/`
   - Username: your Nutstore account
   - Password: the app password
   - Remote folder: `LightNovelReader` (created automatically)
3. Tap *Test connection*, then *Upload now*.

Nextcloud, Synology, Alist and self-hosted WebDAV work the same way. Both `Basic` and `Digest`
authentication are supported.

> ⚠️ The password is stored **unencrypted** in the host database. Always use an app-specific
> password or token, never your primary account password.

---

## Known limitations

- **Limited backup scope** — see above. Use the host's own export for a full database backup.
- **VIP chapters** — no credentials are bundled, so only publicly readable chapters load.
- **Snapshot format is plugin-private** — `lnr-*.json.gz` is not accepted by the host's *Import*,
  and vice versa. This is deliberate: the host's format depends on non-public internal entities.
- **Site structure changes** — parsing targets wenku8's current HTML. A redesign surfaces a clear
  "parse error" instead of crashing.
- **Cloudflare challenge** — wenku8 sits behind Cloudflare. The plugin sends a normal desktop
  browser User-Agent but does not execute JavaScript, so while it works from typical consumer
  networks (the host's own built-in source proves real devices get through), a 403 is returned if
  Cloudflare serves the "Just a moment..." interstitial to your egress IP. That is not something
  the plugin can bypass; change networks if it happens.

  > Maintainer's note: the machine this plugin was developed on is blocked by that challenge, so
  > **the parsing selectors were never verified against the live site** — they were compared
  > line-by-line with the host's built-in wenku8 implementation instead. On first real use, open a
  > book and confirm details, index and chapter text all load.

## Disclaimer

This plugin is only a client for wenku8's public pages. It stores, redistributes and republishes
no novel content. All content belongs to its respective authors and publishers. Use it in
compliance with your local law and the site's terms of service.

## License

MIT
