package coursier.sbtcoursier

import java.io.OutputStreamWriter

import coursier.{Cache, CachePolicy, TermDisplay}
import coursier.core.{Configuration, ResolutionProcess}
import coursier.sbtcoursiershared.SbtCoursierShared
import sbt.{Cache => _, Configuration => _, _}
import sbt.Keys._

object CoursierPlugin extends AutoPlugin {

  override def trigger = allRequirements

  override def requires = SbtCoursierShared

  object autoImport {
    val coursierParallelDownloads = Keys.coursierParallelDownloads
    val coursierMaxIterations = Keys.coursierMaxIterations
    val coursierChecksums = Keys.coursierChecksums
    val coursierArtifactsChecksums = Keys.coursierArtifactsChecksums
    val coursierCachePolicies = Keys.coursierCachePolicies
    val coursierTtl = Keys.coursierTtl
    val coursierKeepPreloaded = Keys.coursierKeepPreloaded
    val coursierVerbosity = Keys.coursierVerbosity
    val mavenProfiles = Keys.mavenProfiles
    val coursierResolvers = Keys.coursierResolvers
    val coursierReorderResolvers = Keys.coursierReorderResolvers
    val coursierRecursiveResolvers = Keys.coursierRecursiveResolvers
    val coursierSbtResolvers = Keys.coursierSbtResolvers
    val coursierUseSbtCredentials = Keys.coursierUseSbtCredentials
    val coursierCredentials = Keys.coursierCredentials
    val coursierFallbackDependencies = Keys.coursierFallbackDependencies
    val coursierCache = Keys.coursierCache
    val coursierConfigGraphs = Keys.coursierConfigGraphs
    val coursierSbtClassifiersModule = Keys.coursierSbtClassifiersModule

    val coursierConfigurations = Keys.coursierConfigurations

    val coursierParentProjectCache = Keys.coursierParentProjectCache
    val coursierResolutions = Keys.coursierResolutions

    val coursierSbtClassifiersResolution = Keys.coursierSbtClassifiersResolution

    val coursierDependencyTree = Keys.coursierDependencyTree
    val coursierDependencyInverseTree = Keys.coursierDependencyInverseTree
    val coursierWhatDependsOn = Keys.coursierWhatDependsOn

    val coursierArtifacts = Keys.coursierArtifacts
    val coursierSignedArtifacts = Keys.coursierSignedArtifacts
    val coursierClassifiersArtifacts = Keys.coursierClassifiersArtifacts
    val coursierSbtClassifiersArtifacts = Keys.coursierSbtClassifiersArtifacts

    val coursierCreateLogger = Keys.coursierCreateLogger

    val coursierVersion = coursier.util.Properties.version
    val addSbtCoursier = {
      addSbtPlugin("io.get-coursier" % "sbt-coursier" % coursierVersion)
    }
  }

  import autoImport._

  lazy val treeSettings = Seq(
    coursierDependencyTree := DisplayTasks.coursierDependencyTreeTask(
      inverse = false
    ).value,
    coursierDependencyInverseTree := DisplayTasks.coursierDependencyTreeTask(
      inverse = true
    ).value,
    coursierWhatDependsOn := Def.inputTaskDyn {
      import sbt.complete.DefaultParsers._
      val input = token(SpaceClass ~ NotQuoted, "<arg>").parsed._2
      DisplayTasks.coursierWhatDependsOnTask(input)
    }.evaluated
  )

  private val pluginIvySnapshotsBase = Resolver.SbtRepositoryRoot.stripSuffix("/") + "/ivy-snapshots"

  // allows to get the actual repo list when sbt starts up
  private val hackHack = Seq(
    // TODO Add docker-based non reg test for that, with sbt-assembly 0.14.5 in ~/.sbt/1.0/plugins/plugins.sbt
    // along with the required extra repo https://repository.jboss.org/nexus/content/repositories/public
    // (required for coursier, because of bad checksums on central)
    appConfiguration.in(updateSbtClassifiers) := {
      val app = appConfiguration.in(updateSbtClassifiers).value

      // hack to trigger https://github.com/sbt/sbt/blob/v1.0.1/main/src/main/scala/sbt/Defaults.scala#L2856,
      // to have the third case be used instead of the second one, at https://github.com/sbt/sbt/blob/v1.0.1/main/src/main/scala/sbt/Defaults.scala#L2069
      new xsbti.AppConfiguration {
        def provider() = {
          import scala.language.reflectiveCalls
          val prov = app.provider()
          val noWarningForDeprecatedStuffProv = prov.asInstanceOf[{
            def mainClass(): Class[_ <: xsbti.AppMain]
          }]
          new xsbti.AppProvider {
            def newMain() = prov.newMain()
            def components() = prov.components()
            def mainClass() = noWarningForDeprecatedStuffProv.mainClass()
            def mainClasspath() = prov.mainClasspath()
            def loader() = prov.loader()
            def scalaProvider() = {
              val scalaProv = prov.scalaProvider()
              val noWarningForDeprecatedStuffScalaProv = scalaProv.asInstanceOf[{
                def libraryJar(): File
                def compilerJar(): File
              }]

              new xsbti.ScalaProvider {
                def app(id: xsbti.ApplicationID) = scalaProv.app(id)
                def loader() = scalaProv.loader()
                def jars() = scalaProv.jars()
                def libraryJar() = noWarningForDeprecatedStuffScalaProv.libraryJar()
                def version() = scalaProv.version()
                def compilerJar() = noWarningForDeprecatedStuffScalaProv.compilerJar()
                def launcher() = {
                  val launch = scalaProv.launcher()
                  new xsbti.Launcher {
                    def app(id: xsbti.ApplicationID, version: String) = launch.app(id, version)
                    def checksums() = launch.checksums()
                    def globalLock() = launch.globalLock()
                    def bootDirectory() = launch.bootDirectory()
                    def appRepositories() = launch.appRepositories()
                    def topLoader() = launch.topLoader()
                    def getScala(version: String) = launch.getScala(version)
                    def getScala(version: String, reason: String) = launch.getScala(version, reason)
                    def getScala(version: String, reason: String, scalaOrg: String) = launch.getScala(version, reason, scalaOrg)
                    def isOverrideRepositories = launch.isOverrideRepositories
                    def ivyRepositories() =
                      throw new NoSuchMethodError("nope")
                    def ivyHome() = launch.ivyHome()
                  }
                }
              }
            }
            def entryPoint() = prov.entryPoint()
            def id() = prov.id()
          }
        }
        def arguments() = app.arguments()
        def baseDirectory() = app.baseDirectory()
      }
    }
  )

  def coursierSettings(
    shadedConfigOpt: Option[(String, Configuration)] = None
  ): Seq[Setting[_]] = hackHack ++ Seq(
    coursierResolvers := RepositoriesTasks.coursierResolversTask.value,
    coursierRecursiveResolvers := RepositoriesTasks.coursierRecursiveResolversTask.value,
    coursierSbtResolvers := {

      // TODO Add docker-based integration test for that, see https://github.com/coursier/coursier/issues/632

      val resolvers =
        sbt.Classpaths.bootRepositories(appConfiguration.value).toSeq.flatten ++ // required because of the hack above it seems
          externalResolvers.in(updateSbtClassifiers).value

      val pluginIvySnapshotsFound = resolvers.exists {
        case repo: URLRepository =>
          repo
            .patterns
            .artifactPatterns
            .headOption
            .exists(_.startsWith(pluginIvySnapshotsBase))
        case _ => false
      }

      val resolvers0 =
        if (pluginIvySnapshotsFound && !resolvers.contains(Classpaths.sbtPluginReleases))
          resolvers :+ Classpaths.sbtPluginReleases
        else
          resolvers

      if (coursierKeepPreloaded.value)
        resolvers0
      else
        resolvers0.filter { r =>
          !r.name.startsWith("local-preloaded")
        }
    },
    coursierFallbackDependencies := InputsTasks.coursierFallbackDependenciesTask.value,
    coursierArtifacts := ArtifactsTasks.artifactsTask(withClassifiers = false).value,
    coursierSignedArtifacts := ArtifactsTasks.artifactsTask(withClassifiers = false, includeSignatures = true).value,
    coursierClassifiersArtifacts := ArtifactsTasks.artifactsTask(
      withClassifiers = true
    ).value,
    coursierSbtClassifiersArtifacts := ArtifactsTasks.artifactsTask(
      withClassifiers = true,
      sbtClassifiers = true
    ).value,
    update := UpdateTasks.updateTask(
      shadedConfigOpt,
      withClassifiers = false
    ).value,
    updateClassifiers := UpdateTasks.updateTask(
      shadedConfigOpt,
      withClassifiers = true,
      ignoreArtifactErrors = true
    ).value,
    updateSbtClassifiers.in(Defaults.TaskGlobal) := UpdateTasks.updateTask(
      shadedConfigOpt,
      withClassifiers = true,
      sbtClassifiers = true,
      ignoreArtifactErrors = true
    ).value,
    coursierConfigGraphs := InputsTasks.ivyGraphsTask.value,
    coursierSbtClassifiersModule := classifiersModule.in(updateSbtClassifiers).value,
    coursierConfigurations := InputsTasks.coursierConfigurationsTask(None).value,
    coursierParentProjectCache := InputsTasks.parentProjectCacheTask.value,
    coursierResolutions := ResolutionTasks.resolutionsTask().value,
    Keys.actualCoursierResolution := {

      val config = Configuration(Compile.name)

      coursierResolutions
        .value
        .collectFirst {
          case (configs, res) if configs(config) =>
            res
        }
        .getOrElse {
          sys.error(s"Resolution for configuration $config not found")
        }
    },
    coursierSbtClassifiersResolution := ResolutionTasks.resolutionsTask(
      sbtClassifiers = true
    ).value.head._2,
    ivyConfigurations := {
      val confs = ivyConfigurations.value
      val names = confs.map(_.name).toSet

      // Yes, adding those back in sbt 1.0. Can't distinguish between config test (whose jars with classifier tests ought to
      // be added), and sources / docs else (if their JARs are in compile, they would get added too then).

      val extraSources =
        if (names("sources"))
          None
        else
          Some(
            sbt.Configuration.of(
              id = "Sources",
              name = "sources",
              description = "",
              isPublic = true,
              extendsConfigs = Vector.empty,
              transitive = false
            )
          )

      val extraDocs =
        if (names("docs"))
          None
        else
          Some(
            sbt.Configuration.of(
              id = "Docs",
              name = "docs",
              description = "",
              isPublic = true,
              extendsConfigs = Vector.empty,
              transitive = false
            )
          )

      confs ++ extraSources.toSeq ++ extraDocs.toSeq
    },
    // Tests artifacts from Maven repositories are given this type.
    // Adding it here so that these work straightaway.
    classpathTypes += "test-jar"
  )

  override lazy val buildSettings = super.buildSettings ++ Seq(
    coursierParallelDownloads := 6,
    coursierMaxIterations := ResolutionProcess.defaultMaxIterations,
    coursierChecksums := Seq(Some("SHA-1"), None),
    coursierArtifactsChecksums := Seq(None),
    coursierCachePolicies := CachePolicy.default,
    coursierTtl := Cache.defaultTtl,
    coursierVerbosity := Settings.defaultVerbosityLevel(sLog.value),
    mavenProfiles := Set.empty,
    coursierUseSbtCredentials := true,
    coursierCredentials := Map.empty,
    coursierCache := Cache.default,
    coursierReorderResolvers := true,
    coursierKeepPreloaded := false,
    coursierCreateLogger := { () => new TermDisplay(new OutputStreamWriter(System.err)) }
  )

  override lazy val projectSettings = coursierSettings() ++
    inConfig(Compile)(treeSettings) ++
    inConfig(Test)(treeSettings)

}
