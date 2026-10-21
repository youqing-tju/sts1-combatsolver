package io.github.youqing.sts1solver.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import io.github.youqing.sts1solver.diag.Trace;

/**
 * 补丁清单：启动时把「实际挂上去的补丁」和「它们要改的方法是否存在」打出来。
 *
 * <p>为什么要有这个类：本项目的三次严重故障都是「补丁没生效」，而当时只能从行为异常反推：
 * <ul>
 *   <li>{@code AbstractDungeon.update()} —— 界面打开时不执行；</li>
 *   <li>{@code Hitbox.clicked} —— 每帧被重写，设了也白设；</li>
 *   <li>{@code MonsterRoom.onPlayerEntry} —— 精英/首领房重写了它，父类补丁体不执行。</li>
 * </ul>
 * 蓝本的解法是在 {@code Entry.Initialize()} 里把所有补丁当**清单**显式列出。
 * 我们做得更硬一点：扫描 jar 自己找出来，手写清单迟早会漂移。
 *
 * <p>另外会检查目标方法是否存在——方法被改名/删除时会直接标 ⚠。
 */
public final class PatchManifest {

    private static final String PACKAGE_PATH = "io/github/youqing/sts1solver/patches/";

    private PatchManifest() {
    }

    public static void report() {
        try {
            List<String> lines = collect();
            Trace.info("PATCHES " + lines.size() + " registered");
            for (String line : lines) {
                if (line.contains("⚠")) {
                    Trace.warn("  " + line);
                } else {
                    Trace.info("  " + line);
                }
            }
        } catch (Throwable t) {
            Trace.error("patch manifest failed", t);
        }
    }

    /** 每条形如 {@code Class$Nested -> target.Class#method(params)}，方法缺失时带 ⚠。 */
    public static List<String> collect() throws Exception {
        List<String> out = new ArrayList<String>();
        for (Class<?> clz : patchClasses()) {
            SpirePatch[] patches = clz.getAnnotationsByType(SpirePatch.class);
            for (SpirePatch p : patches) {
                out.add(describe(clz, p));
            }
        }
        Collections.sort(out);
        return out;
    }

    private static String describe(Class<?> patchClass, SpirePatch p) {
        StringBuilder sb = new StringBuilder();
        Class<?> outer = patchClass.getEnclosingClass();
        sb.append(outer == null ? patchClass.getSimpleName()
                                : outer.getSimpleName() + "." + patchClass.getSimpleName())
          .append(" -> ")
          .append(p.clz().getSimpleName()).append('.').append(p.method());

        Class<?>[] params = p.paramtypez();
        boolean noParams = params.length == 0 || (params.length == 1 && params[0] == void.class);
        if (!noParams) {
            sb.append('(');
            for (int i = 0; i < params.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(params[i].getSimpleName());
            }
            sb.append(')');
        }

        if (!methodExists(p.clz(), p.method(), noParams ? new Class<?>[0] : params)) {
            sb.append("  ⚠ METHOD NOT FOUND");
        }
        return sb.toString();
    }

    private static boolean methodExists(Class<?> type, String name, Class<?>[] params) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                Class<?>[] actual = m.getParameterTypes();
                if (actual.length != params.length) {
                    continue;
                }
                boolean same = true;
                for (int i = 0; i < actual.length; i++) {
                    if (!actual[i].equals(params[i])) {
                        same = false;
                        break;
                    }
                }
                if (same) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 扫描本 mod 的补丁包，找出所有带 @SpirePatch 的类。 */
    private static List<Class<?>> patchClasses() throws Exception {
        List<Class<?>> result = new ArrayList<Class<?>>();
        URL location = PatchManifest.class.getProtectionDomain().getCodeSource().getLocation();
        File root = new File(location.toURI());

        List<String> names = new ArrayList<String>();
        if (root.isFile()) {
            JarFile jar = new JarFile(root);
            try {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String n = entries.nextElement().getName();
                    if (n.startsWith(PACKAGE_PATH) && n.endsWith(".class") && n.indexOf('$') < 0) {
                        names.add(n);
                    }
                }
            } finally {
                jar.close();
            }
        } else {
            collectFromDirectory(new File(root, PACKAGE_PATH), names);
        }

        for (String n : names) {
            String cn = n.substring(0, n.length() - ".class".length()).replace('/', '.');
            try {
                // 我们的补丁都写成嵌套类（Outer$Inner），注解挂在嵌套类上，必须往下找一层
                collectAnnotated(Class.forName(cn, false, PatchManifest.class.getClassLoader()), result);
            } catch (Throwable ignored) {
                // 类加载失败不影响其他补丁的统计
            }
        }
        return result;
    }

    private static void collectAnnotated(Class<?> clz, List<Class<?>> out) {
        if (clz.getAnnotationsByType(SpirePatch.class).length > 0) {
            out.add(clz);
        }
        try {
            for (Class<?> nested : clz.getDeclaredClasses()) {
                collectAnnotated(nested, out);
            }
        } catch (Throwable ignored) {
            // 某些嵌套类可能加载失败，跳过即可
        }
    }

    private static void collectFromDirectory(File dir, List<String> names) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collectFromDirectory(f, names);
            } else if (f.getName().endsWith(".class") && f.getName().indexOf('$') < 0) {
                String rel = f.getPath().replace('\\', '/');
                int idx = rel.indexOf(PACKAGE_PATH);
                names.add(rel.substring(idx));
            }
        }
    }
}
