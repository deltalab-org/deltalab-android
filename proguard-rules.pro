# native methods
-keep class com.b44t.messenger.** { *; }
-keep class org.thoughtcrime.securesms.util.FileUtils* { *; }

# bug with video recoder
-keep class com.coremedia.iso.** { *; }

# unused SealedData constructor needed by JsonUtils
-keep class org.thoughtcrime.securesms.crypto.KeyStoreHelper* { *; }
