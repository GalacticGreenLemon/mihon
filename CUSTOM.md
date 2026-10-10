# Remon

Remon (レモン, "lemon") is a personal build of [Mihon](https://github.com/mihonapp/mihon). Changes compared to upstream:

- **Google Drive backups.** After each automatic backup, the file is uploaded to a `Remon backups` folder in
  Google Drive and only the newest few are kept there (10 by default). Set it up in
  *Settings → Data and storage → Google Drive*, which also has a "Back up to Google Drive now" button.
  The app only gets the `drive.file` permission, so it can see the files it uploaded and nothing else in your Drive.
- **Optional: extensions and downloaded chapters.** Two more switches in the same section. After each automatic backup,
  a separate job uploads only what's missing on Drive:
  - `Remon backups/Extensions/<package>_v<version>.apk`, with only the newest version of each kept.
  - `Remon backups/Downloads/<source>/<manga>/<chapter>.cbz`, matching the local downloads folder. Chapters saved as
    image folders are zipped into CBZ files on the way up. Chapters you delete on the phone (including ones
    deleted automatically after reading) are deleted from Drive too, unless you turn off
    *Delete chapters from Drive when deleted here* to keep a permanent library. Only chapters this phone uploaded
    or had before count, so a new phone or a new storage folder never wipes Drive. By default this only runs on Wi-Fi.
- **Restore from Drive inside the app**, in the same section:
  - *Restore backup from Google Drive*: pick one of the uploaded backups, then the usual restore screen opens.
  - *Install extensions from Google Drive*: installs the uploaded extensions that aren't on the device.
    Android may ask you to confirm each one. Restore a backup first, so the extension repos are back and the
    extensions are trusted.
  - *Download chapters from Google Drive*: downloads the chapters that aren't on the device in the background.
- **Keiyoushi extension store added by default.** On first start (or the first start with internet),
  `https://github.com/keiyoushi/extensions/raw/repo/index.pb` is added to *Settings → Browse → Extension stores*.
  If you remove it, it stays removed.
- **Remon theme and icon.** A red-on-black theme (red on white in light mode) is the default, and the launcher icon
  is れ in a red ring, in the style of Mihon's み icon.
- **Named Remon, package name `app.remon`**, so it installs next to the official app instead of clashing with it.
  To move your library over, create a backup in the official app and restore it in this one.
- **No telemetry and no in-app updater.** The updater would offer official releases, which can't install over this build.

### Setting up a new phone

Install Remon, pick a storage folder, turn on the Google Drive switch with the same Google account, then run
the three restore actions in order: backup, extensions, chapters.

## One-time setup

### 1. Signing key

Android only updates an app if every version is signed with the same key, and Google uses the key's fingerprint to
recognise the app. So the GitHub build needs your own key, stored as repository secrets.

```sh
keytool -genkeypair -v -keystore personal.keystore -alias mihon -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 personal.keystore > personal.keystore.b64   # macOS: base64 -i personal.keystore -o personal.keystore.b64
```

Keep `personal.keystore` and its password somewhere safe. If you lose it, you can't install updates over the app.

In the GitHub repo, open *Settings → Secrets and variables → Actions* and add:

| Secret               | Value                                   |
|----------------------|-----------------------------------------|
| `SIGNING_KEY`        | contents of `personal.keystore.b64`     |
| `KEY_STORE_PASSWORD` | the keystore password                   |
| `ALIAS`              | `mihon`                                 |
| `KEY_PASSWORD`       | the key password (same as above if you only set one) |

### 2. Build

Open *Actions → Build personal APK → Run workflow*; it also runs on every push to `main`. When it finishes:

- download the `remon-…` artifact; for most phones, `app-arm64-v8a-release.apk` is the one to install
- copy the **SHA1** fingerprint from the "Print signing certificate SHA-1" step; you need it in the next step

### 3. Google Cloud project (for Drive access)

1. Go to <https://console.cloud.google.com/>, create a project (any name).
2. *APIs & Services → Library*, search **Google Drive API**, click **Enable**.
3. *Google Auth Platform* (or *OAuth consent screen*): fill in an app name and your email, choose **External**.
   Under *Audience*, click **Publish app**. `drive.file` is a non-sensitive scope, so Google doesn't need to review it.
   If you leave the app in "Testing" instead, add yourself as a test user and expect to reconnect every 7 days.
4. *Clients → Create client → Android*:
   - Package name: `app.remon`
   - SHA-1 certificate fingerprint: the value from the build log

You don't need to copy a client ID into the app. Google Play services matches the app by package name and fingerprint.

### 4. In the app

*Settings → Data and storage*: set the automatic backup interval, then turn on
**Upload automatic backups to Google Drive** and pick your account. Then tap **Back up to Google Drive now** to check it works.

If an upload fails, you get a notification and the local backup is kept. When Drive is on, automatic backups wait
for an internet connection.
