package ua.gov.diia.loadtest.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * {@code ua.gov.diia.loadtest}: everything a service needs to run a diia-load-testing load test, so its
 * build only names the load test definition.
 *
 * <ul>
 *   <li>a {@code loadTest} source set ({@code src/loadTest/java} or {@code src/loadTest/kotlin}) that
 *       sees main's classes - the generated gRPC stubs - and main's dependencies;</li>
 *   <li>the library of every {@code loadTest.transports} entry ({@code ua.gov.diia:loadtest-grpc} by default,
 *       {@code ua.gov.diia:loadtest-http} for {@code http}) at this plugin's own version on that source set;</li>
 *   <li>{@code runLoadTest}: runs {@code loadTest.definition} (or {@code loadTest.mainClass}) and forwards
 *       every {@code -D} given on the Gradle command line to it;</li>
 *   <li>{@code loadTestHelp}: prints the definition's scenarios, presets and parameters;</li>
 *   <li>the service's own runtime classpath and main class, for load tests that start the service
 *       locally.</li>
 * </ul>
 *
 * <p>Language-neutral: a Java service writes the test in Java; in a Kotlin service the Kotlin plugin
 * compiles {@code src/loadTest/kotlin} as it does for any source set. The plugin never applies Kotlin.
 */
public final class LoadTestPlugin implements Plugin<Project> {

  public static final String EXTENSION_NAME = "loadTest";
  public static final String SOURCE_SET_NAME = "loadTest";
  public static final String TASK_NAME = "runLoadTest";
  public static final String HELP_TASK_NAME = "loadTestHelp";
  public static final Map<String, String> LIBRARIES = Map.of(
          "grpc", "ua.gov.diia:loadtest-grpc",
          "http", "ua.gov.diia:loadtest-http"
  );
  public static final String DEFAULT_TRANSPORT = "grpc";
  /** The library's runner for definitions. */
  public static final String RUNNER = "ua.gov.diia.loadtest.LoadTestMain";

  private static final String VERSION_RESOURCE = "/loadtest-plugin.properties";
  private static final String SPRING_BOOT = "org.springframework.boot";
  private static final String SPRING_BOOT_MAIN_CLASS_TASK = "resolveMainClassName";

  @Override
  public void apply(final Project project) {
    final LoadTestExtension extension = project.getExtensions().create(EXTENSION_NAME, LoadTestExtension.class);
    extension.getTransports().convention(List.of(DEFAULT_TRANSPORT));
    project.getPlugins().withType(JavaPlugin.class, javaPlugin -> configure(project, extension));
  }

  private static void configure(final Project project, final LoadTestExtension extension) {
    final SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
    final SourceSet main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
    final SourceSet loadTest = sourceSets.create(SOURCE_SET_NAME);

    final ConfigurationContainer configurations = project.getConfigurations();
    configurations.getByName(loadTest.getImplementationConfigurationName())
            .extendsFrom(configurations.getByName(main.getImplementationConfigurationName()));
    configurations.getByName(loadTest.getRuntimeOnlyConfigurationName())
            .extendsFrom(configurations.getByName(main.getRuntimeOnlyConfigurationName()));

    project.getDependencies().add(loadTest.getImplementationConfigurationName(), main.getOutput());
    configurations.getByName(loadTest.getImplementationConfigurationName()).getDependencies().addAllLater(
            extension.getTransports().map(transports -> transports.stream()
                    .distinct()
                    .map(transport -> project.getDependencies().create(library(transport) + ":" + pluginVersion()))
                    .toList()));

    final Provider<RegularFile> serviceClasspathFile =
            project.getLayout().getBuildDirectory().file("loadtest/service-classpath.txt");
    final FileCollection serviceClasspath = main.getRuntimeClasspath();

    final TaskProvider<JavaExec> run = project.getTasks().register(TASK_NAME, JavaExec.class, task -> {
      task.setDescription("Runs the load test (src/loadTest); ./gradlew " + HELP_TASK_NAME + " lists its settings.");
      configureLoadTestExec(project, task, loadTest);
      task.getMainClass().set(extension.getDefinition().map(definition -> RUNNER).orElse(extension.getMainClass()));
      task.getArgumentProviders().add(() -> optional(extension.getDefinition().getOrNull()));

      // What context.getEnvironment().service(...) starts by default: this project's own service.
      task.getInputs().files(serviceClasspath).withPropertyName("serviceClasspath");
      task.getJvmArgumentProviders().add(() -> List.of(
              "-Dloadtest.service.classpathFile=" + serviceClasspathFile.get().getAsFile().getAbsolutePath()
      ));
      task.getJvmArgumentProviders().add(() -> optional(
              extension.getServiceMainClass().map(name -> "-Dloadtest.service.mainClass=" + name).getOrNull()
      ));
      task.doFirst(t -> {
        if (extension.getDefinition().isPresent() == extension.getMainClass().isPresent()) {
          throw new GradleException("Set the load test definition: loadTest { definition = 'com.example.loadtest.MyLoadTest' }"
                  + " (or, for a load test with its own main: loadTest { mainClass = '...' }, but not both)");
        }
        writeServiceClasspath(serviceClasspathFile.get().getAsFile(), serviceClasspath);
      });
    });

    // Spring Boot already knows the service's main class: use it unless serviceMainClass is set.
    project.getPlugins().withId(SPRING_BOOT, plugin -> run.configure(task -> {
      final TaskProvider<Task> resolveMainClass = project.getTasks().named(SPRING_BOOT_MAIN_CLASS_TASK);
      task.dependsOn(resolveMainClass);
      task.getJvmArgumentProviders().add(() -> extension.getServiceMainClass().isPresent()
              ? List.of()
              : List.of("-Dloadtest.service.mainClassFile="
                      + resolveMainClass.get().getOutputs().getFiles().getSingleFile().getAbsolutePath()));
    }));

    project.getTasks().register(HELP_TASK_NAME, JavaExec.class, task -> {
      task.setDescription("Lists the load test's scenarios, presets and parameters with their current values.");
      configureLoadTestExec(project, task, loadTest);
      task.getMainClass().set(RUNNER);
      task.getArgumentProviders().add(() -> {
        final List<String> args = new ArrayList<>(optional(extension.getDefinition().getOrNull()));
        args.add("--help");
        return args;
      });
      task.doFirst(t -> {
        if (!extension.getDefinition().isPresent()) {
          throw new GradleException(HELP_TASK_NAME + " needs a load test definition: loadTest { definition = '...' }");
        }
      });
    });
  }

  private static void configureLoadTestExec(
          final Project project,
          final JavaExec task,
          final SourceSet loadTest
  ) {
    task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
    task.setClasspath(loadTest.getRuntimeClasspath());
    task.systemProperties(project.getGradle().getStartParameter().getSystemPropertiesArgs());
  }

  private static String library(final String transport) {
    final String library = LIBRARIES.get(transport);
    if (library == null) {
      throw new GradleException("Unknown load test transport '" + transport + "' in loadTest { transports = ... }; available: "
              + String.join(", ", LIBRARIES.keySet().stream().sorted().toList()));
    }
    return library;
  }

  private static List<String> optional(final String value) {
    return value == null ? List.of() : List.of(value);
  }

  private static void writeServiceClasspath(final File file, final FileCollection classpath) {
    try {
      Files.createDirectories(file.getParentFile().toPath());
      Files.writeString(file.toPath(), classpath.getAsPath());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String pluginVersion() {
    try (InputStream in = LoadTestPlugin.class.getResourceAsStream(VERSION_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(VERSION_RESOURCE + " is missing from the loadtest plugin jar");
      }
      final Properties properties = new Properties();
      properties.load(in);
      return properties.getProperty("version");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

}
