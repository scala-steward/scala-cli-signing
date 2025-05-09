import $ivy.`io.github.alexarchambault.mill::mill-native-image::0.1.31-1`
import $ivy.`io.github.alexarchambault.mill::mill-native-image-upload:0.1.31-1`

import $file.publish, publish.{finalPublishVersion, publishSonatype => publishSonatype0}

import io.github.alexarchambault.millnativeimage.NativeImage
import io.github.alexarchambault.millnativeimage.upload.Upload
import mill._, scalalib._

import java.io.File

object Deps {
  object Versions {
    def jsoniterScala = "2.35.2"
    def bouncycastle  = "1.80"
  }
  def bouncycastle      = ivy"org.bouncycastle:bcpg-jdk18on:${Versions.bouncycastle}"
  def bouncycastleUtils = ivy"org.bouncycastle:bcutil-jdk18on:${Versions.bouncycastle}"
  def caseApp           = ivy"com.github.alexarchambault::case-app:2.1.0-M30"
  def coursierPublish   = ivy"io.get-coursier.publish:publish_2.13:0.2.0"
  def expecty           = ivy"com.eed3si9n.expecty::expecty:0.17.0"
  def jsoniterCore =
    ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core:${Versions.jsoniterScala}"
  def jsoniterMacros =
    ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros:${Versions.jsoniterScala}"
  def munit = ivy"org.scalameta::munit:1.1.1"
  def osLib = ivy"com.lihaoyi::os-lib:0.11.4"
  def svm   = ivy"org.graalvm.nativeimage:svm:$graalVmVersion"

  def graalVmVersion      = "22.3.1"
  def graalVmId           = s"graalvm-java17:$graalVmVersion"
  def coursierVersion     = "2.1.24"
  def ubuntuDockerVersion = "ubuntu:24.04"
}

object Scala {
  def scala213 = "2.13.16"
  def scala3   = "3.3.5"
}

def ghOrg  = "VirtusLab"
def ghName = "scala-cli-signing"
trait ScalaCliSigningPublish extends PublishModule {
  import mill.scalalib.publish._
  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "org.virtuslab.scala-cli-signing",
    url = s"https://github.com/$ghOrg/$ghName",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github(ghOrg, ghName),
    developers = Seq(
      Developer("alexarchambault", "Alex Archambault", "https://github.com/alexarchambault")
    )
  )
  def publishVersion = finalPublishVersion()
}

object shared extends Cross[Shared](Scala.scala213, Scala.scala3)
trait Shared extends CrossScalaModule with ScalaCliSigningPublish {
  override val crossScalaVersion = crossValue
  def ivyDeps = super.ivyDeps() ++ Seq(
    Deps.jsoniterCore,
    Deps.osLib
  )
  def compileIvyDeps = super.ivyDeps() ++ Seq(
    Deps.jsoniterMacros
  )
}

trait CliNativeImage extends NativeImage {
  def nativeImagePersist      = System.getenv("CI") != null
  def nativeImageGraalVmJvmId = Deps.graalVmId
  def nativeImageName         = "scala-cli-signing"
  def nativeImageClassPath    = `native-cli`.runClasspath()
  def nativeImageMainClass = T {
    `native-cli`.mainClass().getOrElse(sys.error("no main class found"))
  }
  def nativeImageOptions = super.nativeImageOptions() ++ Seq(
    "--no-fallback",
    "--rerun-class-initialization-at-runtime=org.bouncycastle.jcajce.provider.drbg.DRBG$Default,org.bouncycastle.jcajce.provider.drbg.DRBG$NonceAndIV"
  )

  def nameSuffix = ""
  def copyToArtifacts(directory: String = "artifacts/") = T.command {
    val _ = Upload.copyLauncher(
      nativeImage().path,
      directory,
      "scala-cli-signing",
      compress = true,
      suffix = nameSuffix
    )
  }
}

object cli extends Cross[Cli](Scala.scala213, Scala.scala3)
trait Cli extends CrossScalaModule with ScalaCliSigningPublish {
  self =>
  override val crossScalaVersion = crossValue
  def ivyDeps = super.ivyDeps() ++ Seq(
    Deps.bouncycastle,
    Deps.bouncycastleUtils,
    Deps.caseApp,
    Deps.coursierPublish // we can probably get rid of that one
  )
  def moduleDeps = Seq(
    shared()
  )
  def mainClass = Some("scala.cli.signing.ScalaCliSigning")

  object test extends ScalaTests with TestModule.Munit {
    def ivyDeps = super.ivyDeps() ++ Agg(
      Deps.expecty,
      Deps.munit,
      Deps.jsoniterMacros
    )
    override def forkArgs: T[Seq[String]] = T {
      super.forkArgs() ++ Seq("-Xmx512m", "-Xms128m", "--add-opens=java.base/java.util=ALL-UNNAMED")
    }

    override def scalaVersion = crossScalaVersion
  }
}
object `native-cli` extends ScalaModule with ScalaCliSigningPublish { self =>
  private def scalaVer = Scala.scala3
  def scalaVersion     = scalaVer
  def ivyDeps = super.ivyDeps() ++ Seq(
    Deps.svm
  )
  def moduleDeps = Seq(
    cli(scalaVer)
  )

  def mainClass = cli(scalaVer).mainClass()

  object `base-image` extends CliNativeImage
  object `static-image` extends CliNativeImage {
    private def helperImageName = "scala-cli-signing-musl"
    def nativeImageDockerParams = T {
      buildHelperImage()
      Some(
        NativeImage.linuxStaticParams(
          s"$helperImageName:latest",
          s"https://github.com/coursier/coursier/releases/download/v${Deps.coursierVersion}/cs-x86_64-pc-linux.gz"
        )
      )
    }
    def buildHelperImage = T {
      os.proc("docker", "build", "-t", helperImageName, ".")
        .call(cwd = os.pwd / "project" / "musl-image", stdout = os.Inherit)
      ()
    }
    def writeNativeImageScript(scriptDest: String, imageDest: String = "") = T.command {
      buildHelperImage()
      super.writeNativeImageScript(scriptDest, imageDest)()
    }
    def nameSuffix = "-static"
  }

  object `mostly-static-image` extends CliNativeImage {
    def nativeImageDockerParams = Some(
      NativeImage.linuxMostlyStaticParams(
        Deps.ubuntuDockerVersion,
        s"https://github.com/coursier/coursier/releases/download/v${Deps.coursierVersion}/cs-x86_64-pc-linux.gz"
      )
    )
    def nameSuffix = "-mostly-static"
  }
}

def tmpDirBase = T.persistent {
  PathRef(T.dest / "working-dir")
}

trait CliTests extends ScalaModule {
  def testLauncher: T[PathRef]
  def cliKind: T[String]

  override def scalaVersion = Scala.scala3

  def prefix = "integration-"
  private def updateRef(name: String, ref: PathRef): PathRef = {
    val rawPath = ref.path.toString.replace(
      File.separator + name + File.separator,
      File.separator
    )
    PathRef(os.Path(rawPath))
  }
  private def mainArtifactName = T(artifactName())
  def modulesPath = T {
    val name                = mainArtifactName().stripPrefix(prefix)
    val baseIntegrationPath = os.Path(millSourcePath.toString.stripSuffix(name))
    val p = os.Path(
      baseIntegrationPath.toString.stripSuffix(baseIntegrationPath.baseName)
    )
    PathRef(p)
  }
  def sources = T.sources {
    val mainPath = PathRef(modulesPath().path / "integration" / "src" / "main" / "scala")
    super.sources() ++ Seq(mainPath)
  }
  def resources = T.sources {
    val mainPath = PathRef(modulesPath().path / "integration" / "src" / "main" / "resources")
    super.resources() ++ Seq(mainPath)
  }

  trait Tests extends ScalaTests with TestModule.Munit {
    def ivyDeps = super.ivyDeps() ++ Agg(
      Deps.expecty,
      Deps.munit,
      Deps.osLib
    )
    def testFramework = "munit.Framework"
    def forkArgs      = super.forkArgs() ++ Seq("-Xmx512m", "-Xms128m")
    def forkEnv = super.forkEnv() ++ Seq(
      "SIGNING_CLI"      -> testLauncher().path.toString,
      "SIGNING_CLI_KIND" -> cliKind(),
      "SIGNING_CLI_TMP"  -> tmpDirBase().path.toString
    )

    def sources = T.sources {
      val name = mainArtifactName().stripPrefix(prefix)
      super.sources().flatMap { ref =>
        Seq(updateRef(name, ref), ref)
      }
    }
    def resources = T.sources {
      val name = mainArtifactName().stripPrefix(prefix)
      super.resources().flatMap { ref =>
        Seq(updateRef(name, ref), ref)
      }
    }
  }
}

object `jvm-integration` extends Cross[JvmIntegration](Scala.scala213, Scala.scala3)
trait JvmIntegration extends CrossScalaModule with CliTests { self =>
  scalaVersion
  override val crossScalaVersion = crossValue
  def testLauncher               = cli(crossScalaVersion).launcher()
  def cliKind                    = "jvm"

  object test extends Tests
}
object `native-integration` extends Module {
  object native extends CliTests {
    def testLauncher = `native-cli`.`base-image`.nativeImage()
    def cliKind      = "native"

    object test extends Tests
  }
  object static extends CliTests {
    def testLauncher = `native-cli`.`static-image`.nativeImage()
    def cliKind      = "native-static"

    object test extends Tests
  }
  object `mostly-static` extends CliTests {
    def testLauncher = `native-cli`.`mostly-static-image`.nativeImage()
    def cliKind      = "native-mostly-static"

    object test extends Tests
  }
}

object ci extends Module {
  def upload(directory: String = "artifacts/") = T.command {
    val version = finalPublishVersion()

    val path      = os.Path(directory, os.pwd)
    val launchers = os.list(path).filter(os.isFile(_)).map(path => path -> path.last)
    val ghToken = Option(System.getenv("UPLOAD_GH_TOKEN")).getOrElse {
      sys.error("UPLOAD_GH_TOKEN not set")
    }
    val (tag, overwriteAssets) =
      if (version.endsWith("-SNAPSHOT")) ("launchers", true)
      else ("v" + version, false)

    Upload.upload(
      ghOrg,
      ghName,
      ghToken,
      tag,
      dryRun = false,
      overwrite = overwriteAssets
    )(launchers: _*)
  }

  def publishSonatype(tasks: mill.main.Tasks[PublishModule.PublishData]) = T.command {
    publishSonatype0(
      data = define.Target.sequence(tasks.value)(),
      log = T.ctx().log
    )
  }

  def copyJvm(jvm: String = Deps.graalVmId, dest: String = "jvm") = T.command {
    import sys.process._
    val command = os.proc(
      "cs",
      "java-home",
      "--jvm",
      jvm,
      "--update",
      "--ttl",
      "0"
    )
    val baseJavaHome = os.Path(command.call().out.text().trim, os.pwd)
    System.err.println(s"Initial Java home $baseJavaHome")
    val destJavaHome = os.Path(dest, os.pwd)
    os.copy(baseJavaHome, destJavaHome, createFolders = true)
    System.err.println(s"New Java home $destJavaHome")
    destJavaHome
  }
}
