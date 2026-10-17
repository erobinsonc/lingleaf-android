# Release builds: R8 shrinks the app. kotlinx.serialization ships its own rules;
# these keep the saved-data classes' serializers explicitly, so a library update
# can never make the app unable to read what the learner saved.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class app.lingleaf.** {
    *** Companion;
}
-if @kotlinx.serialization.Serializable class app.lingleaf.**
-keepclassmembers class app.lingleaf.<1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.lingleaf.**$$serializer { *; }

# Shrinking only: renaming hides nothing in open-source code and would make crash
# reports unreadable.
-dontobfuscate
