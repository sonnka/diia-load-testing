package ua.gov.diia.loadtest.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/** The {@code loadTest { }} block. Set {@code definition}; the rest is optional. */
public abstract class LoadTestExtension {

  /**
   * Fully qualified name of the load test definition: a class extending {@code LoadTestDefinition}
   * (or {@code GrpcLoadTestDefinition}) - a Kotlin {@code object} or a class with a no-argument
   * constructor. The library's own runner then runs it: no {@code main} needed.
   *
   * @return the definition class property
   */
  public abstract Property<String> getDefinition();

  /**
   * Instead of {@link #getDefinition()}: a class with its own {@code main} that drives the library
   * directly, e.g. {@code com.example.loadtest.LoadTestKt} for a Kotlin top-level {@code main}.
   *
   * @return the main class property
   */
  public abstract Property<String> getMainClass();

  /**
   * Main class of the service itself, for load tests that start it locally
   * ({@code context.getEnvironment().service(...)}). Not needed with Spring Boot: its resolved main
   * class is used.
   *
   * @return the service main class property
   */
  public abstract Property<String> getServiceMainClass();

  /**
   * What the load test talks to the service over; each one adds its library to the {@code loadTest} source
   * set: {@code grpc} ({@code loadtest-grpc}, {@code GrpcLoadTestDefinition}) and {@code http}
   * ({@code loadtest-http}, {@code HttpLoadTestDefinition}). Default: {@code ['grpc']}.
   *
   * @return the transports property
   */
  public abstract ListProperty<String> getTransports();

}
