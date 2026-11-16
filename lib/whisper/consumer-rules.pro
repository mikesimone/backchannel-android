# Called from whisper_jni.cpp by name.
-keep class net.mikesimone.backchannel.whisper.WhisperNative { *; }
-keep interface net.mikesimone.backchannel.whisper.WhisperNative$SegmentListener { *; }
-keep class * implements net.mikesimone.backchannel.whisper.WhisperNative$SegmentListener { void onSegment(java.lang.String); }
