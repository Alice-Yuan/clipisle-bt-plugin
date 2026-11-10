pluginManagement { repositories { maven { url = uri("https://maven.aliyun.com/repository/google") }; maven { url = uri("https://maven.aliyun.com/repository/public") }; google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { maven { url = uri("https://maven.aliyun.com/repository/google") }; maven { url = uri("https://maven.aliyun.com/repository/public") }; google(); mavenCentral(); maven { url = uri("https://dl.frostwire.com/maven"); content { includeGroup("com.frostwire"); includeGroup("com.frostwire.jlibtorrent") } } } }
plugins { id("com.android.application") version "9.0.1" apply false; id("org.jetbrains.kotlin.android") version "2.3.20" apply false }
rootProject.name = "ClipIsleBtPlugin"
include(":app")

