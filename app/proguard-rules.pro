# Aturan ProGuard / R8 untuk Mythos
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod,SourceFile,LineNumberTable
-dontwarn org.json.**

# Kode aplikasi tidak memakai refleksi/serialisasi otomatis (JSON ditulis manual lewat org.json; enum disimpan lewat name()),
# jadi paket agent.** dan ui.** TIDAK perlu di-keep seluruhnya. Hanya yang namanya tampil ke pengguna/model yang dijaga:
# nama kelas galat masuk ke pesan hasil alat (Agent.kt: e.javaClass.simpleName).
-keepnames class * extends java.lang.Throwable
-keep class com.mythos.app.** extends java.lang.Throwable

# Jaga nama konstanta enum yang disimpan di riwayat/pengaturan (ChatStore, Settings memakai name()/valueOf()).
-keep enum com.mythos.app.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Bila build release gagal/crash setelah perubahan ini, pulihkan sementara dua baris berikut lalu laporkan kelas penyebabnya:
# -keep class com.mythos.app.agent.** { *; }
# -keep class com.mythos.app.ui.** { *; }
