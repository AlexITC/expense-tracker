import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

import org.scalablytyped.converter.Selection
import org.scalablytyped.converter.plugin.ScalablyTypedConverterGenSourcePlugin.autoImport.stMinimize
import org.scalablytyped.converter.plugin.ScalablyTypedPluginBase.autoImport._
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._
import play.sbt.routes.RoutesKeys
import play.sbt.{PlayLayoutPlugin, PlayScala}
import sbt.Keys._
import sbt._
import sbtbuildinfo.BuildInfoPlugin
import sbtbuildinfo.BuildInfoPlugin.autoImport._
import sbtbuildinfo.{BuildInfoKey => BuildInfoKeyType}
import scalajsbundler.sbtplugin.ScalaJSBundlerPlugin.autoImport._
import wartremover.WartRemover.autoImport._

/** Reusable project settings. The module definitions live in build.sbt and apply these via `.configure`; dependency
  * versions and groups live in project/Dependencies.scala.
  */
object BuildSettings {

  val consoleDisabledOptions: Seq[String] = Seq("-Werror", "-Ywarn-unused", "-Ywarn-unused-import")

  /** Say just `build` or `sbt build` to make a production bundle in `build`. */
  lazy val build = TaskKey[File]("build")

  // In automated flows (CI, and orca/claude which run with CI=true) lock yarn.lock so sbt-driven installs
  // don't rewrite it. A plain local `sbt` leaves CI unset, keeping today's behavior: updating a dependency
  // regenerates the lock as before.
  lazy val yarnLockfileArgs: Seq[String] =
    sys.env.get("CI").filter(_.nonEmpty).map(_ => Seq("--pure-lockfile")).getOrElse(Seq.empty[String])

  lazy val commonSettings: Project => Project = {
    _.settings(
      // Options every module wants: a fixed source encoding, the standard warning set, and more
      // detailed type-error output. Module-specific extras live in the base* settings below.
      scalacOptions ++= Seq(
        "-encoding",
        "utf-8", // Specify character encoding used by source files.
        "-deprecation",
        "-feature",
        "-unchecked",
        "-explaintypes" // Explain type errors in more detail.
      ),
      // Enable fatal warnings only when running in the CI
      scalacOptions ++= {
        sys.env
          .get("CI")
          .filter(_.nonEmpty)
          .map(_ => Seq("-Werror"))
          .getOrElse(Seq.empty[String])
      },
      Compile / compile / wartremoverErrors ++= List(
        Wart.ArrayEquals,
        //      Wart.Any,
        Wart.AsInstanceOf,
        Wart.ExplicitImplicitTypes,
        Wart.IsInstanceOf,
        Wart.JavaConversions,
        Wart.JavaSerializable,
        Wart.MutableDataStructures,
        Wart.NonUnitStatements,
        //      Wart.Nothing,
        Wart.Null,
        Wart.OptionPartial,
        //      Wart.Overloading,
        Wart.Product,
        Wart.PublicInference,
        Wart.Return,
        Wart.Serializable,
        Wart.StringPlusAny,
        //      Wart.ToString,
        Wart.TryPartial,
        // From wiringbits-scala-newbie-warts (core-warts): flags calls that pass two or more same-typed
        // arguments positionally, where an accidental swap would compile silently. Pass them by name.
        Wart.custom("net.wiringbits.warts.NamedArguments")
      ),
      // Compile-only wart: wartremoverDependencies puts it (and its transitive deps) on the wart
      // classloader without adding it to the app classpath. `%%` resolves the JVM `_3` artifact even in
      // Scala.js modules (wartremoverCrossVersion is binary); the wart runs on the JVM at compile time.
      wartremoverDependencies += "net.wiringbits" %% "core-warts" % Dependencies.coreWarts
    )
  }

  // Shared by every Scala.js module (lib/common.js, lib/api.js, web).
  lazy val scalaJsModuleSettings: Project => Project = _.settings(
    useYarn := true,
    yarnExtraArgs ++= yarnLockfileArgs,
    Test / fork := false, // sjs needs this to run tests
    stUseScalaJsDom := true,
    Compile / stMinimize := Selection.All,
    // ScalablyTyped-generated inline code uses scala.scalajs.runtime.linkingInfo which was deprecated
    // in Scala.js 1.18.0. We can't fix the generated code, so suppress the warning. Only Scala.js
    // modules reference it, so it belongs here rather than in commonSettings.
    scalacOptions += "-Wconf:cat=deprecation&origin=scala.scalajs.runtime.*:s"
  )

  lazy val baseServerSettings: Project => Project = {
    _.settings(
      // Some options are very noisy when using the console and prevent us using it smoothly, let's disable them
      Compile / console / scalacOptions ~= (_.filterNot(consoleDisabledOptions.contains))
    )
  }

  // Used only by web projects
  lazy val baseWebSettings: Project => Project =
    _.enablePlugins(ScalaJSPlugin)
      .settings(
        scalaJSUseMainModuleInitializer := true,
        /* disabled because it somehow triggers many warnings */
        scalaJSLinkerConfig := scalaJSLinkerConfig.value.withSourceMap(false),
        /* for slinky */
        libraryDependencies += Dependencies.slinkyHot.value,
        libraryDependencies ++= Dependencies.scalaJsTime.value,
        Test / requireJsDomEnv := true
      )

  /** Implement the `build` task defined above. Most of this is really just to copy the index.html file around.
    */
  lazy val browserProject: Project => Project =
    _.settings(
      build := {
        val artifacts = (Compile / fullOptJS / webpack).value
        val artifactFolder = (Compile / fullOptJS / crossTarget).value
        val jsFolder = baseDirectory.value / "src" / "main" / "js"
        val distFolder = baseDirectory.value / "build"

        distFolder.mkdirs()
        artifacts.foreach { artifact =>
          val target = artifact.data.relativeTo(artifactFolder) match {
            case None => distFolder / artifact.data.name
            case Some(relFile) => distFolder / relFile.toString
          }

          Files.copy(artifact.data.toPath, target.toPath, REPLACE_EXISTING)
        }

        // copy public resources
        Files
          .walk(jsFolder.toPath)
          .filter(x => !Files.isDirectory(x))
          .forEach(source => {
            source.toFile.relativeTo(jsFolder).foreach { relativeSource =>
              val dest = distFolder / relativeSource.toString
              dest.getParentFile.mkdirs()
              Files.copy(source, dest.toPath, REPLACE_EXISTING)
            }
          })

        // link the proper js bundle
        val indexFrom = baseDirectory.value / "src/main/js/index.html"
        val indexTo = distFolder / "index.html"

        val indexPatchedContent = {
          import collection.JavaConverters._
          Files
            .readAllLines(indexFrom.toPath, IO.utf8)
            .asScala
            .map(_.replaceAllLiterally("-fastopt", "-opt"))
            .mkString("\n")
        }

        Files.write(indexTo.toPath, indexPatchedContent.getBytes(IO.utf8))
        distFolder
      }
    )

  // specify versions for all of reacts dependencies
  lazy val reactNpmDeps: Project => Project =
    _.settings(
      stTypescriptVersion := "3.9.3",
      stIgnore += "react-proxy",
      Compile / npmDependencies ++= Dependencies.reactNpmDependencies
    )

  lazy val withCssLoading: Project => Project =
    _.settings(
      /* custom webpack file to include css */
      Compile / webpackConfigFile := Some((ThisBuild / baseDirectory).value / "custom.webpack.config.js"),
      Test / webpackConfigFile := None, // it is important to avoid the custom webpack config in tests to get them passing
      Compile / npmDevDependencies ++= Dependencies.cssNpmDevDependencies
    )

  lazy val bundlerSettings: Project => Project =
    _.settings(
      Compile / fastOptJS / webpackExtraArgs += "--mode=development",
      Compile / fullOptJS / webpackExtraArgs += "--mode=production",
      Compile / fastOptJS / webpackDevServerExtraArgs += "--mode=development",
      Compile / fullOptJS / webpackDevServerExtraArgs += "--mode=production"
    )

  // Used only by play-based projects
  lazy val playSettings: Project => Project = {
    _.enablePlugins(PlayScala)
      .disablePlugins(PlayLayoutPlugin)
      .settings(
        // docs are huge and unnecessary
        Compile / doc / sources := Nil,
        Compile / doc / scalacOptions ++= Seq(
          "-no-link-warnings"
        ),
        // remove play noisy warnings
        RoutesKeys.routesImport := Seq.empty,
        // Play routes and Twirl templates compile to generated Scala (under crossTarget) with positional
        // reverse-router and template calls we cannot annotate. Exclude those generated source directories
        // from all warts (NamedArguments in particular). wartremover matches the generated compilation-unit
        // path, and wartremoverExcluded is read at project scope by the plugin, not `Compile / compile`.
        // Referencing crossTarget (a setting) avoids the cycle that managedSources (a task) would create.
        wartremoverExcluded ++= Seq(crossTarget.value / "routes", crossTarget.value / "twirl"),
        libraryDependencies ++= Dependencies.playBundle,
        libraryDependencies ++= Dependencies.playTest
      )
  }

  lazy val webBuildInfoSettings: Project => Project = _.enablePlugins(BuildInfoPlugin)
    .settings(
      buildInfoKeys := Seq[BuildInfoKeyType](name, version, scalaVersion, sbtVersion),
      buildInfoKeys ++= {
        val apiUrl = sys.env.get("API_URL")
        val values = Seq(
          "apiUrl" -> apiUrl
        )
        // Logging these values is useful to make sure that the necessary settings
        // are being overriden when packaging the app.
        sLog.value.info(s"BuildInfo settings:\n${values.mkString("\n")}")
        values.map(t => BuildInfoKeyType(t._1, t._2))
      },
      buildInfoPackage := "net.wiringbits",
      buildInfoUsePackageAsPath := true
    )
}
