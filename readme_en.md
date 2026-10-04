# WenkuKeeper (文库管家) — LightNovelReader Plugin

Brings [wenku8](https://www.wenku8.net/) into
[LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader), backs up your local data to
**your own** WebDAV space or GitHub repository, and migrates a bookshelf between data sources.

Host plugin API version: **4** (`ApiMetadata.API_VERSION = 4`).

[简体中文](readme.md) | **English**

> **Note on completeness.** This English README covers the features and usage. The Chinese
> [`readme.md`](readme.md) is authoritative and additionally contains the on-device verification
> results, the development pitfalls that only surface on a real device, and the full list of
> known limitations. Read it before debugging anything.

---

## What it does

### 1. Sync novel data from wenku8

Registers a `WebBookDataSource` named `WenkuKeeper` (identifier `wenkukeeper:wenku8`):

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

### 2. Upload local data to the cloud (WebDAV or GitHub)

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

### 3. Reading log (READING_LOG.md)

Alongside each snapshot the plugin also writes a **human-readable** `READING_LOG.md`:

```markdown
# Reading Log

Generated 2026-10-03 21:40 | 12 books | 5 finished | 47 h 12 m total

| Title | Progress | Time read | Last read | Shelf |
| --- | --- | --- | --- | --- |
| Book A | 100% | 6 h 3 m | 2026-10-02 23:11 | Default |
```

It renders directly on GitHub and reads fine as plain text. Book titles come from the host's local
cache first, falling back to a remote lookup, then to the raw book id.

> Why reading records instead of novel text: the content is copyrighted, exporting it would mean
> hundreds of chapter requests per long novel, and it would produce a huge number of commits. Your
> own reading history has none of those problems.

### 4. Shelf migration

**The target is always the data source currently active in the host; the source is any book already
on your shelf.** It answers "I want to move to another site".

Four steps, all on the plugin page:

| Step | What it does | Network |
| --- | --- | --- |
| **1. Export shelf** | Writes a plan listing the books; scope can be all shelves or one | No |
| **2. Match** | Searches the active source for each book, fetches details, scores by title and author | Yes |
| **3. Accept high confidence** | Or accept / skip / re-match individually; each candidate shows its tier, score and reasoning | No |
| **4. Import to a new shelf** | Creates a **new shelf** in the target source (default name `迁移导入`) | Yes |

Key behaviours:

- **Your original shelf is never touched.** The import only creates a new shelf in the target source.
- **Scoring**: title and author are both scored; **≥ 80 counts as high confidence** (one-tap accept),
  **≥ 50 medium**. A missing author on one side only adds a small amount, so a book is not treated
  as different merely because the other site omits the author.
- **Simplified/Traditional normalisation**: source and target are often different variants
  (`wenku8.net` → `tw.linovelib.com`), so both sides are converted to Traditional before comparing.
- **The plan is persisted**, so an interrupted run resumes: books already searched are skipped.
- **Reading progress migrates only the parts that do not depend on chapter structure** — overall
  progress, total time and last-read time take the larger value. Chapter-level maps and the
  last-read chapter keep the target book's own values, because chapter ids differ between sites.

---

## Install

### Prebuilt plugin

1. Obtain `plugin-debug.apk.lnrp`;
2. In LightNovelReader open *Settings → Plugin management → Install from file* and pick the
   `.lnrp` file (renaming it back to `.apk` and installing it with the system installer also works);
3. Enable **WenkuKeeper**;
4. Switch the active data source to **WenkuKeeper** under *Settings → Data source*.

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
