# kotlinx.serialization ships its own consumer rules; these keep the generated serializers of
# this app's @Serializable classes reachable through their companions.
-keepclassmembers class io.github.rosyidputrar.duobackup.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses

# Tink references optional classes that are not on Android's classpath.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
