# gomobile bindings: JNI entry points and Seq proxy classes are reached reflectively.
-keep class io.github.sagernet.libbox.libbox.** { *; }
-keep class go.** { *; }
-keepclassmembers class * implements go.Seq$Proxy { *; }

# Keep our own model/config classes used by reflection-free JSON (org.json) round-trips.
-keep class id.jhopanstore.pantunnel.core.** { *; }

-dontwarn io.github.sagernet.libbox.**
-dontwarn go.**
