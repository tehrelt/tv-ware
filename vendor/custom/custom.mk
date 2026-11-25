# Isolated custom layer for Xiaomi TV Box S 2nd Gen (jaws)

PRODUCT_SOONG_NAMESPACES += vendor/custom

# Built-in VPN (Happ) + sysconfig power/data allowlist
PRODUCT_PACKAGES += \
    Happ \
    happ-sysconfig.xml

# Speed optimization overlays (0.25x animation scales)
PRODUCT_PACKAGES += \
    CustomFrameworkOverlay \
    CustomSettingsProviderOverlay \
    CustomSimpleSettingsConfigOverlay

# Strip unnecessary system stubs / browser
PRODUCT_PACKAGES := $(filter-out \
    HTMLViewer \
    BasicDreams \
    ExactCalculator \
    Jelly \
    Browser2 \
    ,$(PRODUCT_PACKAGES))

# Auto-pickup optional APKs from ~/custom_apks/
_CUSTOM_APKS_SRC := $(if $(CUSTOM_APKS_DIR),$(CUSTOM_APKS_DIR),$(HOME)/custom_apks)
_CUSTOM_APK_FLAGS := $(shell python3 vendor/custom/scripts/sync_custom_apks.py "$(_CUSTOM_APKS_SRC)" "vendor/custom/apks" "prebuilts/build-tools/linux-x86/bin/zipalign")

ifneq ($(filter HAS_YOUTUBETV=true,$(_CUSTOM_APK_FLAGS)),)
$(call soong_config_set_bool,custom_apks,has_youtubetv,true)
PRODUCT_PACKAGES += YouTubeTV
else
$(call soong_config_set_bool,custom_apks,has_youtubetv,false)
endif

ifneq ($(filter YOUTUBETV_SKIP=true,$(_CUSTOM_APK_FLAGS)),)
$(call soong_config_set_bool,custom_apks,youtubetv_skip_checks,true)
else
$(call soong_config_set_bool,custom_apks,youtubetv_skip_checks,false)
endif

ifneq ($(filter HAS_SPOTIFYTV=true,$(_CUSTOM_APK_FLAGS)),)
$(call soong_config_set_bool,custom_apks,has_spotifytv,true)
PRODUCT_PACKAGES += SpotifyTV
else
$(call soong_config_set_bool,custom_apks,has_spotifytv,false)
endif

ifneq ($(filter SPOTIFYTV_SKIP=true,$(_CUSTOM_APK_FLAGS)),)
$(call soong_config_set_bool,custom_apks,spotifytv_skip_checks,true)
else
$(call soong_config_set_bool,custom_apks,spotifytv_skip_checks,false)
endif

ifneq ($(filter HAS_CUSTOMLAUNCHER=true,$(_CUSTOM_APK_FLAGS)),)
$(call soong_config_set_bool,custom_apks,has_customlauncher,true)
PRODUCT_PACKAGES += CustomLauncher
else
$(call soong_config_set_bool,custom_apks,has_customlauncher,false)
endif

ifneq ($(filter CUSTOMLAUNCHER_SKIP=true,$(_CUSTOM_APK_FLAGS)),)
$(call soong_config_set_bool,custom_apks,customlauncher_skip_checks,true)
else
$(call soong_config_set_bool,custom_apks,customlauncher_skip_checks,false)
endif

ifeq ($(filter 1 true,$(REPLACE_LAUNCHER)),)
$(call soong_config_set_bool,custom_apks,replace_launcher,false)
else ifneq ($(filter HAS_CUSTOMLAUNCHER=true,$(_CUSTOM_APK_FLAGS)),)
$(call soong_config_set_bool,custom_apks,replace_launcher,true)
PRODUCT_PACKAGES := $(filter-out Catapult,$(PRODUCT_PACKAGES))
else
$(call soong_config_set_bool,custom_apks,replace_launcher,false)
endif
