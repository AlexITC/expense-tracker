import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._ // enables the %%% operator
import play.sbt.PlayImport.{evolutions, guice, jdbc, ws}
import sbt._

/** Centralized dependency versions and module dependency groups. The module definitions live in build.sbt and reference
  * these; versions are declared once here.
  *
  * Groups that use `%%%` (Scala.js / cross) are `Def.Initialize` values because `%%%` resolves the platform suffix from
  * the enclosing project and can only run inside a setting macro; read them with `.value`. Groups that use only `%%` /
  * `%` (JVM / Java) are plain `Seq[ModuleID]` and are used directly.
  */
object Dependencies {

  // Versions
  val pekko = "1.6.0"
  val playJson = "3.0.6"
  val sttp = "3.11.0"
  val webappUtils = "0.7.2"
  val anorm = "3.0.0"
  val scalaJavaTime = "2.6.0"
  val tapir = "1.13.19"
  val chimney = "1.10.0"
  val scalatest = "3.2.20"

  // Pekko is pinned across the whole build (ThisBuild / dependencyOverrides).
  val pekkoOverrides: Seq[ModuleID] = Seq(
    "org.apache.pekko" %% "pekko-actor" % pekko,
    "org.apache.pekko" %% "pekko-actor-typed" % pekko,
    "org.apache.pekko" %% "pekko-stream" % pekko,
    "org.apache.pekko" %% "pekko-slf4j" % pekko,
    "org.apache.pekko" %% "pekko-serialization-jackson" % pekko
  )

  // lib/common
  val commonJvm: Seq[ModuleID] = Seq(
    "org.playframework" %% "play-json" % playJson,
    "net.wiringbits" %% "webapp-common" % webappUtils,
    "org.scalatest" %% "scalatest" % scalatest % Test
  )
  val commonJs: Def.Initialize[Seq[ModuleID]] = Def.setting(
    Seq(
      "io.github.cquiroz" %%% "scala-java-time" % scalaJavaTime,
      "org.playframework" %%% "play-json" % playJson,
      "net.wiringbits" %%% "webapp-common" % webappUtils,
      "org.scalatest" %%% "scalatest" % scalatest % Test
    )
  )

  // lib/api
  val apiJvm: Seq[ModuleID] = Seq(
    "org.playframework" %% "play-json" % playJson,
    "com.softwaremill.sttp.client3" %% "core" % sttp,
    "com.softwaremill.sttp.tapir" %% "tapir-json-play" % tapir,
    "com.softwaremill.sttp.tapir" %% "tapir-sttp-client" % tapir,
    "org.scalatest" %% "scalatest" % scalatest % Test
  )
  val apiJs: Def.Initialize[Seq[ModuleID]] = Def.setting(
    Seq(
      "org.playframework" %%% "play-json" % playJson,
      "org.scalatest" %%% "scalatest" % scalatest % Test,
      "com.softwaremill.sttp.client3" %%% "core" % sttp,
      "com.softwaremill.sttp.tapir" %%% "tapir-json-play" % tapir,
      "com.softwaremill.sttp.tapir" %%% "tapir-sttp-client" % tapir
    )
  )

  // lib/ui
  val ui: Def.Initialize[Seq[ModuleID]] = Def.setting(
    Seq(
      "io.github.cquiroz" %%% "scala-java-time" % scalaJavaTime,
      "org.scala-js" %%% "scala-js-macrotask-executor" % "1.1.1",
      "com.olvind.st-material-ui" %%% "st-material-ui-icons-slinky" % "5.11.16",
      "net.wiringbits" %%% "slinky-utils" % webappUtils,
      "org.scalatest" %%% "scalatest" % scalatest % Test
    )
  )

  // web (Scala.js app)
  val web: Def.Initialize[Seq[ModuleID]] = Def.setting(
    Seq(
      "org.playframework" %%% "play-json" % playJson,
      "com.softwaremill.sttp.client3" %%% "core" % sttp,
      "org.scala-js" %%% "scala-js-macrotask-executor" % "1.1.1",
      "com.olvind.st-material-ui" %%% "st-material-ui-icons-slinky" % "5.11.16",
      "io.monix" %%% "monix-reactive" % "3.4.1",
      "com.softwaremill.sttp.tapir" %%% "tapir-sttp-client" % tapir
    )
  )
  val webTest: Def.Initialize[Seq[ModuleID]] = Def.setting(
    Seq("org.scalatest" %%% "scalatest" % scalatest % Test)
  )

  // Shared Scala.js time libraries used by baseWebSettings.
  val scalaJsTime: Def.Initialize[Seq[ModuleID]] = Def.setting(
    Seq(
      "io.github.cquiroz" %%% "scala-java-time" % scalaJavaTime,
      "io.github.cquiroz" %%% "scala-java-time-tzdb" % scalaJavaTime
    )
  )

  // slinky hot-reload helper used by baseWebSettings.
  val slinkyHot: Def.Initialize[ModuleID] = Def.setting("me.shadaj" %%% "slinky-hot" % "0.7.5")

  // server (Play backend)
  val server: Seq[ModuleID] = Seq(
    "org.playframework.anorm" %% "anorm" % anorm,
    "org.playframework.anorm" %% "anorm-postgres" % anorm,
    "org.playframework.anorm" %% "anorm-pekko" % anorm,
    "org.playframework" %% "play-json" % playJson,
    "org.postgresql" % "postgresql" % "42.7.11",
    "de.svenkubiak" % "jBCrypt" % "0.4.3",
    "commons-validator" % "commons-validator" % "1.10.1",
    "com.dimafeng" %% "testcontainers-scala-scalatest" % "0.44.1" % "test",
    "com.dimafeng" %% "testcontainers-scala-postgresql" % "0.44.1" % "test",
    "com.dimafeng" %% "testcontainers-scala-localstack-v2" % "0.44.1" % "test",
    "com.softwaremill.sttp.client3" %% "core" % sttp % "test",
    "com.softwaremill.sttp.client3" %% "async-http-client-backend-future" % sttp % "test",
    "software.amazon.awssdk" % "ses" % "2.44.9",
    "software.amazon.awssdk" % "s3" % "2.44.9",
    "software.amazon.awssdk" % "textract" % "2.44.9",
    "software.amazon.awssdk" % "bedrockruntime" % "2.44.9",
    "software.amazon.awssdk" % "bedrockdataautomationruntime" % "2.44.9",
    "jakarta.xml.bind" % "jakarta.xml.bind-api" % "4.0.5",
    "org.apache.commons" % "commons-text" % "1.15.0",
    "org.apache.pdfbox" % "pdfbox" % "3.0.3",
    // JAX-B dependencies for JDK 9+, required to use play sessions
    "javax.xml.bind" % "jaxb-api" % "2.3.1",
    "javax.annotation" % "javax.annotation-api" % "1.3.2",
    "javax.el" % "javax.el-api" % "3.0.0",
    "org.glassfish" % "javax.el" % "3.0.0",
    "io.scalaland" %% "chimney" % chimney,
    "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % tapir,
    "com.softwaremill.sttp.tapir" %% "tapir-json-play" % tapir,
    "com.softwaremill.sttp.tapir" %% "tapir-play-server" % tapir,
    "org.apache.pekko" %% "pekko-stream" % pekko,
    "com.github.f4b6a3" % "uuid-creator" % "5.3.3",
    "com.bot4s" %% "telegram-core" % "7.0.0",
    "com.softwaremill.sttp.client4" %% "okhttp-backend" % "4.0.13"
  )

  // Play's own modules plus guice, added by playSettings.
  val playBundle: Seq[ModuleID] = Seq(
    guice,
    evolutions,
    jdbc,
    ws,
    "com.google.inject" % "guice" % "6.0.0"
  )
  val playTest: Seq[ModuleID] = Seq(
    "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.2" % Test,
    "org.scalatestplus" %% "mockito-4-11" % "3.2.18.0" % Test
  )
}
