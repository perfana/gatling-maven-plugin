/**
 * Copyright 2011-2017 GatlingCorp (http://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.mojo;

import io.perfana.client.PerfanaClient;
import io.perfana.client.PerfanaClientBuilder;
import io.perfana.client.api.*;
import io.perfana.client.exception.PerfanaAssertionsAreFalse;
import io.perfana.client.exception.PerfanaClientException;
import org.apache.commons.exec.ExecuteException;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.toolchain.Toolchain;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.SelectorUtils;
import org.codehaus.plexus.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import static io.gatling.mojo.MojoConstants.*;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Mojo to execute Gatling.
 */
@Mojo(name = "test",
  defaultPhase = LifecyclePhase.INTEGRATION_TEST,
  requiresDependencyResolution = ResolutionScope.TEST)
public class GatlingMojo extends AbstractGatlingMojo {

  /**
   * A name of a Simulation class to run.
   */
  @Parameter(property = "gatling.simulationClass")
  private String simulationClass;

  /**
   * Iterate over multiple simulations if more than one simulation file is found. By default false.
   * If multiple simulations are found but {@literal runMultipleSimulations} is false the execution will fail.
   */
  @Parameter(property = "gatling.runMultipleSimulations", defaultValue = "false")
  private boolean runMultipleSimulations;

  /**
   * List of include patterns to use for scanning. Includes all simulations by default.
   */
  @Parameter(property = "gatling.includes")
  private String[] includes;

  /**
   * List of exclude patterns to use for scanning. Excludes none by default.
   */
  @Parameter(property = "gatling.excludes")
  private String[] excludes;

  /**
   * Run simulation but does not generate reports. By default false.
   */
  @Parameter(property = "gatling.noReports", defaultValue = "false")
  private boolean noReports;

  /**
   * Generate the reports for the simulation in this folder.
   */
  @Parameter(property = "gatling.reportsOnly")
  private String reportsOnly;

  /**
   * A short description of the run to include in the report.
   */
  @Parameter(property = "gatling.runDescription")
  private String runDescription;

  /**
   * Disable the plugin.
   */
  @Parameter(property = "gatling.skip", defaultValue = "false")
  private boolean skip;

  /**
   * Will cause the project build to look successful, rather than fail, even
   * if there are Gatling test failures. This can be useful on a continuous
   * integration server, if your only option to be able to collect output
   * files, is if the project builds successfully.
   */
  @Parameter(property = "gatling.failOnError", defaultValue = "true")
  private boolean failOnError;

  /**
   * Continue execution of simulations despite assertion failure. If you have
   * some stack of simulations and you want to get results from all simulations
   * despite some assertion failures in previous one.
   */
  @Parameter(property = "gatling.continueOnAssertionFailure", defaultValue = "false")
  private boolean continueOnAssertionFailure;

  @Parameter(property = "gatling.useOldJenkinsJUnitSupport", defaultValue = "false")
  private boolean useOldJenkinsJUnitSupport;

  /**
   * Extra JVM arguments to pass when running Gatling.
   */
  @Parameter(property = "gatling.jvmArgs")
  private List<String> jvmArgs;

  /**
   * Override Gatling's default JVM args, instead of replacing them.
   */
  @Parameter(property = "gatling.overrideJvmArgs", defaultValue = "false")
  private boolean overrideJvmArgs;

  /**
   * Propagate System properties to forked processes.
   */
  @Parameter(property = "gatling.propagateSystemProperties", defaultValue = "true")
  private boolean propagateSystemProperties;

  /**
   * Extra JVM arguments to pass when running Zinc.
   */
  @Parameter(property = "gatling.compilerJvmArgs")
  private List<String> compilerJvmArgs;

  /**
   * Override Zinc's default JVM args, instead of replacing them.
   */
  @Parameter(property = "gatling.overrideCompilerJvmArgs", defaultValue = "false")
  private boolean overrideCompilerJvmArgs;

  /**
   * Extra options to be passed to scalac when compiling the Scala code
   */
  @Parameter(property = "gatling.extraScalacOptions")
  private List<String> extraScalacOptions;

  /**
   * Disable the Scala compiler, if scala-maven-plugin is already in charge
   * of compiling the simulations.
   */
  @Parameter(property = "gatling.disableCompiler", defaultValue = "false")
  private boolean disableCompiler;

  /**
   * Use this folder to discover simulations that could be run.
   */
  @Parameter(property = "gatling.simulationsFolder", defaultValue = "${project.basedir}/src/test/scala")
  private File simulationsFolder;

  /**
   * Use this folder as the folder where feeders are stored.
   */
  @Parameter(property = "gatling.resourcesFolder", defaultValue = "${project.basedir}/src/test/resources")
  private File resourcesFolder;

  /**
   * Use this folder as the folder where results are stored.
   */
  @Parameter(property = "gatling.resultsFolder", defaultValue = "${project.build.directory}/gatling")
  private File resultsFolder;

  @Parameter(defaultValue = "${plugin.artifacts}", readonly = true)
  private List<Artifact> artifacts;

  /**
   * Perfana: Name of application that is being tested.
   */
  @Parameter(property = "gatling.application", alias = "ap", defaultValue = "UNKNOWN_APPLICATION")
  private String application;

  /**
   * Perfana: Test type for this test.
   */
  @Parameter(property = "gatling.testType", alias = "tt", defaultValue = "UNKNOWN_TEST_TYPE")
  private String testType;

  /**
   * Perfana: Test environment for this test.
   */
  @Parameter(property = "gatling.testEnvironment", alias = "te", defaultValue = "UNKNOWN_TEST_ENVIRONMENT")
  private String testEnvironment;

  /**
   * Perfana: Test run id.
   */
  @Parameter(property = "gatling.testRunId", alias = "tid")
  private String testRunId;

  /**
   * Perfana: Build results url where to find the results of this load test.
   */
  @Parameter(property = "gatling.CIBuildResultsUrl", alias = "url")
  private String CIBuildResultsUrl;

  /**
   * Perfana: Perfana url.
   */
  @Parameter(property = "gatling.perfanaUrl", alias = "iourl", defaultValue = "UNKNOWN_PERFANA_URL")
  private String perfanaUrl;

  /**
   * Perfana: the release number of the application.
   */
  @Parameter(property = "gatling.applicationRelease", alias = "pr")
  private String applicationRelease;

  /**
   * Perfana: Rampup time in seconds.
   */
  @Parameter(property = "gatling.rampupTimeInSeconds", alias = "rt")
  private String rampupTimeInSeconds;

  /**
   * Perfana: Constan load time in seconds.
   */
  @Parameter(property = "gatling.constantLoadTimeInSeconds", alias = "pd")
  private String constantLoadTimeInSeconds;

  /**
   * Perfana: Parse the Perfana test asserts and fail build it not ok.
   */
  @Parameter(property = "gatling.assertResultsEnabled", alias = "ar", defaultValue = "false")
  private boolean assertResultsEnabled;

  /**
   * Perfana: Enable calls to Perfana.
   */
  @Parameter(property = "gatling.perfanaEnabled", alias = "tie", defaultValue = "false")
  private boolean perfanaEnabled;

  /**
   * Perfana: test run annotations passed via environment variable
   */
  @Parameter(property = "gatling.annotations", alias = "ann")
  private String annotations;

  /**
   * Perfana: test run variables passed via environment variable
   */
  @Parameter(property = "gatling.variables")
  private Properties variables;

  /**
   * Perfana: test run comma separated tags via environment variable
   */
  @Parameter(property = "gatling.tags")
  private String tags;

  /**
   * Perfana: properties for perfana event implementations
   */
  @Parameter(property = "gatling.perfanaEventProperties")
  private Map<String, Properties> perfanaEventProperties;

  /**
   * Perfana: schedule script with events, one event per line, such as: PT1M|scale-down|replicas=2
   */
  @Parameter(property = "gatling.eventScheduleScript")
  private String eventScheduleScript;

  /**
   * Executes Gatling simulations.
   */
  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (skip) {
      getLog().info("Skipping gatling-maven-plugin");
      return;
    }

    boolean abortPerfana = false;
    final PerfanaClient perfanaClient = perfanaEnabled
            ? createPerfanaClient()
            : null;

    if (perfanaEnabled && perfanaClient != null) {
        perfanaClient.startSession();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (perfanaEnabled && !perfanaClient.isSessionStopped()) {
                getLog().info("Shutdown Hook: abort perfana session!");
                perfanaClient.abortSession();
            }
        }));
    }

    // Create results directories
    resultsFolder.mkdirs();
    try {
      List<String> testClasspath = buildTestClasspath();

      Toolchain toolchain = toolchainManager.getToolchainFromBuildContext("jdk", session);
      if (!disableCompiler) {
        executeCompiler(compilerJvmArgs(), testClasspath, toolchain);
      }

      List<String> jvmArgs = gatlingJvmArgs();

      if (reportsOnly != null) {
        executeGatling(jvmArgs, gatlingArgs(null), testClasspath, toolchain);

      } else {
        List<String> simulations = simulations();
        iterateBySimulations(toolchain, jvmArgs, testClasspath, simulations);
      }

    } catch (Exception e) {
      if (failOnError) {
        abortPerfana = true;
        if (e instanceof GatlingSimulationAssertionsFailedException) {
          throw new MojoFailureException(e.getMessage(), e);
        } else if (e instanceof MojoFailureException) {
          throw (MojoFailureException) e;
        } else if (e instanceof MojoExecutionException) {
          throw (MojoExecutionException) e;
        } else {
          throw new MojoExecutionException("Gatling failed.", e);
        }
      } else {
        getLog().warn("There were some errors while running your simulation, but failOnError was set to false won't fail your build.");
      }
    } finally {
        copyJUnitReports();
        if (perfanaEnabled && perfanaClient != null && abortPerfana) {
            perfanaClient.abortSession();
        }
    }
    if (perfanaEnabled && perfanaClient != null) {
        try {
            perfanaClient.stopSession();
        } catch (PerfanaClientException e) {
            throw new MojoExecutionException("Perfana assertions check failed. " + e.getMessage(), e);
        } catch (PerfanaAssertionsAreFalse perfanaAssertionsAreFalse) {
            throw new MojoExecutionException("Perfana assertions check is false. " + perfanaAssertionsAreFalse.getMessage(), perfanaAssertionsAreFalse);
        }
    }
  }

  private PerfanaClient createPerfanaClient() {

      PerfanaClientLogger logger = new PerfanaClientLogger() {
          @Override
          public void info(String message) {
              getLog().info(message);
          }

          @Override
          public void warn(String message) {
              getLog().warn(message);
          }

          @Override
          public void error(String message) {
              getLog().error(message);
          }

          @Override
          public void error(String message, Throwable throwable) {
            getLog().error(message, throwable);
          }

          @Override
          public void debug(final String message) {
              getLog().debug(message);
          }
      };

      TestContext context = new TestContextBuilder()
              .setTestRunId(testRunId)
              .setApplication(application)
              .setTestType(testType)
              .setTestEnvironment(testEnvironment)
              .setCIBuildResultsUrl(CIBuildResultsUrl)
              .setApplicationRelease(applicationRelease)
              .setRampupTimeInSeconds(rampupTimeInSeconds)
              .setConstantLoadTimeInSeconds(constantLoadTimeInSeconds)
              .setAnnotations(annotations)
              .setVariables(variables)
              .setTags(tags)
              .build();

      PerfanaConnectionSettings settings = new PerfanaConnectionSettingsBuilder()
              .setPerfanaUrl(perfanaUrl)
              .build();

      PerfanaClientBuilder builder = new PerfanaClientBuilder()
              .setLogger(logger)
              .setTestContext(context)
              .setPerfanaConnectionSettings(settings)
              .setAssertResultsEnabled(assertResultsEnabled)
              .setCustomEvents(eventScheduleScript);
      
      if (perfanaEventProperties != null) {
          perfanaEventProperties.forEach(
                  (className, props) -> props.forEach(
                          (name, value) -> builder.addEventProperty(className, (String) name, (String) value)));
      }
      
      return builder.build();
  }

  private void iterateBySimulations(Toolchain toolchain, List<String> jvmArgs, List<String> testClasspath, List<String> simulations) throws Exception {
    Exception exc = null;
    int simulationsCount = simulations.size();
    for (int i = 0; i < simulationsCount; i++) {
      try {
        executeGatling(jvmArgs, gatlingArgs(simulations.get(i)), testClasspath, toolchain);
      } catch (GatlingSimulationAssertionsFailedException e) {
        if (exc == null && i == simulationsCount - 1) {
          throw e;
        }

        if (continueOnAssertionFailure) {
          if (exc != null) {
            continue;
          }
          exc = e;
          continue;
        }
        throw e;
      }
    }

    if (exc != null) {
      getLog().warn("There were some errors while running your simulation, but continueOnAssertionFailure was set to true, so your simulations continue to perform.");
      throw exc;
    }
  }

  private void executeCompiler(List<String> zincJvmArgs, List<String> testClasspath, Toolchain toolchain) throws Exception {
    List<String> compilerClasspath = buildCompilerClasspath();
    compilerClasspath.addAll(testClasspath);
    List<String> compilerArguments = compilerArgs();

    Fork forkedCompiler = new Fork(COMPILER_MAIN_CLASS, compilerClasspath, zincJvmArgs, compilerArguments, toolchain, false, getLog());
    try {
      forkedCompiler.run();
    } catch (ExecuteException e) {
      throw new CompilationException(e);
    }
  }

  private void executeGatling(List<String> gatlingJvmArgs, List<String> gatlingArgs, List<String> testClasspath, Toolchain toolchain) throws Exception {
    Fork forkedGatling = new Fork(GATLING_MAIN_CLASS, testClasspath, gatlingJvmArgs, gatlingArgs, toolchain, propagateSystemProperties, getLog());
    try {
      forkedGatling.run();
    } catch (ExecuteException e) {
      if (e.getExitValue() == 2)
        throw new GatlingSimulationAssertionsFailedException(e);
      else
        throw e; /* issue 1482*/
    }
  }

  private void copyJUnitReports() throws MojoExecutionException {

    try {
      if (useOldJenkinsJUnitSupport) {
        File[] runDirectories = resultsFolder.listFiles(File::isDirectory);

        for (File runDirectory: runDirectories) {
          File jsDir = new File(runDirectory, "js");
          if (jsDir.exists() && jsDir.isDirectory()) {
            File assertionFile = new File(jsDir, "assertions.xml");
            if (assertionFile.exists()) {
              File newAssertionFile = new File(resultsFolder, "assertions-" + runDirectory.getName() + ".xml");
              Files.copy(assertionFile.toPath(), newAssertionFile.toPath(), COPY_ATTRIBUTES, REPLACE_EXISTING);
              getLog().info("Copying assertion file " + assertionFile.getCanonicalPath() + " to " + newAssertionFile.getCanonicalPath());
            }
          }
        }
      }
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to copy JUnit reports", e);
    }
  }

  private List<String> buildCompilerClasspath() throws Exception {

    List<String> compilerClasspathElements = new ArrayList<>();
    for (Artifact artifact: artifacts) {
      String groupId = artifact.getGroupId();
      if (!groupId.startsWith("org.codehaus.plexus")
        && !groupId.startsWith("org.apache.maven")
        && !groupId.startsWith("org.sonatype")) {
        compilerClasspathElements.add(artifact.getFile().getCanonicalPath());
      }
    }

    String gatlingVersion = getVersion("io.gatling", "gatling-core");
    Set<Artifact> gatlingCompilerAndDeps = resolve("io.gatling", "gatling-compiler", gatlingVersion, true).getArtifacts();
    for (Artifact artifact : gatlingCompilerAndDeps) {
      compilerClasspathElements.add(artifact.getFile().getCanonicalPath());
    }

    // Add plugin jar to classpath (used by MainWithArgsInFile)
    compilerClasspathElements.add(MojoUtils.locateJar(GatlingMojo.class));
    return compilerClasspathElements;
  }

  private List<String> gatlingJvmArgs() {
    return computeArgs(jvmArgs, GATLING_JVM_ARGS, overrideJvmArgs);
  }

  private List<String> compilerJvmArgs() {
    return computeArgs(compilerJvmArgs, COMPILER_JVM_ARGS, overrideCompilerJvmArgs);
  }

  private List<String> computeArgs(List<String> custom, List<String> defaults, boolean override) {
    if (custom.isEmpty()) {
      return defaults;
    }
    if (override) {
      List<String> merged = new ArrayList<>(custom);
      merged.addAll(defaults);
      return merged;
    }
    return custom;
  }

  private List<String> simulations() throws MojoFailureException {
    // Solves the simulations, if no simulation file is defined
    if (simulationClass != null) {
      return Collections.singletonList(simulationClass);

    } else {
      List<String> simulations = resolveSimulations();

      if (simulations.isEmpty()) {
        getLog().error("No simulations to run");
        throw new MojoFailureException("No simulations to run");
      }

      if (simulations.size() > 1 && !runMultipleSimulations) {
        String message = "More than 1 simulation to run, need to specify one, or enable runMultipleSimulations";
        getLog().error(message);
        throw new MojoFailureException(message);
      }

      return simulations;
    }
  }

  private List<String> gatlingArgs(String simulationClass) throws Exception {
    // Arguments
    List<String> args = new ArrayList<>();
    addArg(args, "rsf", resourcesFolder.getCanonicalPath());
    addArg(args, "rf", resultsFolder.getCanonicalPath());
    addArg(args, "sf", simulationsFolder.getCanonicalPath());

    addArg(args, "rd", runDescription);

    if (noReports) {
      args.add("-nr");
    }

    addArg(args, "s", simulationClass);
    addArg(args, "ro", reportsOnly);

    return args;
  }

  private List<String> compilerArgs() throws Exception {
    List<String> args = new ArrayList<>();
    addArg(args, "sf", simulationsFolder.getCanonicalPath());
    addArg(args, "bf", compiledClassesFolder.getCanonicalPath());

    if (!extraScalacOptions.isEmpty()) {
      addArg(args, "eso", StringUtils.join(extraScalacOptions.iterator(), ","));
    }

    return args;
  }

  /**
   * Resolve simulation files to execute from the simulation folder.
   *
   * @return a comma separated String of simulation class names.
   */
  private List<String> resolveSimulations() {

    try {
      ClassLoader testClassLoader = new URLClassLoader(testClassPathUrls());

      Class<?> simulationClass = testClassLoader.loadClass("io.gatling.core.scenario.Simulation");
      List<String> includes = MojoUtils.arrayAsListEmptyIfNull(this.includes);
      List<String> excludes = MojoUtils.arrayAsListEmptyIfNull(this.excludes);

      List<String> simulationsClasses = new ArrayList<>();

      for (String classFile: compiledClassFiles()) {
        String className = pathToClassName(classFile);

        boolean isIncluded = includes.isEmpty() || match(includes, className);
        boolean isExcluded =  match(excludes, className);

        if (isIncluded && !isExcluded) {
          // check if the class is a concrete Simulation
          Class<?> clazz = testClassLoader.loadClass(className);
          if (simulationClass.isAssignableFrom(clazz) && isConcreteClass(clazz)) {
            simulationsClasses.add(className);
          }
        }
      }

      return simulationsClasses;

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static boolean match(List<String> patterns, String string) {
    for (String pattern : patterns) {
      if (SelectorUtils.match(pattern, string)) {
        return true;
      }
    }
    return false;
  }

  private URL[] testClassPathUrls() throws DependencyResolutionRequiredException, MalformedURLException {

    List<String> testClasspathElements = mavenProject.getTestClasspathElements();

    URL[] urls = new URL[testClasspathElements.size()];
    for (int i = 0; i < testClasspathElements.size(); i++) {
      String testClasspathElement = testClasspathElements.get(i);
      URL url = Paths.get(testClasspathElement).toUri().toURL();
      urls[i] = url;
    }

    return urls;
  }

  private String[] compiledClassFiles() throws IOException {
    DirectoryScanner scanner = new DirectoryScanner();
    scanner.setBasedir(compiledClassesFolder.getCanonicalPath());
    scanner.setIncludes(new String[]{"**/*.class"});
    scanner.scan();
    String[] files = scanner.getIncludedFiles();
    Arrays.sort(files);
    return files;
  }

  private String pathToClassName(String path) {
    return path.substring(0, path.length() - ".class".length()).replace(File.separatorChar, '.');
  }

  private boolean isConcreteClass(Class<?> clazz) {
    return !clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers());
  }
}
