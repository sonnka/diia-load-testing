package ua.gov.diia.loadtest.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds a real consumer project with the plugin. The library dependency is served from a local
 * repository holding a POM-only stand-in, so no network is needed.
 */
class LoadTestPluginFunctionalTest {

  @TempDir
  Path project;

  @Test
  void runsTheLoadTestWithMainClassesTheLibraryAndForwardedProperties() throws IOException {
    consumer("loadTest { mainClass = 'demo.loadtest.Main' }");
    write("src/main/java/demo/App.java", """
            package demo;
            public class App { public static String name() { return "status-list"; } }
            """);
    write("src/loadTest/java/demo/loadtest/Main.java", """
            package demo.loadtest;
            public class Main {
              public static void main(String[] args) {
                System.out.println("app=" + demo.App.name() + " targetRate=" + System.getProperty("targetRate"));
              }
            }
            """);

    final BuildResult result = gradle("runLoadTest", "printLoadTestDependencies", "-DtargetRate=42").build();

    assertTrue(result.getOutput().contains("app=status-list targetRate=42"), result.getOutput());
    assertTrue(result.getOutput().contains("ua.gov.diia:loadtest-grpc:" + LoadTestPlugin.pluginVersion()), result.getOutput());
  }

  @Test
  void runsADefinitionWithTheLibraryRunnerAndHandsOverTheServiceClasspath() throws IOException {
    consumer("loadTest { definition = 'demo.loadtest.DemoLoadTest'; serviceMainClass = 'demo.App' }");
    write("src/main/java/demo/App.java", "package demo; public class App { }");
    // Stands in for the library's runner (the library itself is a POM-only stub here).
    write("src/loadTest/java/ua/gov/diia/loadtest/LoadTestMain.java", """
            package ua.gov.diia.loadtest;
            public class LoadTestMain {
              public static void main(String[] args) throws Exception {
                String classpathFile = System.getProperty("loadtest.service.classpathFile"); // runLoadTest only
                String classpath = classpathFile == null ? "" : java.nio.file.Files.readString(java.nio.file.Path.of(classpathFile));
                System.out.println("args=" + String.join(",", args)
                        + " service=" + System.getProperty("loadtest.service.mainClass")
                        + " serviceClasses=" + classpath.contains("classes" + java.io.File.separator + "java" + java.io.File.separator + "main")
                        + " preset=" + System.getProperty("preset"));
              }
            }
            """);

    final BuildResult run = gradle("runLoadTest", "-Dpreset=smoke").build();
    final BuildResult help = gradle("loadTestHelp").build();

    assertTrue(run.getOutput().contains("args=demo.loadtest.DemoLoadTest service=demo.App serviceClasses=true preset=smoke"),
            run.getOutput());
    assertTrue(help.getOutput().contains("args=demo.loadtest.DemoLoadTest,--help"), help.getOutput());
  }

  @Test
  void addsTheLibraryOfEveryTransport() throws IOException {
    consumer("loadTest { mainClass = 'demo.loadtest.Main'; transports = ['http'] }");
    final String version = LoadTestPlugin.pluginVersion();

    final BuildResult http = gradle("printLoadTestDependencies").build();
    write("build.gradle", read("build.gradle")
            .replace("transports = ['http']", "transports = ['grpc', 'http']"));

    final BuildResult both = gradle("printLoadTestDependencies").build();
    write("build.gradle", read("build.gradle")
            .replace("transports = ['grpc', 'http']", "transports = ['soap']"));

    final BuildResult unknown = gradle("printLoadTestDependencies").buildAndFail();

    assertTrue(http.getOutput().contains("deps=[ua.gov.diia:loadtest-http:" + version + "]"), http.getOutput());
    assertTrue(both.getOutput().contains("deps=[ua.gov.diia:loadtest-grpc:" + version + ", ua.gov.diia:loadtest-http:" + version + "]"),
            both.getOutput());
    assertTrue(unknown.getOutput().contains("Unknown load test transport 'soap'"), unknown.getOutput());
  }

  @Test
  void explainsAMissingDefinition() throws IOException {
    consumer("");

    final BuildResult run = gradle("runLoadTest").buildAndFail();
    final BuildResult help = gradle("loadTestHelp").buildAndFail();

    assertTrue(run.getOutput().contains("loadTest { definition ="), run.getOutput());
    assertTrue(help.getOutput().contains("loadTestHelp needs a load test definition"), help.getOutput());
  }

  private void consumer(final String loadTestBlock) throws IOException {
    final String version = LoadTestPlugin.pluginVersion();
    for (final String library : List.of("loadtest-grpc", "loadtest-http")) {
      write("repo/ua/gov/diia/" + library + "/" + version + "/" + library + "-" + version + ".pom", """
              <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>ua.gov.diia</groupId>
                <artifactId>%s</artifactId>
                <version>%s</version>
                <packaging>pom</packaging>
              </project>
              """.formatted(library, version));
    }
    write("settings.gradle", "rootProject.name = 'consumer'");
    write("build.gradle", """
            plugins {
                id 'java'
                id 'ua.gov.diia.loadtest'
            }
            repositories { maven { url = uri('repo') } }
            %s

            tasks.register('printLoadTestDependencies') {
                def deps = configurations.loadTestImplementation.allDependencies
                        .findAll { it.group != null }.collect { "${it.group}:${it.name}:${it.version}" }
                doLast { println "deps=" + deps }
            }
            """.formatted(loadTestBlock));
  }

  private GradleRunner gradle(final String... arguments) {
    return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath().withArguments(arguments);
  }

  private String read(final String path) throws IOException {
    return Files.readString(project.resolve(path));
  }

  private void write(final String path, final String content) throws IOException {
    final Path file = project.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

}
