# Required by NewPipeExtractor's JavaScript interpreter.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
# Desktop-only optional paths are not used by NewPipe's interpreted-mode JavaScript runner.
# These narrow rules follow the upstream NewPipe Android application's Rhino rules.
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn jdk.dynalink.**

# Split streams worked in debug but disappeared from the original minified build
# on API 36. Retain the extractor bytecode for this proof's release compatibility;
# the app and Media3 remain optimised. Narrowing this rule needs device regression.
-keep class org.schabi.newpipe.extractor.** { *; }
