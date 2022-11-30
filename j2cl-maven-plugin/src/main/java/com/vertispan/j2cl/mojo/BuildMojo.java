package com.vertispan.j2cl.mojo;

import com.vertispan.j2cl.build.BlockingBuildListener;
import com.vertispan.j2cl.build.BuildService;
import com.vertispan.j2cl.build.DefaultDiskCache;
import com.vertispan.j2cl.build.DiskCache;
import com.vertispan.j2cl.build.Project;
import com.vertispan.j2cl.build.TaskRegistry;
import com.vertispan.j2cl.build.TaskScheduler;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Transpiles this project and all of its dependencies, then combines them all into a single JS
 * executable.
 */
@Mojo(name = "build", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, defaultPhase = LifecyclePhase.PREPARE_PACKAGE)
public class BuildMojo extends AbstractBuildMojo {

    /**
     * The path within {@link #webappDirectory} to write the JavaScript file generated by this goal.
     */
    @Parameter(defaultValue = "${project.artifactId}/${project.artifactId}.js", required = true)
    protected String initialScriptFilename;

    /**
     * The output directory for this goal. Note that this is used in conjunction with {@link #initialScriptFilename}
     * so that more than one goal or even project can share the same webappDirectory, but have their own sub-directory
     * and output file.
     */
    @Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}", required = true)
    protected String webappDirectory;

    /**
     * @deprecated Will be removed in 0.21
     */
    @Parameter
    @Deprecated
    protected Set<String> externs = new TreeSet<>();

    /**
     * @deprecated Will be removed in 0.21
     */
    @Deprecated
    @Parameter
    protected List<String> entrypoint = new ArrayList<>();

    /**
     * Describes how the output should be built - presently supports five modes, four of which are closure-compiler
     * "compilationLevel" argument options, and an additional special case for J2cl-base applications. The quoted
     * descriptions here explain how closure-compiler defines them.
     * <ul>
     *     <li>
     *         {@code ADVANCED_OPTIMIZATIONS} - "ADVANCED_OPTIMIZATIONS aggressively reduces code size by renaming
     *         function names and variables, removing code which is never called, etc." This is typically what is
     *         expected for production builds.
     *     </li>
     *     <li>
     *         {@code SIMPLE_OPTIMIZATIONS} - "SIMPLE_OPTIMIZATIONS performs transformations to the input JS that
     *         do not require any changes to JS that depend on the input JS." Generally not useful in this plugin -
     *         slower than BUNDLE, much bigger than ADVANCED_OPTIMIZATIONS
     *     </li>
     *     <li>
     *         {@code WHITESPACE_ONLY} - "WHITESPACE_ONLY removes comments and extra whitespace in the input JS."
     *         Generally not useful in this plugin - slower than BUNDLE, much bigger than ADVANCED_OPTIMIZATIONS
     *     </li>
     *     <li>
     *         {@code BUNDLE} - "Simply orders and concatenates files to the output." The GWT fork of closure also
     *         prepends define statements, and provides wiring for sourcemaps.
     *     </li>
     *     <li>
     *         {@code BUNDLE_JAR} - Not a "real" closure-compiler option. but instead invokes BUNDLE on each
     *         classpath entry and generates a single JS file which will load those bundled files in order. Enables
     *         the compiler to cache results for each dependency, rather than re-generate a single large JS file.
     *     </li>
     * </ul>
     */
    @Parameter(defaultValue = "ADVANCED_OPTIMIZATIONS", property = "compilationLevel")
    protected String compilationLevel;

    /**
     * ECMAScript language level of generated JavasScript. Values correspond to the Closure Compiler reference:
     * https://github.com/google/closure-compiler/wiki/Flags-and-Options
     */
    @Parameter(defaultValue = "ECMASCRIPT5", property = "languageOut")
    protected String languageOut;

    /**
     * Closure flag: "Override the value of a variable annotated {@code @define}. The format is
     * {@code &lt;name&gt;[=&lt;val&gt;]}, where {@code &lt;name&gt;} is the name of a {@code @define}
     * variable and {@code &lt;val&gt;} is a boolean, number, or a single-quoted string that contains
     * no single quotes. If {@code [=&lt;val&gt;]} is omitted, the variable is marked true"
     * <p></p>
     * In this plugin the format is to provided tags for each define key, where the text contents will represent the
     * value.
     * <p></p>
     * In the context of J2CL and Java, this can be used to define values for system properties.
     */
    @Parameter
    protected Map<String, String> defines = new TreeMap<>();

    /**
     * Closure flag: "Rewrite ES6 library calls to use polyfills provided by the compiler's runtime."
     * Unlike in closure-compiler, defaults to false.
     */
    @Parameter(defaultValue = "false")
    protected boolean rewritePolyfills;

    /**
     * Closure flag: "Source of translated messages. Currently only supports XTB."
     */
    @Parameter
    protected TranslationsFileConfig translationsFile;

    /**
     * Closure flag: "Determines the set of builtin externs to load. Options: BROWSER, CUSTOM. Defaults to BROWSER."
     *
     * Presently we default to BROWSER, but are considering changing this to CUSTOM if we include externs files in
     * the generate jsinterop artifacts, so that each set of bindings is self-contained.
     */
    @Parameter(defaultValue = "BROWSER")
    protected String env;

    /**
     * Whether or not to leave Java assert checks in the compiled code. In j2cl:build, defaults to true. Has no
     * effect when the compilation level isn't set to ADVANCED_OPTIMIZATIONS, assertions will always remain
     * enabled.
     */
    @Parameter(defaultValue = "false")
    protected boolean checkAssertions;

    /**
     * @deprecated Will be removed in 0.21
     */
    @Deprecated
    @Parameter(defaultValue = "SORT_ONLY")
    protected String dependencyMode;

    /**
     * True to enable sourcemaps to be built into the project output.
     */
    @Parameter(defaultValue = "false")
    protected boolean enableSourcemaps;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        // pre-create the directory so it is easier to find up front, even if it starts off empty
        try {
            Files.createDirectories(Paths.get(webappDirectory));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to create the webappDirectory " + webappDirectory, e);
        }

        PluginDescriptor pluginDescriptor = (PluginDescriptor) getPluginContext().get("pluginDescriptor");
        String pluginVersion = pluginDescriptor.getVersion();

        Plugin plugin = project.getPlugin(pluginDescriptor.getPlugin().getKey());

        // accumulate configs and defaults, provide a lambda we can read dot-separated values from
        ExpressionEvaluator expressionEvaluator = new PluginParameterExpressionEvaluator(mavenSession, mojoExecution);

        //TODO need to be very careful about allowing these to be configurable, possibly should tie them to the "plugin version" aspect of the hash
        //     or stitch them into the module's dependencies, that probably makes more sense...
        List<File> extraClasspath = Arrays.asList(
                getFileWithMavenCoords(jreJar),
                getFileWithMavenCoords(internalAnnotationsJar),
                getFileWithMavenCoords(jsinteropAnnotationsJar),
                getFileWithMavenCoords("com.vertispan.jsinterop:base:" + Versions.VERTISPAN_JSINTEROP_BASE_VERSION)//TODO stop hardcoding this when goog releases a "base" which actually works on both platforms
        );

        List<Artifact> extraJsZips = Arrays.asList(
                getMavenArtifactWithCoords(jreJsZip),
                getMavenArtifactWithCoords(bootstrapJsZip)
        );


        // merge may be unnecessary, just use mojoExecution.getConfiguration()?
        Xpp3DomConfigValueProvider config = new Xpp3DomConfigValueProvider(merge((Xpp3Dom) plugin.getConfiguration(), mojoExecution.getConfiguration()), expressionEvaluator, repoSession, repositories, repoSystem, extraClasspath, getLog());

        ProjectBuildingRequest request = new DefaultProjectBuildingRequest(mavenSession.getProjectBuildingRequest());

        // build project from maven project and dependencies, recursively
        LinkedHashMap<String, Project> builtProjects = new LinkedHashMap<>();
        Project p;
        try {
            p = buildProject(project, project.getArtifact(), false, projectBuilder, request, pluginVersion, builtProjects, Artifact.SCOPE_COMPILE_PLUS_RUNTIME, getDependencyReplacements(), extraJsZips);
        } catch (ProjectBuildingException e) {
            throw new MojoExecutionException("Failed to build project structure", e);
        }

        // given the build output, determine what tasks we're going to run
        String outputTask = getOutputTask(compilationLevel);

        // construct other required elements to get the work done
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(getWorkerTheadCount());
        final DiskCache diskCache;
        try {
            diskCache = new DefaultDiskCache(getCacheDir().toFile(), executor);
        } catch (IOException ioException) {
            throw new MojoExecutionException("Failed to create cache", ioException);
        }

        addShutdownHook(executor, diskCache);

        MavenLog mavenLog = new MavenLog(getLog());
        TaskScheduler taskScheduler = new TaskScheduler(executor, diskCache, mavenLog);

        TaskRegistry taskRegistry = createTaskRegistry();

        // Given these, build the graph of work we need to complete
        BuildService buildService = new BuildService(taskRegistry, taskScheduler, diskCache);
        buildService.assignProject(p, outputTask, config);

        // Get the hash of all current files, since we aren't running a watch service
        buildService.initialHashes();

        // perform the build
        BlockingBuildListener listener = new BlockingBuildListener();
        try {
            buildService.requestBuild(listener);
            listener.blockUntilFinished();
            boolean success = listener.isSuccess();
            if (!success) {
                throw new MojoFailureException("Build failed, check log for failures");
            }
        } catch (InterruptedException e) {
            throw new MojoExecutionException("Interrupted", e);
        }
    }

}
