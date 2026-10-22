package io.quarkiverse.goblin.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.CompositeIndex;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Indexer;
import org.jboss.logging.Logger;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import io.quarkiverse.goblin.internal.WebClientArming;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.workspace.SourceDir;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.deployment.IsProduction;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Produce;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.BytecodeTransformerBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.gizmo.Gizmo;
import io.quarkus.maven.dependency.ResolvedDependency;

/**
 * Keeps the application's own code free of Goblin, which never reaches a production build: Goblin arms the
 * application's Vert.x {@code WebClient}s by itself in dev and test builds, and refuses application code that uses its
 * API, which would compile against the {@code provided} dependency and then fail in the packaged application.
 */
public class GoblinApplicationCodeProcessor {

    private static final Logger LOG = Logger.getLogger(GoblinApplicationCodeProcessor.class);

    private static final String GOBLIN_PACKAGE = "io.quarkiverse.goblin.";

    /**
     * The {@code WebClient} types whose static factories are rewritten, as internal names: the core client and its
     * Mutiny flavour.
     */
    private static final Set<String> WEB_CLIENT_TYPES = Set.of(
            "io/vertx/ext/web/client/WebClient",
            "io/vertx/mutiny/ext/web/client/WebClient");

    private static final Set<String> WEB_CLIENT_FACTORIES = Set.of("create", "wrap");

    private static final String ARMING_OWNER = WebClientArming.class.getName().replace('.', '/');

    static final String APPLICATION_CODE_MESSAGE = "Goblin must never reach production, but these application classes "
            + "use its API: %s. They compile against the provided dependency, yet the packaged application does not "
            + "contain Goblin, so they would fail there. Move them to src/test/java when only tests use them, or to a "
            + "module of their own declared provided, like Goblin, when dev mode needs them. A Vert.x WebClient needs "
            + "no Goblin call: Goblin arms the clients the application creates by itself, in dev and test mode.";

    /**
     * Rewrites every {@code WebClient.create(...)} and {@code WebClient.wrap(...)} call of the application classes to
     * hand the new client to {@link WebClientArming#arm(Object)}, so client-side assaults reach the application's Vert.x
     * {@code WebClient} calls without any Goblin code in the application. The application classes are those of the
     * application module (main and test classes) and of the other modules of the same build it depends on; a client
     * created by another extension or a third-party library (an OIDC token call, for instance) is left alone.
     *
     * @param applicationArchives the application archives, whose root holds the application module's classes
     * @param curateOutcome the application model, giving the other modules of the build
     * @param transformers producer for the bytecode transformations
     */
    @BuildStep(onlyIfNot = IsProduction.class)
    void armApplicationWebClients(ApplicationArchivesBuildItem applicationArchives, CurateOutcomeBuildItem curateOutcome,
            BuildProducer<BytecodeTransformerBuildItem> transformers) {
        IndexView applicationIndex = CompositeIndex.create(applicationArchives.getRootArchive().getIndex(),
                index(outputs(curateOutcome.getApplicationModel(), false)));
        Set<String> callers = new TreeSet<>();
        for (String type : WEB_CLIENT_TYPES) {
            for (ClassInfo user : applicationIndex.getKnownUsers(DotName.createSimple(type.replace('/', '.')))) {
                callers.add(user.name().toString());
            }
        }
        for (String caller : callers) {
            transformers.produce(new BytecodeTransformerBuildItem.Builder()
                    .setClassToTransform(caller)
                    .setCacheable(true)
                    .setVisitorFunction((className, visitor) -> new WebClientFactoryVisitor(visitor))
                    .build());
        }
    }

    /**
     * Fails a dev or test build whose packaged code uses the Goblin API: the main classes of the application module and
     * of the other modules of the same build it packages. Test classes are never packaged, and neither is a module the
     * application declares {@code provided} -- the place for dev-only code such as a custom assault -- so both may use
     * Goblin freely. When the build does not run from a workspace, there are no sources to check and the check is
     * skipped.
     *
     * @param combinedIndex the combined index, holding the Goblin classes
     * @param curateOutcome the application model, giving the modules of the build and their output directories
     * @throws IllegalStateException when a packaged class uses the Goblin API
     */
    @BuildStep(onlyIfNot = IsProduction.class)
    @Produce(ServiceStartBuildItem.class)
    void refuseGoblinInApplicationCode(CombinedIndexBuildItem combinedIndex, CurateOutcomeBuildItem curateOutcome) {
        List<Path> packagedOutputs = outputs(curateOutcome.getApplicationModel(), true);
        if (packagedOutputs.isEmpty()) {
            LOG.debug("Goblin: no workspace module, the application code is not checked for Goblin API usage");
            return;
        }
        Set<String> offenders = goblinUsers(index(packagedOutputs), combinedIndex.getIndex(), packagedOutputs);
        if (!offenders.isEmpty()) {
            throw new IllegalStateException(String.format(APPLICATION_CODE_MESSAGE, String.join(", ", offenders)));
        }
    }

    /**
     * Returns the main output directories of the application module and of the other modules of the same build it
     * depends on at runtime.
     *
     * @param model the application model
     * @param packagedOnly {@code true} to leave out the modules declared {@code provided}, which are never packaged
     * @return the main output directories, empty when the build does not run from a workspace
     */
    static List<Path> outputs(ApplicationModel model, boolean packagedOnly) {
        List<Path> outputs = new ArrayList<>();
        addMainOutputs(model.getAppArtifact().getWorkspaceModule(), outputs);
        for (ResolvedDependency dependency : model.getRuntimeDependencies()) {
            if (dependency.isWorkspaceModule()
                    && !(packagedOnly && "provided".equals(dependency.getScope()))) {
                addMainOutputs(dependency.getWorkspaceModule(), outputs);
            }
        }
        return outputs;
    }

    private static void addMainOutputs(WorkspaceModule module, List<Path> outputs) {
        if (module == null || !module.hasMainSources()) {
            return;
        }
        for (SourceDir dir : module.getMainSources().getSourceDirs()) {
            if (dir.getOutputDir() != null && !outputs.contains(dir.getOutputDir())) {
                outputs.add(dir.getOutputDir());
            }
        }
    }

    /**
     * Indexes the classes of the given output directories. Their modules may carry no Jandex index nor
     * {@code beans.xml}, so their classes are indexed here, with the references each one makes.
     *
     * @param outputs the output directories
     * @return the index of their classes
     */
    static IndexView index(Collection<Path> outputs) {
        Indexer indexer = new Indexer();
        for (Path output : outputs) {
            if (!Files.isDirectory(output)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(output)) {
                for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".class"))::iterator) {
                    try (InputStream in = Files.newInputStream(file)) {
                        indexer.index(in);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Goblin: cannot index " + output, e);
            }
        }
        return indexer.complete();
    }

    /**
     * Returns the application classes compiled from the main sources that refer to a Goblin class. Package-private for
     * tests.
     *
     * @param applicationIndex the index of the application classes
     * @param index the combined index, holding the Goblin classes
     * @param mainOutputs the output directories of the main sources
     * @return the names of the offending classes, sorted
     */
    static Set<String> goblinUsers(IndexView applicationIndex, IndexView index, Collection<Path> mainOutputs) {
        Set<String> offenders = new TreeSet<>();
        for (ClassInfo goblinClass : index.getKnownClasses()) {
            String name = goblinClass.name().toString();
            // the application's own classes may live under io.quarkiverse.goblin (the integration-tests app does)
            if (!name.startsWith(GOBLIN_PACKAGE) || applicationIndex.getClassByName(goblinClass.name()) != null) {
                continue;
            }
            for (ClassInfo user : applicationIndex.getKnownUsers(goblinClass.name())) {
                String userName = user.name().toString();
                if (isMainClass(userName, mainOutputs)) {
                    offenders.add(userName);
                }
            }
        }
        return offenders;
    }

    private static boolean isMainClass(String className, Collection<Path> mainOutputs) {
        String file = className.replace('.', '/') + ".class";
        for (Path output : mainOutputs) {
            if (output != null && Files.isRegularFile(output.resolve(file))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Passes the result of every {@code WebClient.create(...)} / {@code WebClient.wrap(...)} call to
     * {@link WebClientArming#arm(Object)}, leaving the client on the stack for the application.
     */
    static final class WebClientFactoryVisitor extends ClassVisitor {

        WebClientFactoryVisitor(ClassVisitor delegate) {
            super(Gizmo.ASM_API_VERSION, delegate);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new MethodVisitor(Gizmo.ASM_API_VERSION, visitor) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String method, String methodDescriptor,
                        boolean isInterface) {
                    super.visitMethodInsn(opcode, owner, method, methodDescriptor, isInterface);
                    if (opcode == Opcodes.INVOKESTATIC && WEB_CLIENT_TYPES.contains(owner)
                            && WEB_CLIENT_FACTORIES.contains(method)
                            && methodDescriptor.endsWith(")L" + owner + ";")) {
                        super.visitInsn(Opcodes.DUP);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, ARMING_OWNER, "arm", "(Ljava/lang/Object;)V",
                                false);
                    }
                }
            };
        }
    }
}
