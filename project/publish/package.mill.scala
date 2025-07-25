package build.project.publish
import com.lumidion.sonatype.central.client.core.{PublishingType, SonatypeCredentials}

import mill._
import mill.util.VcsVersion
import scalalib._

import scala.annotation.unused
import scala.concurrent.duration._

private def computePublishVersion(state: VcsVersion.State, simple: Boolean): String =
  if (state.commitsSinceLastTag > 0)
    if (simple) {
      val versionOrEmpty = state.lastTag
        .filter(_ != "latest")
        .filter(_ != "nightly")
        .map(_.stripPrefix("v"))
        .flatMap { tag =>
          if (simple) {
            val idx = tag.lastIndexOf(".")
            if (idx >= 0)
              Some(tag.take(idx + 1) + (tag.drop(idx + 1).toInt + 1).toString + "-SNAPSHOT")
            else
              None
          }
          else {
            val idx = tag.indexOf("-")
            if (idx >= 0) Some(tag.take(idx) + "+" + tag.drop(idx + 1) + "-SNAPSHOT")
            else None
          }
        }
        .getOrElse("0.0.1-SNAPSHOT")
      Some(versionOrEmpty)
        .filter(_.nonEmpty)
        .getOrElse(state.format())
    }
    else {
      val rawVersion = os.proc("git", "describe", "--tags").call().out.text().trim
        .stripPrefix("v")
        .replace("latest", "0.0.0")
        .replace("nightly", "0.0.0")
      val idx = rawVersion.indexOf("-")
      if (idx >= 0) rawVersion.take(idx) + "+" + rawVersion.drop(idx + 1) + "-SNAPSHOT"
      else rawVersion
    }
  else
    state
      .lastTag
      .getOrElse(state.format())
      .stripPrefix("v")

def finalPublishVersion: T[String] = {
  val isCI = System.getenv("CI") != null
  if (isCI)
    Task(persistent = true) {
      val state = VcsVersion.vcsState()
      computePublishVersion(state, simple = false)
    }
  else
    Task {
      val state = VcsVersion.vcsState()
      computePublishVersion(state, simple = true)
    }
}

def publishSonatype(
  data: Seq[PublishModule.PublishData],
  log: mill.api.Logger,
  workspace: os.Path,
  env: Map[String, String],
  bundleName: String
): Unit = {
  val credentials = SonatypeCredentials(
    username = sys.env("SONATYPE_USERNAME"),
    password = sys.env("SONATYPE_PASSWORD")
  )
  val pgpPassword = sys.env("PGP_PASSWORD")
  val timeout     = 10.minutes

  val artifacts = data.map {
    case PublishModule.PublishData(a, s) =>
      (s.map { case (p, f) => (p.path, f) }, a)
  }

  val isRelease = {
    val versions = artifacts.map(_._2.version).toSet
    val set      = versions.map(!_.endsWith("-SNAPSHOT"))
    assert(
      set.size == 1,
      s"Found both snapshot and non-snapshot versions: ${versions.toVector.sorted.mkString(", ")}"
    )
    set.head
  }
  val publisher = new SonatypeCentralPublisher(
    credentials = credentials,
    gpgArgs = Seq(
      "--detach-sign",
      "--batch=true",
      "--yes",
      "--pinentry-mode",
      "loopback",
      "--passphrase",
      pgpPassword,
      "--armor",
      "--use-agent"
    ),
    readTimeout = timeout.toMillis.toInt,
    connectTimeout = timeout.toMillis.toInt,
    log = log,
    workspace = workspace,
    env = env,
    awaitTimeout = timeout.toMillis.toInt
  )
  val publishingType  = if (isRelease) PublishingType.AUTOMATIC else PublishingType.USER_MANAGED
  val finalBundleName = if (bundleName.nonEmpty) Some(bundleName) else None
  publisher.publishAll(
    publishingType = publishingType,
    singleBundleName = finalBundleName,
    artifacts = artifacts *
  )
}

// from https://github.com/sbt/sbt-ci-release/blob/35b3d02cc6c247e1bb6c10dd992634aa8b3fe71f/plugin/src/main/scala/com/geirsson/CiReleasePlugin.scala#L33-L39
@unused
def isTag: Boolean =
  Option(System.getenv("TRAVIS_TAG")).exists(_.nonEmpty) ||
  Option(System.getenv("CIRCLE_TAG")).exists(_.nonEmpty) ||
  Option(System.getenv("CI_COMMIT_TAG")).exists(_.nonEmpty) ||
  Option(System.getenv("BUILD_SOURCEBRANCH"))
    .orElse(Option(System.getenv("GITHUB_REF")))
    .exists(_.startsWith("refs/tags"))
