// Reusable settings live in project/BuildSettings.scala; dependency versions and groups live in
// project/Dependencies.scala. Only the module graph and build-wide settings stay here.
import BuildSettings._

ThisBuild / scalaVersion := "3.8.3"
ThisBuild / organization := "net.wiringbits"

// scalajs-bundler and ScalablyTyped plugin keys that sbt's lintUnused reports as unused even
// though those plugins' own tasks read them. Exclude them so the false positives stop showing
// on every build.
Global / excludeLintKeys += webpackExtraArgs
Global / excludeLintKeys += webpackDevServerPort
Global / excludeLintKeys += stMinimize

ThisBuild / dependencyOverrides ++= Dependencies.pekkoOverrides

lazy val common = (crossProject(JSPlatform, JVMPlatform) in file("lib/common"))
  .configure(commonSettings)
  .jsConfigure(_.enablePlugins(ScalaJSPlugin, ScalaJSBundlerPlugin, ScalablyTypedConverterPlugin))
  .jsConfigure(scalaJsModuleSettings)
  .jvmSettings(
    libraryDependencies ++= Dependencies.commonJvm
  )
  .jsSettings(
    libraryDependencies ++= Dependencies.commonJs.value
  )

// shared apis
lazy val api = (crossProject(JSPlatform, JVMPlatform) in file("lib/api"))
  .dependsOn(common)
  .configure(commonSettings)
  .jsConfigure(_.enablePlugins(ScalaJSPlugin, ScalaJSBundlerPlugin, ScalablyTypedConverterPlugin))
  .jsConfigure(scalaJsModuleSettings)
  .jvmSettings(
    libraryDependencies ++= Dependencies.apiJvm
  )
  .jsSettings(
    libraryDependencies ++= Dependencies.apiJs.value
  )

lazy val server = (project in file("server"))
  .dependsOn(common.jvm, api.jvm)
  .configure(baseServerSettings, commonSettings, playSettings)
  .settings(
    // The backend should not produce inferred Any. Anorm (dynamically typed column values) and the sttp
    // uri interpolator are the exceptions, suppressed at their definitions.
    Compile / compile / wartremoverErrors += Wart.Any,
    name := "wiringbits-server",
    fork := true,
    // In CI, run suites in parallel in a single JVM sharing the one Postgres container (scaled to cores via
    // Tags.CPU) for ~35% faster tests. Locally keep fork := true: it runs serially with graceful container
    // shutdown, and avoids the thin-client server holding the container open between runs. The
    // background_jobs-queue specs use IsolatedPostgresSpec so their global DELETE can't race a concurrent suite.
    Test / fork := !sys.env.get("CI").exists(_.nonEmpty),
    Test / parallelExecution := sys.env.get("CI").exists(_.nonEmpty),
    libraryDependencies ++= Dependencies.server
  )

lazy val web = (project in file("web"))
  .dependsOn(common.js, api.js)
  .enablePlugins(ScalablyTypedConverterPlugin)
  .configure(
    baseWebSettings,
    browserProject,
    commonSettings,
    scalaJsModuleSettings,
    reactNpmDeps,
    withCssLoading,
    bundlerSettings,
    webBuildInfoSettings
  )
  .settings(
    // Scala.js UI boundary: casts to DOM/JS facades (MUI callbacks, js.Dynamic) are unavoidable here.
    Compile / compile / wartremoverErrors -= Wart.AsInstanceOf,
    name := "wiringbits-web",
    webpackDevServerPort := 8080,
    // sbt-scalajs-bundler only copies *.js to the webpack working dir by default;
    // index.html must also be there so webpack-dev-server can serve it at "/".
    Compile / webpackResources := (baseDirectory.value / "src" / "main" / "js") ** "*.html",
    stFlavour := Flavour.Slinky,
    stReactEnableTreeShaking := Selection.All,
    // material-ui is provided by a pre-packaged library
    stIgnore ++= Dependencies.webStIgnore,
    Compile / npmDependencies ++= Dependencies.webNpmDependencies,
    libraryDependencies ++= Dependencies.web.value,
    libraryDependencies ++= Dependencies.webTest.value,
    // sbt-scalajs-bundler defaults to webpack-cli 4.5.0 which ships with @webpack-cli/serve@^1.3.0.
    // yarn now resolves that range to 1.7.0, which requires internal APIs added in webpack-cli 4.7+.
    // Upgrading to 4.10.0 (latest 4.x) pins the correct @webpack-cli/serve@^1.7.0 from the start.
    webpackCliVersion := "4.10.0"
  )

lazy val root = (project in file("."))
  .aggregate(
    common.jvm,
    common.js,
    api.jvm,
    api.js,
    server,
    web
  )
  .settings(
    publish := {},
    publishLocal := {}
  )

addCommandAlias("dev-web", ";web/fastOptJS/startWebpackDevServer;~web/fastOptJS")
