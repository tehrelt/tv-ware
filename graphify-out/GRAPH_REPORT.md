# Graph Report - tv-ware  (2026-11-26)

## Corpus Check
- Corpus is ~44,249 words - fits in a single context window. You may not need a graph.

## Summary
- 1189 nodes · 3088 edges · 51 communities (28 shown, 23 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 162 edges (avg confidence: 0.89)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- tv-setup CustomTkinter GUI
- tv-setup Core Utilities
- tv-setup Network & Concurrency
- Android Key & Content Observers
- Compose Layout & Focus
- Compose Visual Effects & Text
- SpicyLyrics Client & Cache
- tv-setup Optimizer & Tests
- tv-setup JSON & File I/O
- Android Network & Hashing
- Compose Canvas & Animations
- GitHub Release Workflow
- Web Settings Server Handler
- Serena Project Config
- Launcher ViewModel & Coroutines
- Compose Clock & Brush
- Compose Grid & Lazy Lists
- App Repository & Broadcasts
- Android Bitmap & Drawables
- tv-setup Design & Plans
- Idle Dream Service
- Status Bar & Network Icons
- Media Screen Reducer
- MainActivity & Auto-Start
- Launcher Preferences & Pins
- DataStore Preferences Keys
- AppInfo & Artwork Model
- Web Settings Server Lifecycle
- NetworkMonitor & StateFlow
- QR Code Generator
- Happ Auto-Start Tests
- Material3 Dark Typography
- Media Artwork & Menu Input
- Home Screen Screenshot
- Media Playback State
- Idle Dream Unit Tests
- Context Menu Screenshot
- Grid Home Screenshot
- Settings Screen Screenshot
- Package Order & Tests
- Media Notification Listener
- tv-setup GUI Dependencies

## God Nodes (most connected - your core abstractions)
1. `TvSetupApp` - 66 edges
2. `OptimizerTests` - 49 edges
3. `ApplyPlan` - 48 edges
4. `LauncherViewModel` - 46 edges
5. `AdbClient` - 42 edges
6. `FakeAdb` - 41 edges
7. `AdbError` - 40 edges
8. `MediaMonitor` - 36 edges
9. `rollback_device()` - 35 edges
10. `output()` - 29 edges

## Surprising Connections (you probably didn't know these)
- `Wi-Fi ADB Verification` --semantically_similar_to--> `APK Installer & HOME Switch`  [INFERRED] [semantically similar]
  tv-launcher/README.md → docs/superpowers/specs/2026-11-25-tv-setup-design.md
- `tv-setup Windows App` --references--> `tv-setup Design Specification`  [EXTRACTED]
  README.md → docs/superpowers/specs/2026-11-25-tv-setup-design.md
- `AppContextDialog()` --calls--> `MenuConfirmInput`  [INFERRED]
  tv-launcher/app/src/main/java/com/tvware/launcher/ui/components/AppContextDialog.kt → tv-launcher/app/src/main/java/com/tvware/launcher/ui/components/MenuConfirmInput.kt
- `HomeScreen()` --calls--> `MediaScreen()`  [INFERRED]
  tv-launcher/app/src/main/java/com/tvware/launcher/ui/screens/HomeScreen.kt → tv-launcher/app/src/main/java/com/tvware/launcher/ui/screens/MediaScreen.kt
- `IdleDreamScreen()` --calls--> `LauncherBackground()`  [INFERRED]
  tv-launcher/app/src/main/java/com/tvware/launcher/ui/screens/IdleDreamScreen.kt → tv-launcher/app/src/main/java/com/tvware/launcher/ui/screens/HomeScreen.kt

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **tv-setup Modular Architecture** — docs_superpowers_plans_2026_11_26_tv_setup_adb_client_module, docs_superpowers_plans_2026_11_26_tv_setup_discovery_module, docs_superpowers_plans_2026_11_26_tv_setup_optimizer_module, docs_superpowers_plans_2026_11_26_tv_setup_installer_module, docs_superpowers_plans_2026_11_26_tv_setup_gui_module [EXTRACTED 1.00]
- **tv-launcher Build and Release Pipeline** — readme_tv_launcher, readme_ci_release_workflow, tv_launcher_readme_gradle_build, docs_superpowers_specs_2026_11_25_tv_setup_design_apk_installer_home_switch [INFERRED 0.90]

## Communities (51 total, 23 thin omitted)

### Community 0 - "tv-setup CustomTkinter GUI"
Cohesion: 0.05
Nodes (28): GuiSmokeTests, run_sync(), find_adb_executable(), DiscoveredDevice, _add_dialog_buttons(), _add_labeled_entry(), _fit_dialog_size(), format_device_label() (+20 more)

### Community 1 - "tv-setup Core Utilities"
Cohesion: 0.05
Nodes (44): AdbClientTests, BootstrapTests, make_bad_crc_zip(), make_zip(), AdbClient, AdbError, AdbResult, _cleanup_directory() (+36 more)

### Community 2 - "tv-setup Network & Concurrency"
Cohesion: 0.08
Nodes (20): DiscoveryTests, UsbOrTcpDevice, _AdapterAddress, _adb_device(), browse_mdns(), DeviceStatus, discover_devices(), extract_scan_subnets() (+12 more)

### Community 3 - "Android Key & Content Observers"
Cohesion: 0.07
Nodes (5): MediaMonitor, Callback, isPlaybackActiveState(), isPlaybackPausedState(), selectPreferredSessionIndex()

### Community 4 - "Compose Layout & Focus"
Cohesion: 0.08
Nodes (5): AppContextDialog(), DialogActionButton(), ActionChip(), SettingsScreen(), StatusLine()

### Community 5 - "Compose Visual Effects & Text"
Cohesion: 0.08
Nodes (17): ChevronDownIcon(), MediaArtworkCard(), MediaBackdrop(), MediaChromeSlot(), MediaCircleButton(), MediaControlsRow(), MediaLyricsPanel(), MediaPillButton() (+9 more)

### Community 6 - "SpicyLyrics Client & Cache"
Cohesion: 0.08
Nodes (33): SpicyLyricsClient, CacheEntry, HttpResult, Failed, NotFound, Ok, RateLimited, Unauthorized (+25 more)

### Community 7 - "tv-setup Optimizer & Tests"
Cohesion: 0.17
Nodes (7): FakeAdb, OptimizerTests, shell(), shell(), output(), ApplyPlan, rollback_device()

### Community 8 - "tv-setup JSON & File I/O"
Cohesion: 0.10
Nodes (25): _bytes_response(), InstallerTests, fake_urlopen(), fake_urlopen(), fake_urlopen(), fake_urlopen(), fake_urlopen(), fake_urlopen() (+17 more)

### Community 9 - "Android Network & Hashing"
Cohesion: 0.08
Nodes (8): buildWebSettingsHtml(), WebAppItem, WebServerInfo, WebSettingsHandler, WebSettingsPatch, WebSettingsServer, HttpRequest, WebSettingsSnapshot

### Community 10 - "Compose Canvas & Animations"
Cohesion: 0.09
Nodes (6): MediaTrackKey, mediaControlsHideAfterMs(), prepareSoftWarpBitmap(), warpArtworkMesh(), WarpedArtwork(), MediaPresentationTest

### Community 11 - "GitHub Release Workflow"
Cohesion: 0.06
Nodes (33): concurrency, cancel-in-progress, group, KEYSTORE_BASE64, KEYSTORE_PROPERTIES, jobs, release, name (+25 more)

### Community 12 - "Web Settings Server Handler"
Cohesion: 0.11
Nodes (3): LauncherViewModel, WebSettingsHandler, LyricsTrackQuery

### Community 13 - "Serena Project Config"
Cohesion: 0.08
Nodes (25): activation_command, activation_command_timeout, added_modes, agent_interface, default_modes, encoding, excluded_apis, excluded_tools (+17 more)

### Community 14 - "Launcher ViewModel & Coroutines"
Cohesion: 0.10
Nodes (5): BackgroundMode, BLACK, CUSTOM_FILE, GRADIENT, LauncherUiState

### Community 16 - "Compose Grid & Lazy Lists"
Cohesion: 0.12
Nodes (3): HomeScreen(), LauncherBackground(), SectionTitle()

### Community 19 - "tv-setup Design & Plans"
Cohesion: 0.10
Nodes (20): adb_client Module Plan, discovery Module Plan, tv-setup Implementation Plan, installer Module Plan, optimizer Module Plan, ADB Auto-Discovery & Platform-Tools Download, APK Installer & HOME Switch, Device Inspection & Rollback Snapshot (+12 more)

### Community 21 - "Status Bar & Network Icons"
Cohesion: 0.26
Nodes (11): NetworkStatus, EthernetIcon(), NetworkStatusIndicator(), NowPlayingChip(), SettingsGearIcon(), SlidersIcon(), StatusBar(), BroadcastReceiver (+3 more)

### Community 22 - "Media Screen Reducer"
Cohesion: 0.25
Nodes (3): MediaScreenReducer, MediaScreenVisibilityState, MediaStateTest

### Community 23 - "MainActivity & Auto-Start"
Cohesion: 0.16
Nodes (3): shouldAutoStartHapp(), MainActivity, TileMenu

### Community 27 - "AppInfo & Artwork Model"
Cohesion: 0.26
Nodes (3): AppArtwork, AppInfo, AppTile()

### Community 34 - "Media Artwork & Menu Input"
Cohesion: 0.31
Nodes (4): MediaArtworkSource, selectMediaArtwork(), MenuConfirmInput, LauncherInputAndArtworkTest

### Community 35 - "Home Screen Screenshot"
Cohesion: 0.20
Nodes (10): All Apps Section (Все приложения), Clock and Date Display (19:26, Wed, 25 Nov), Minimalist Dark Android TV Launcher Layout, D-Pad Focus Ring on YouTube Card, Favorites Section (Избранное), TV-Ware Home Screen UI, Quick Settings and System Settings Icon Buttons, Top Status and Action Bar (+2 more)

### Community 36 - "Media Playback State"
Cohesion: 0.39
Nodes (5): computePlaybackPositionMs(), formatMediaDuration(), MediaMonitorState, MediaSessionInfo, MediaProgressSection()

### Community 37 - "Idle Dream Unit Tests"
Cohesion: 0.36
Nodes (3): idleDriftOffset(), shouldShowDreamMedia(), IdleDreamTest

### Community 39 - "Context Menu Screenshot"
Cohesion: 0.29
Nodes (7): Action: App Info (О приложении), Action: Hide App (Скрыть приложение), Focused Action: Remove from Favorites (Убрать из избранного), App Title and Package Name Header (YouTube / com.google.android.youtube.tv), Dimmed Launcher Home Screen Backdrop (Favorites & All Apps), App Context Menu Modal Dialog, Android TV Launcher Context Menu Screenshot

### Community 40 - "Grid Home Screenshot"
Cohesion: 0.29
Nodes (7): All Apps Grid Section (Все приложения), 10-Foot Dark TV UI Theme, Favorites Section (Избранное), D-Pad Focus Outline on Play Games Tile, TV Launcher Grid Home Screen, Top Status Bar (Clock, Date, Wi-Fi, VPN, Settings), VPN Status Pill Indicator

### Community 41 - "Settings Screen Screenshot"
Cohesion: 0.40
Nodes (6): Back Navigation Button (Назад), Background Theme Selector (Фон: Чёрный, Графит, Изображение), Grid Columns Selector (Колонок в сетке: 4, 5, 6), Hidden Apps Section (Скрытые приложения), Launcher Settings Screen (Настройки лаунчера), 10-Foot TV D-Pad Focus vs Active Selection Visual Pattern

## Knowledge Gaps
- **39 isolated node(s):** `BLACK`, `GRADIENT`, `CUSTOM_FILE`, `NotFound`, `Unauthorized` (+34 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 275 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **23 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `BackgroundMode` connect `Launcher ViewModel & Coroutines` to `Compose Visual Effects & Text`, `Android Network & Hashing`, `Compose Clock & Brush`, `Compose Grid & Lazy Lists`, `Idle Dream Service`, `Launcher Preferences & Pins`, `Kotlin Coroutine Unit Tests`, `DataStore Preferences Keys`?**
  _High betweenness centrality (0.193) - this node is a cross-community bridge._
- **Are the 9 inferred relationships involving `TvSetupApp` (e.g. with `GuiSmokeTests` and `AdbClient`) actually correct?**
  _`TvSetupApp` has 9 INFERRED edges - model-reasoned connections that need verification._
- **What connects `BLACK`, `GRADIENT`, `CUSTOM_FILE` to the rest of the system?**
  _39 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `tv-setup CustomTkinter GUI` be split into smaller, more focused modules?**
  _Cohesion score 0.05067567567567568 - nodes in this community are weakly interconnected._
- **Why does `AdbError` connect `tv-setup Core Utilities` to `tv-setup JSON & File I/O`, `tv-setup CustomTkinter GUI`, `tv-setup Network & Concurrency`, `tv-setup Optimizer & Tests`?**
  _High betweenness centrality (0.099) - this node is a cross-community bridge._
- **Are the 4 inferred relationships involving `OptimizerTests` (e.g. with `AdbError` and `AdbResult`) actually correct?**
  _`OptimizerTests` has 4 INFERRED edges - model-reasoned connections that need verification._
- **Should `tv-setup Core Utilities` be split into smaller, more focused modules?**
  _Cohesion score 0.053958177744585514 - nodes in this community are weakly interconnected._