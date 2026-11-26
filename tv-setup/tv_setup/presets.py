"""Default TV optimization presets."""

from dataclasses import dataclass

LAUNCHER_PACKAGE = "com.tvware.launcher"
LAUNCHER_ACTIVITY = "com.tvware.launcher/.MainActivity"
LAUNCHER_DREAM_COMPONENT = "com.tvware.launcher/.LauncherDreamService"
HAPP_PACKAGES = ("su.happ.proxyutility", "com.happproxy")
STOCK_LAUNCHER_PACKAGES = (
    "com.google.android.apps.tv.launcherx",
    "com.google.android.tvlauncher",
    "com.mitv.tvhome",
    "org.lineageos.tv.launcher",
)
ANIMATION_KEYS = (
    "window_animation_scale",
    "transition_animation_scale",
    "animator_duration_scale",
)
SCREENSAVER_KEYS = (
    "screensaver_enabled",
    "screensaver_components",
    "screensaver_activate_on_sleep",
    "screensaver_activate_on_dock",
)


@dataclass(frozen=True)
class BloatEntry:
    package: str
    label: str
    category: str
    default_checked: bool


_GOOGLE = "Google TV / Android TV"
_OPTIONAL = "Опциональные Google/стриминг"
_XIAOMI = "Xiaomi / PatchWall"
BLOAT_CATALOG: tuple[BloatEntry, ...] = (
    BloatEntry("com.google.android.play.games", "Google Play Games", _GOOGLE, True),
    BloatEntry("com.google.android.videos", "Google TV", _GOOGLE, True),
    BloatEntry("com.google.android.music", "Google Play Music", _GOOGLE, True),
    BloatEntry("com.google.android.tvrecommendations", "TV Recommendations", _GOOGLE, True),
    BloatEntry("com.google.android.feedback", "Google Feedback", _GOOGLE, True),
    BloatEntry("com.google.android.partnersetup", "Google Partner Setup", _GOOGLE, True),
    BloatEntry("com.android.dreams.basic", "Basic Screensavers", _GOOGLE, True),
    BloatEntry("com.android.htmlviewer", "HTML Viewer", _GOOGLE, True),
    BloatEntry("com.google.android.katniss", "Google Assistant", _OPTIONAL, False),
    BloatEntry("com.google.android.youtube.tvmusic", "YouTube Music", _OPTIONAL, False),
    BloatEntry("com.netflix.ninja", "Netflix", _OPTIONAL, False),
    BloatEntry("com.amazon.amazonvideo.livingroom", "Prime Video", _OPTIONAL, False),
    BloatEntry("com.disney.disneyplus", "Disney+", _OPTIONAL, False),
    BloatEntry("com.xiaomi.mitv.advertise", "Mi TV Ads", _XIAOMI, True),
    BloatEntry("com.miui.systemAdSolution", "MIUI System Ads", _XIAOMI, True),
    BloatEntry("com.xiaomi.mitv.shop", "Mi TV Shop", _XIAOMI, True),
    BloatEntry("com.xiaomi.mitv.payment", "Mi TV Payment", _XIAOMI, True),
    BloatEntry("com.xiaomi.mitv.pay", "Mi TV Pay", _XIAOMI, True),
    BloatEntry("com.xiaomi.mibox.gamecenter", "Mi Box Game Center", _XIAOMI, True),
    BloatEntry("com.xiaomi.mitv.updates", "Mi TV Updates", _XIAOMI, True),
    BloatEntry("com.xiaomi.tv.appupgrade", "Xiaomi TV App Upgrade", _XIAOMI, True),
)
