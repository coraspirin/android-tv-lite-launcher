# KUTU-TRANSFER.md — build and test record

Third app in the project, after Kutu Mirror and Kutu Home. Asked for on 2026-09-21: a
two-way file transfer reached from a browser, on demand, behind a PIN, carrying APKs,
media and general files.

## Build metadata

| | |
|---|---|
| Package | `local.kutu.transfer` |
| Source | `build/kutu-transfer/` |
| Language | Java, plain platform Views |
| Dependencies | **none** (`dependencies { }`) |
| minSdk / targetSdk / compileSdk | 28 / 28 / 36 |
| Native code | none |
| Release APK | **~41 KB** (Kutu Mirror is 5.65 MB) |
| Keystore | `keys/kutu-transfer.jks`, credentials in `keys/kutu-transfer.credentials.txt` |
| Port | **8787** |

**It builds on Windows.** Kutu Mirror needs WSL because its native half is a POSIX
autotools/CMake build; this app has no native half, so `gradlew assembleRelease` in
`build/kutu-transfer/` is the whole story, same as Kutu Home.

## Where it sits relative to the guide

The guide's "no network permission, no background service, no wakelock" list is
**section 15, and it is scoped to Kutu Home** — the heading names the package. Kutu Mirror,
built under the same guide, is granted `INTERNET`, `WAKE_LOCK`, `FOREGROUND_SERVICE` and
`RECEIVE_BOOT_COMPLETED` by section 12. A LAN service is conditionally permitted, and Kutu
Mirror is the precedent this app follows.

**Kutu Home gained no permission from this.** It starts a component in another package and
is told nothing about it. Its section 15 conformance table is still true.

Permissions taken: `INTERNET`, `FOREGROUND_SERVICE`, `REQUEST_INSTALL_PACKAGES`, and -
added in the second round - `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`.
Deliberately **not** taken, unlike Kutu Mirror: `RECEIVE_BOOT_COMPLETED` and `WAKE_LOCK` —
the app only ever runs because someone asked seconds ago, so it has no boot path and no
session worth holding the CPU awake for. No storage permission at all.

## Design

**The session screen is the session.** Opening `TransferActivity` starts the server;
leaving it stops the server. The port cannot outlive what the human can see.

That is also how **guide 10** is satisfied — *"a bare TCP connection must not open UI"*, a
stop condition at guide line 1231. The causality here only runs the other way: nothing on
the network can reach the Activity, and the Activity is the only thing that creates the
socket. `TransferServer` has no reference to any Activity and no way to start one.

Components follow the guide 11 shim pattern: `TransferControlActivity` and
`TransferStopActivity` are exported, `Theme.NoDisplay`, parameterless, and do one fixed
thing. `TransferActivity` and `TransferService` are not exported.

Kutu Home reaches it through a fourth chip, **Dosya Aktarımı**, which starts the control
shim by explicit `ComponentName`. Unlike Ekran Yansıtma it gets **no panel with a switch**:
the transfer screen already is the session, so a switch would only be a second, lying copy
of that state. `AppRepository.TRANSFER_PKG` keeps the app out of All Apps, beside
`MIRROR_PKG`.

### Security

- **Lifetime**: socket open only during a session, plus a 10-minute idle timeout so a
  session someone walked away from closes itself.
- **PIN**: six digits, fresh per session, shown only on the TV. Correct PIN mints a
  128-bit token in a session cookie; tokens compare in constant time. Eight wrong attempts
  lock the session.
- **Client filter**: non-RFC1918/CGNAT/link-local peers get no reply at all. Cheap
  enforcement of the standing F2 mitigation — the box's firmware froze in 2021 and cannot
  be patched, so it must not be reachable from the internet.
- **Path safety**: every client-supplied name is canonicalised and re-checked against the
  shared folder. Stripping `..` by hand misses encodings and `....//`; the canonical path
  either sits under the folder or it does not.

### Storage, and why it is wider than it was

The first version had one folder: `getExternalFilesDir(null)` →
`/sdcard/Android/data/local.kutu.transfer/files/`. It needs no storage permission on API 28,
and the note here said widening it to the whole card was "left narrow deliberately".

**That answered the wrong half of the job.** The complaint was that opening the page and
typing the PIN showed nothing - *"android tv'deki dosyaları göremiyorum"* - and that an upload
went somewhere unseen: *"dosya gönderdiğimde nereye gittiğini göremiyorum"*. Both are the same
cause. A single fixed folder can only answer "put a file on the box"; it cannot answer "which
of the box's files do I want", and it gives an upload exactly one destination, so there is
nothing to show.

So the model grew a root list:

| Root | Path | Permission | Write |
|---|---|---|---|
| `kutu` | the app's own external files dir | none | always |
| `sd` | the whole internal card | `READ`/`WRITE_EXTERNAL_STORAGE` | while granted |

The permissions are **requested at runtime from the session screen**, the one the human opened
deliberately, and nowhere else. One D-pad press covers both, since they share a group. A
refusal is not a broken app: the root list falls back to `kutu` alone, which is exactly how
this shipped first, and the TV screen says which of the two states it is in.

**Everything the client names is a virtual path**, `<rootId>/<relative>` - `sd/Download/film.mp4`
- never a filesystem path. An empty path is the root list itself. Resolution canonicalises and
then re-checks against that root's canonical path, so `..`, encodings, `....//` and symlinks
all land outside it and are refused identically. The virtual path handed back to the browser is
rebuilt from the canonical one, so what it then displays and sends back is already normalised.

Uploads go to the folder the browser is standing in (`POST /up?p=<dir>`), are refused when the
target is read-only, and are refused when they would take that volume's free space below
256 MB. A folder listing is capped at 2000 entries. **Recursive delete is not offered**: a
directory is removed only when it is already empty, because deleting a tree from a browser is
a much larger promise than this app makes.

### The one piece that had to be written carefully

`Multipart` parses `multipart/form-data` **streaming straight to disk**. The box has under
2 GiB of RAM; buffering an uploaded video — the exact thing this app exists to accept —
would kill it. The parser scans for the boundary in a 64 KB window and holds back only
(boundary length − 1) bytes per pass, because a boundary can straddle two reads.

**The bug that test caught.** `PushbackInputStream.read(b,off,len)` drains its pushback
buffer and *then calls through to the socket* for whatever is still missing. When a whole
small body already sits in pushback, asking for the full 64 KB window blocks forever on
bytes the client finished sending. **A 1.2 GB upload passed; a 45 KB APK hung**, because
large uploads always have more data in flight and hide it. Fixed by never requesting more
than `available()` when anything is buffered. Worth remembering: the large-file test was
not the demanding one.

### The second bug, found by running the page rather than reading it

The browser page is the primary UI and curl never touches its JavaScript. Extracting the
served `<script>` blocks and running them under node against a stub DOM - with a filename
containing quotes, angle brackets and an apostrophe - caught one real defect and confirmed
one non-defect:

- **`encodeURIComponent` does not encode an apostrophe.** The delete button embeds the
  encoded name in a single-quoted JS string, so a file called `Bob's notes.txt` ended the
  string early and produced a broken handler. Now encoded to `%27`, which round-trips
  through the server's URL decoding unchanged. Verified end-to-end afterwards: upload,
  download and delete of a file with an apostrophe in its name all work.
- File names are escaped before they reach `innerHTML`, so an uploaded
  `<img src=x onerror=...>` renders as text rather than as a tag.

### The page became a browser, and the bug class went with it

The first page interpolated the encoded file name into an inline `onclick` string. That is
what broke on an apostrophe. Rebuilding it as a folder browser was a chance to remove the
shape rather than patch it again: **every clickable thing now carries its target in a
`data-go` or `data-rm` attribute**, and one delegated listener reads it back with
`decodeURIComponent`. No path is pasted into a quoted JavaScript string anywhere on the page,
so no character in a file name can close one.

Two things this shape does need care with, both caught by running it:

- **Order of interrogation.** A folder row carries `data-go` *and* contains the Sil button, so
  a listener that asks about navigation first swallows every folder delete. `data-rm` is
  looked for first.
- **Emptiness is a property of the entries, not of the markup.** The "Bu klasör boş" line was
  originally printed when the built HTML was empty, which it never is in a subfolder - the
  "Üst klasör" row is always there. Found by the DOM test, not by reading.

The test harness was upgraded to match. `extract.py` rebuilds the served HTML out of
`Page.java`'s string literals, and `browse-test.js` loads it into **jsdom with a fake box
behind `fetch`** and drives it with real dispatched clicks - navigate into a root, into a
subfolder, back up, delete, upload - asserting on the DOM that results. That is strictly
stronger than the earlier stub: `innerHTML` is really parsed, so escaping is checked by asking
whether an `<img>` element exists, not by matching strings.

## Tests performed

All against the device at `192.168.61.115`, release build, 2026-09-21.

| Test | Result |
|---|---|
| Port 8787 before a session | absent |
| Port during a session | `LISTEN` (state 0A) |
| Port after BACK | listener gone (only TIME_WAIT from closed downloads) |
| **Guide 10 probe regression**: 5 bare connect/close cycles + `GET /`, `/api/list`, `/dl`, `/up` with no session | **0 Activity launches**, foreground app never interrupted, all refused |
| Unauthenticated `/api/list` | 401 |
| Wrong PIN | 401 |
| Correct PIN | 200 + session cookie |
| Previous session's cookie against a new session | 401 |
| Previous session's PIN against a new session | 401 |
| 3 MB round trip | sha256 identical |
| **1.2 GB round trip** | sha256 identical; upload 2m29s, download 1m03s |
| Dalvik / native heap after moving 1.2 GB | **1.6 MB / 2.3 MB** — nothing buffered |
| 45 KB APK, 1-byte file, two files in one request | all saved (after the pushback fix) |
| `Range: bytes=100-199` | 206, correct `Content-Range`, byte-exact slice |
| Path traversal: `../../../../system/build.prop`, `..%2f..`, `/etc/hosts`, `....//` | all 404 |
| Delete from the browser | works |
| APK handoff on the TV | Android's own installer opens, attributed to Kutu Aktarım |
| Idle CPU / foreground services after a session | **0.0 %**, none, no wakelock |
| Kutu Home: 4 chips, shelf centre | still y=540.0 of the 1080-line frame |
| Chip → session → BACK | opens `TransferActivity`, port opens, then closes |
| Page JS under node, stub DOM, hostile filename | no XSS; rows, sizes and free line correct |
| File named `Bob's notes.txt` | uploads, downloads and deletes correctly |
| New chip in dark theme | follows the tokens like every other chip |

Second round, folder browsing, same device and build discipline:

| Test | Result |
|---|---|
| Storage permission prompt on the session screen | appears, D-pad navigable, one press grants both |
| Root list before the grant | `kutu` only; TV screen says why |
| Root list after the grant | `kutu` + `sd`, card free space correct |
| Browse `sd`, `sd/Android/data`, five levels deep | correct entries, crumbs and parent at each level |
| Traversal, new path model: `sd/../../../system`, `kutu/../../../../data`, `/etc`, `etc`, `root`, `sd/./../../system/build.prop`, bare `"` | all 404 |
| Download traversal: `sd/../../../system/build.prop`, `kutu/../../../../../etc/hosts`, `sd/Android/../../../init.rc` | all 404 |
| Upload to `sd/Download` | lands in `/sdcard/Download/`, sha256 identical on the way back |
| Upload with no `p` | falls back to the `kutu` folder, as before |
| `Range: bytes=100-199` on a path outside the app folder | 206, 100 bytes |
| Delete a non-empty directory | 409, directory intact |
| Delete an empty directory | 200, gone |
| Delete a file with an apostrophe and `#` in its name | 200, gone |
| APK opened from the TV list | installer opens - `SharedFileProvider` works on the new virtual path |
| Old session's cookie against a new session | 401 |
| **Guide 10 probe regression, re-run against the new routes**: 5 bare connect/close + held idle connection + `GET /`, `/api/list`, `/dl`, `/up`, `/rm` | **0 Activity launches**, all refused, Kutu Home never left the foreground |
| Port after BACK | no `LISTEN`, only TIME_WAIT from closed downloads |
| Page under jsdom with hostile names (`<img src=x onerror=...>`, `it's & <b>bold</b> #1`) | 19 checks pass: no live element injected, every path round-trips exactly |

### Not yet done

- **APK install needs one manual grant.** `REQUEST_INSTALL_PACKAGES` lets the app *ask*;
  Android still requires the per-app "Install unknown apps" toggle, which defaults to off.
  The installer opens and says so. Left for the human to decide:
  Settings → Apps → Kutu Aktarım → Install unknown apps.
- **Confirmed in a real browser, on the reading half only.** The user opened the page,
  entered the PIN, browsed between folders and downloaded the files they wanted. Navigation,
  the crumb trail, the listing and download therefore no longer rest on jsdom alone.
  **Drag and drop, the file picker and the upload progress bar are still unconfirmed** -
  every upload so far has come from curl or from jsdom's stubbed XHR, neither of which
  touches the parts of the page a real rendering engine supplies.
- **No folder that refuses to be read was found to test with.** With `READ_EXTERNAL_STORAGE`
  granted on API 28 everything under the card listed, so the `denied` branch of the listing -
  the one that says "Bu klasör okunamıyor" - has not been seen on the device.
- **No `mkdir`.** Files can only be uploaded into folders that already exist. Deliberate for
  now, and the obvious next thing if it turns out to be wanted.
- **Media handoff not exercised** — the `ACTION_VIEW` path is the same one the APK test
  proved, but no video was opened through it.
- **Non-private peer rejection not exercised** — it cannot be triggered from the LAN.
- **Upload-with-full-disk not exercised** — the free-space guard is untested against a
  genuinely full `/data`.

## Standing risks this adds

- **A second app on the box can install APKs.** `SECURITY-REVIEW.md` F4 logs
  `com.tvonline.filewizard` for exactly this; Kutu Transfer makes it two. Mitigations: the
  install is always a deliberate press on the TV, never initiated by anything arriving over
  the network, and Android's own confirmation still gates it. Dropping the permission is a
  one-line manifest change if the File Wizard route is preferred.
- **A writable LAN port exists during a session, and it now reaches the whole card.** This is
  the widest thing any app in this project holds. It was asked for explicitly, and what bounds
  it is unchanged - the PIN, the idle timeout, the private-peer filter, and the socket existing
  only while the session screen is on the TV - so those four are doing more work than they were
  when the scope was one folder. The storage permissions are runtime-granted from that screen,
  so revoking them in Settings puts the app back to its original single-folder scope without
  reinstalling.
- **Port 8787 must be added to the attributed port table** in `SECURITY-REVIEW.md` with its
  owning uid when that review is next redone.
- `FileProvider` was deliberately removed from Kutu Mirror because nothing was saved to
  disk. This app saves, so an equivalent came back — written by hand, since the app has no
  dependencies, scoped to one directory and read-only.
