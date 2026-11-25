# Ensure target_files dir waits for logo.img (upstream logo.mk only depends on BUILT_TARGET_FILES_PACKAGE)
ifneq ($(filter gxl gxm g12a g12b sm1 s4 t7 sc2, $(TARGET_AMLOGIC_SOC)),)
$(BUILT_TARGET_FILES_DIR): $(PRODUCT_OUT)/logo.img
endif
