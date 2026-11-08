<a id="readme-top"></a>

<div align="center">
  <img src="./assets/logo.png" alt="murrmurr logo" width="96" height="96">
  <h1>murrmurr</h1>
  <p>An Android music player for quickly switching between local storage and a NAS.</p>
</div>

<details>
  <summary>Table of contents</summary>
  <ul>
    <li><a href="#about-the-project">About the project</a></li>
    <li><a href="#getting-started">Getting started</a></li>
    <li><a href="#usage">Usage</a></li>
    <li><a href="#license">License</a></li>
  </ul>
</details>

## About the project

murrmurr plays music stored on your Android device, including microSD cards,
and music hosted on a NAS running [Navidrome](https://www.navidrome.org/).
The **Local / Server** button switches the library you browse without stopping
the current track or replacing the queue. Each source keeps its own browsing state.

The app supports Android 11 and newer.
NAS access is through Navidrome, not direct SMB or NFS shares.

### Features

- Browse songs, albums, artists, genres and folders, with search and saved view modes.
- Background playback, notification and lock-screen controls, shuffle, repeat and an editable queue.
- Local playlists, favorites, star ratings and rating-based smart playlists.
- Read-only browsing of Navidrome playlists, with server-side favorites and ratings.
- M3U/M3U8 playlist import/export for local music.
- Library statistics, device-recorded listening history and an optional ten-band equalizer.

### Built with

- [Kotlin](https://kotlinlang.org/)
- [Jetpack Compose](https://developer.android.com/compose)
- [AndroidX Media3](https://developer.android.com/media/media3)

## Getting started

### Prerequisites

- An Android 11+ device.
- Local music files, or a Navidrome server with a music library and user account.
- Network access to the server when streaming from your NAS.

### Installation

1. Download the [latest release APK](https://github.com/AMBIENTCORE/murrmurr/releases/latest)
   to your Android device. Files are named
   `murmurr_<version>.apk`, for example `murmurr_0.9.10.apk`.
2. Open the APK from your browser's downloads or a file manager.
3. If Android prompts you, allow that browser or file manager to
   **Install unknown apps**, then return to the APK.
4. Tap **Install**, then open **murrmurr** and grant music access.

For updates, install the newer APK over the existing app rather than uninstalling
first. Uninstalling removes app-local playlists, ratings and listening history.

## Usage

### Themes

Open **Settings > Themes** to select **Default**, the original palette, or
**Cinder**, with grey surfaces and cream accents from the logo. The choice is saved
across restarts and applies to both Local and Server.

### Local music

1. Open murrmurr and grant music access.
2. For music outside Android's index, select **Settings > Add music folder** and
   choose a directory in Android's document picker.
3. Use the toolbar refresh button or **Settings > Scan for new tracks** after
   adding files.
4. Browse **Library**, select a track, and tap the mini-player to open playback
   controls and the queue.

   Local scanning has three modes:

| Mode | Use it for |
| --- | --- |
| **Scan for new tracks** | New or changed entries in Android's music index. Does not remove missing tracks. |
| **Refresh tags / remove missing** | Reconcile the local library with mounted Android music indexes. Offline cards are retained. |
| **Deep folder scan** | Find unindexed files in selected folders. Slower because it enumerates those folders. |

### Now Playing and tablets

Tap the mini-player for full-width artwork and compact track metadata: artist,
title, album, year, catalog number when tagged, genres, and file type / bitrate
when available. Catalog tags in ID3 and Vorbis metadata are read during playback.
Embedded year/date tags are used even when Android's music index has no year.
Local year and catalog tags are read without requiring playback, including
paused or restored tracks.
The labels are **Catalog No.** and **Genres:**. The gear opens Settings; the
equalizer and queue icons provide direct access to their controls. **...** opens
the song menu for ratings, favorites, queue actions, track information and adding
local tracks to a playlist.

Tap the artist or album name to browse its tracks. Individual genre names link
to their genre collections. The song menu also offers **Go to artist**,
**Go to album**, **Go to album artist** when different, **Go to genre**, and
**Go to folder** when available. Navigation uses the track's Local/Server source
without changing playback or the queue.

Android tablets are supported in portrait, landscape and resizable windows.
Landscape and near-square Now Playing windows place artwork beside metadata and
controls; portrait and narrow windows stack them vertically. Now Playing keeps
metadata and playback controls on one screen, sizing the artwork to fill the
remaining space without scrolling. Short windows use compact metadata, and long
text is ellipsized; full metadata remains available in **Track information**.
Library icon grids add columns on wider windows, and tablet list actions retain
48 dp touch targets.

### NAS / Navidrome

1. Run Navidrome on your NAS and make sure the device can reach it.
2. Tap **Local** in the toolbar, or open
   **Settings > Server > Configure Navidrome server**.
3. Enter the server's base URL, username and password.
4. Select **Test connection**, then **Save and connect**.
5. Tap **Server** to return to local browsing, or **Local** to browse the server again.

Switching sources changes browsing, not playback. The saved server catalog is
shown on subsequent switches while a refresh runs in the background. If the
server is unavailable, you can still switch back to local music.

Use HTTPS where possible. Plain HTTP requires enabling
**Allow HTTP over trusted LAN / Tailscale**; the app does not encrypt HTTP traffic.
Do not use it over an untrusted network.

For access away from home, you can run [Tailscale](https://tailscale.com/) on both
the Android device and NAS, then use the NAS's Tailscale address. Connect Tailscale
separately and allow access to Navidrome's port; murrmurr does not manage the VPN.

Server playlists are managed in Navidrome. Server ratings and favorites are saved
to that account and do not overwrite local ratings.

### Playlists and ratings

Use **Playlists** to create local playlists or smart playlists based on star
ratings. Import M3U/M3U8 files with **Playlists > Import playlists** after scanning
the corresponding music files.

## License

Distributed under the MIT License. See [LICENSE](./LICENSE).

<p align="right"><a href="#readme-top">Back to top</a></p>
