APP_PLATFORM := android-16
APP_ABI := armeabi-v7a arm64-v8a x86
APP_STL := none

ifneq ($(NDK_DEBUG),1)
APP_CFLAGS  += -Oz -flto=full -fno-unwind-tables -fno-exceptions -fno-asynchronous-unwind-tables -fomit-frame-pointer
APP_LDFLAGS += -flto=full
endif
