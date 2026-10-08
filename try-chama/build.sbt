name := """try-chama"""
organization := "com.trychama"

version := "1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .enablePlugins(PlayJava, PlayEbean)

scalaVersion := "2.13.17"

libraryDependencies ++= Seq(
  guice,
  jdbc,
  javaJpa,
  evolutions,
  "javax.persistence" % "javax.persistence-api" % "2.2",
  "com.auth0" % "java-jwt" % "4.4.0",
  "org.playframework" %% "play-mailer" % "10.1.0",
  "org.playframework" %% "play-mailer-guice" % "10.1.0",
  "mysql" % "mysql-connector-java" % "8.0.33",
  "org.mindrot" % "jbcrypt" % "0.4",
  "com.h2database" % "h2" % "2.4.240",

  // Ebean runtime and agent
  "io.ebean" % "ebean" % "12.11.1",
  "io.ebean" % "ebean-agent" % "12.11.1"
)

// Add these lines somewhere near your other settings/dependencies

// Force a consistent Jackson 2.14.x line compatible with jackson-module-scala 2.14.3
dependencyOverrides ++= Seq(
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.14.3",
  "com.fasterxml.jackson.core" % "jackson-core"     % "2.14.3",
  "com.fasterxml.jackson.core" % "jackson-annotations" % "2.14.3"
)

// Make Scala module explicit so we control its version
libraryDependencies += "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.14.3"