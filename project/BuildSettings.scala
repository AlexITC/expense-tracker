import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

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
      // Enable fatal warnings only when running in the CI
      scalacOptions ++= {
        sys.env
          .get("CI")
          .filter(_.nonEmpty)
          .map(_ => Seq("-Werror"))
          .getOrElse(Seq.empty[String])
      },
      // ScalablyTyped-generated inline code uses scala.scalajs.runtime.linkingInfo which was deprecated
      // in Scala.js 1.18.0. We can't fix it in the generated code, so suppress the warning.
      scalacOptions += "-Wconf:cat=deprecation&origin=scala.scalajs.runtime.*:s",
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
      // `%%` resolves the JVM `_3` artifact even in Scala.js modules (no `_sjs1` suffix); the wart runs on
      // the JVM at compile time.
      libraryDependencies += "net.wiringbits" %% "core-warts" % "0.5.3",
      wartremoverClasspaths ++= {
        (Compile / dependencyClasspath).value.files
          .find(_.name.contains("core-warts"))
          .map(_.toURI.toString)
          .toList
      }
    )
  }

  // TODO: Reuse it in all projects
  lazy val baseServerSettings: Project => Project = {
    _.settings(
      scalacOptions ++= Seq(
        "-unchecked",
        "-deprecation",
        "-feature"
      ),
      Compile / doc / scalacOptions ++= Seq("-no-link-warnings"),
      // Some options are very noisy when using the console and prevent us using it smoothly, let's disable them
      Compile / console / scalacOptions ~= (_.filterNot(consoleDisabledOptions.contains))
    )
  }

  // Used only by web projects
  lazy val baseWebSettings: Project => Project =
    _.enablePlugins(ScalaJSPlugin)
      .settings(
        scalacOptions ++= Seq(
          "-deprecation", // Emit warning and location for usages of deprecated APIs.
          "-explaintypes", // Explain type errors in more detail.
          "-feature", // Emit warning and location for usages of features that should be imported explicitly.
          "-unchecked" // Enable additional warnings where generated code depends on assumptions.
        ),
        scalaJSUseMainModuleInitializer := true,
        /* disabled because it somehow triggers many warnings */
        scalaJSLinkerConfig := scalaJSLinkerConfig.value.withSourceMap(false),
        /* for slinky */
        libraryDependencies += Dependencies.slinkyHot.value,
        libraryDependencies ++= Dependencies.scalaJsTime.value,
        Test / fork := false, // sjs needs this to run tests
        Test / requireJsDomEnv := true
      )

  // Used only by the lib projects
  // TODO: This should go to commonSettings instead
  lazy val baseLibSettings: Project => Project = _.settings(
    scalacOptions ++= Seq(
      "-deprecation", // Emit warning and location for usages of deprecated APIs.
      "-encoding",
      "utf-8", // Specify character encoding used by source files.
      "-explaintypes", // Explain type errors in more detail.
      "-feature", // Emit warning and location for usages of features that should be imported explicitly.
      "-unchecked" // Enable additional warnings where generated code depends on assumptions.
    )
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
      Compile / npmDependencies ++= Seq(
        "react" -> "18.2.0",
        "@types/react" -> "18.0.33",
        "react-dom" -> "18.2.0",
        "@types/react-dom" -> "18.0.11",
        "csstype" -> "2.6.11",
        "react-proxy" -> "1.1.8",
        "@types/prop-types" -> "15.7.3"
      )
    )

  lazy val withCssLoading: Project => Project =
    _.settings(
      /* custom webpack file to include css */
      Compile / webpackConfigFile := Some((ThisBuild / baseDirectory).value / "custom.webpack.config.js"),
      Test / webpackConfigFile := None, // it is important to avoid the custom webpack config in tests to get them passing
      Compile / npmDevDependencies ++= Seq(
        "webpack-merge" -> "4.2.2",
        "css-loader" -> "3.4.2",
        "style-loader" -> "1.1.3",
        "file-loader" -> "5.1.0",
        "url-loader" -> "3.0.0"
      )
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
