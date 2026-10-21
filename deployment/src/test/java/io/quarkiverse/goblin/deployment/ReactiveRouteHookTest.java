package io.quarkiverse.goblin.deployment;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.MethodInfo;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import io.quarkiverse.goblin.internal.config.TargetRules;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.builditem.BytecodeTransformerBuildItem;
import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.RouteBase;
import io.quarkus.vertx.web.RouteFilter;
import io.vertx.ext.web.RoutingContext;

/**
 * The build-time side of the reactive-route support (issue #64): the hook woven into Quarkus's {@code RouteHandler},
 * and reactive-route classes kept out of the SERVICE layer weaving.
 */
class ReactiveRouteHookTest {

    private static final TargetRules NO_RULES = new TargetRules(List.of(), List.of(), List.of());

    @Test
    void guardsEveryRouteInvocationOfTheRealRouteHandler() throws IOException {
        byte[] woven = weave(routeHandlerBytes());

        ClassNode node = new ClassNode();
        new ClassReader(woven).accept(node, 0);
        MethodNode handle = node.methods.stream()
                .filter(method -> method.name.equals("handle")
                        && method.desc.equals("(Lio/vertx/ext/web/RoutingContext;)V"))
                .findFirst().orElseThrow();
        long invokes = calls(handle, "io/quarkus/vertx/web/runtime/RouteHandler", "invoke");
        long hooks = calls(handle, GoblinLayerHooksProcessor.GOBLIN_ROUTE_HOOK, "beforeInvoke");
        // handle() invokes the route on every path (today: request context already active, or activated by it)
        assertTrue(invokes >= 1, "RouteHandler.handle() must still invoke the route");
        assertEquals(invokes, hooks, "every route invocation must be guarded by the hook");
    }

    @Test
    void wovenRouteHandlerPassesBytecodeVerification() throws IOException {
        StringWriter errors = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(weave(routeHandlerBytes())), getClass().getClassLoader(), false,
                new PrintWriter(errors));
        assertEquals("", errors.toString());
    }

    @Test
    void leavesOtherMethodsUntouched() throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(weave(routeHandlerBytes())).accept(node, 0);
        for (MethodNode method : node.methods) {
            if (!method.name.equals("handle")) {
                assertEquals(0, calls(method, GoblinLayerHooksProcessor.GOBLIN_ROUTE_HOOK, "beforeInvoke"),
                        method.name);
            }
        }
    }

    @Test
    void nothingIsWovenWithoutReactiveRoutes() {
        List<BytecodeTransformerBuildItem> produced = new ArrayList<>();
        new GoblinLayerHooksProcessor().registerReactiveRouteHook(new Capabilities(Set.of()), produced::add);
        assertTrue(produced.isEmpty(), "an application without quarkus-reactive-routes must stay untouched");

        new GoblinLayerHooksProcessor().registerReactiveRouteHook(
                new Capabilities(Set.of(Capability.REACTIVE_ROUTES)), produced::add);
        assertEquals(1, produced.size());
        assertEquals(GoblinLayerHooksProcessor.ROUTE_HANDLER, produced.get(0).getClassToTransform());
    }

    @Test
    void aHandleMethodWithoutRouteInvocationIsLeftUnchanged() throws IOException {
        String resource = NoRouteInvocation.class.getName().replace('.', '/') + ".class";
        byte[] original;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            original = in.readAllBytes();
        }
        ClassNode node = new ClassNode();
        new ClassReader(weave(original)).accept(node, 0);
        for (MethodNode method : node.methods) {
            assertEquals(0, calls(method, GoblinLayerHooksProcessor.GOBLIN_ROUTE_HOOK, "beforeInvoke"), method.name);
        }
    }

    /** A {@code handle(RoutingContext)} that never calls {@code invoke}: the weaver only warns. */
    static class NoRouteInvocation {
        public void handle(RoutingContext context) {
            context.next();
        }
    }

    @Test
    void reactiveRouteClassesAreNeverWovenByTheServiceLayer() throws IOException {
        Index index = Index.of(RouteMethods.class, RepeatedRoutes.class, RouteBaseHelper.class, FilterOnly.class,
                PlainBean.class);

        assertFalse(eligible(index, RouteMethods.class, "hello"));
        assertFalse(eligible(index, RouteMethods.class, "helper"), "a helper of a route class is an HTTP class too");
        assertFalse(eligible(index, RepeatedRoutes.class, "twice"));
        assertFalse(eligible(index, RouteBaseHelper.class, "helper"));
        assertFalse(eligible(index, FilterOnly.class, "filter"));
        assertTrue(eligible(index, PlainBean.class, "work"));
    }

    static class RouteMethods {
        @Route(path = "/hello")
        public String hello() {
            return "hello";
        }

        public String helper() {
            return "helper";
        }
    }

    static class RepeatedRoutes {
        @Route(path = "/one")
        @Route(path = "/two")
        public String twice() {
            return "twice";
        }
    }

    @RouteBase(path = "/base")
    static class RouteBaseHelper {
        public String helper() {
            return "helper";
        }
    }

    static class FilterOnly {
        @RouteFilter
        public void filter(RoutingContext context) {
            context.next();
        }
    }

    static class PlainBean {
        public String work() {
            return "work";
        }
    }

    private static boolean eligible(Index index, Class<?> clazz, String methodName) {
        ClassInfo info = index.getClassByName(DotName.createSimple(clazz.getName()));
        MethodInfo method = info.methods().stream().filter(m -> m.name().equals(methodName)).findFirst().orElseThrow();
        return GoblinBuildStep.isMethodEligible(info, method, NO_RULES, index);
    }

    private static byte[] routeHandlerBytes() throws IOException {
        String resource = GoblinLayerHooksProcessor.ROUTE_HANDLER.replace('.', '/') + ".class";
        try (InputStream in = ReactiveRouteHookTest.class.getClassLoader().getResourceAsStream(resource)) {
            return in.readAllBytes();
        }
    }

    private static byte[] weave(byte[] original) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        reader.accept(new GoblinLayerHooksProcessor.RouteHandlerHookVisitor(writer), 0);
        return writer.toByteArray();
    }

    private static long calls(MethodNode method, String owner, String name) {
        long count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)
                    && (call.getOpcode() == Opcodes.INVOKEVIRTUAL || call.getOpcode() == Opcodes.INVOKESTATIC)) {
                count++;
            }
        }
        return count;
    }
}
