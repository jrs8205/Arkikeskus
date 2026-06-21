# Arkikeskus R8-säännöt (vain release; debug ei minifioi).
# Periaate 1. kierroksella: varman päälle — leveät keepit reflektiota käyttäville
# kirjastoille (HiveMQ/Netty), kiristetään myöhemmin jos kokoa halutaan lisää alas.

# Luettavat stack tracet (mapping.txt talteen julkaisun yhteydessä)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

## HiveMQ MQTT + Netty (HSL HFP -live). Netty käyttää runsaasti reflektiota
## (Unsafe, valinnaiset codecit) eikä tuo omia consumer-sääntöjä.
-keep class com.hivemq.** { *; }
-keep class io.netty.** { *; }
# JCTools (HiveMQ:n jonototeutus) hakee consumerIndex/producerIndex-kentät reflektiolla
# → ilman keepiä ExceptionInInitializerError MQTT-connectissa (todettu laitteella 11.6.2026)
-keep class org.jctools.** { *; }
-dontwarn com.hivemq.**
-dontwarn io.netty.**
-dontwarn org.jctools.**
-dontwarn org.slf4j.**
-dontwarn io.reactivex.**
# Nettyn valinnaiset riippuvuudet joita ei ole classpathilla
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.eclipse.jetty.alpn.**
-dontwarn org.eclipse.jetty.npn.**
-dontwarn com.google.protobuf.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.**
-dontwarn org.jboss.marshalling.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.oracle.svm.core.annotate.**
-dontwarn reactor.blockhound.**

## Room-entiteetit (sarakemäppäys ei saa hajota; annotaatiopohjaiset yleensä OK,
## mutta pidetään varmuuden vuoksi: RuuviSampleEntity, DailyStepsEntity, DailyStat, WeatherSample)
-keep @androidx.room.Entity class * { *; }

## MapLibre (natiivisillat JNI:llä; AAR:n consumer-säännöt täydennyksenä)
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

## Health Connect client (askeleet/kalorit; sisäinen proto-sarjallistus)
-keep class androidx.health.connect.** { *; }
-keep class androidx.health.platform.** { *; }
-dontwarn androidx.health.**

## Fragmentit: FragmentFactory luo uudelleen Class.forName(nimi)-polulla saved
## statesta → nimi ei saa muuttua eikä oletuskonstruktori kadota
-keep class * extends androidx.fragment.app.Fragment { <init>(); }

## Glance-kotinäyttöwidgetit (KRIITTINEN): R8 yhdisti aiemmin kaikki neljä lähes
## identtistä GlanceAppWidget-alaluokkaa (Weather/Electricity/Steps/Departure) yhdeksi
## luokaksi ($r8$classId-erottimella). Glance tunnistaa widgetin JA sen kotinäyttö-
## instanssit GlanceAppWidget-alaluokan kautta (javaClass) → yhdistettynä
## GlanceAppWidgetManager.getGlanceIds(X::class) palautti KAIKKIEN widgettien id:t, joten
## esim. WeatherWidget().updateAll() piirsi sääsisällön JOKA widgettiin → "kaikki muuttui
## sääksi" (2.16.0). Pidetään luokat erillisinä (estää horisontaalisen yhdistämisen).
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
## ActionCallback (esim. DepartureRefreshAction) ratkaistaan nimellä actionRunCallbackissa
## → ei saa yhdistää/uudelleennimetä.
-keep class * extends androidx.glance.appwidget.action.ActionCallback { *; }

## Omat custom-View't (täydennys AAPT-sääntöihin; layout-XML inflatoi nimellä)
-keep class org.jrs82.** extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
