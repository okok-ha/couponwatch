# Room 生成的实现类通过反射加载
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ML Kit OCR（仅 full 变体）
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# 无障碍服务由系统实例化，不能被裁剪或改名
-keep class com.couponwatch.detector.service.CouponAccessibilityService { *; }
