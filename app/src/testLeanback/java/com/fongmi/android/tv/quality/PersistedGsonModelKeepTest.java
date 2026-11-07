package com.fongmi.android.tv.quality;

import com.google.gson.annotations.SerializedName;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Gson 持久化模型的 R8 可见性护栏。
 *
 * <p>为什么需要：Gson 只按字段名反射读写，R8 看不到这些访问，会把字段改名（反序列化后全是 null），
 * 或把“只写不读”的字段整条删除（JSON 键消失）。debug 包永远测不出来，只有 release 包会出现
 * “设置读过一次就回退/丢失”。{@code app/proguard-rules.pro} 里维护了两层保护：
 * 字段级的 {@code @SerializedName} + 全局 keepclassmembers 规则，以及逐类的 {@code -keep class X { *; }}。
 * 本条测试把“漏掉某一层”变成一次可见的断言失败。
 *
 * <p>如何枚举调用点（不是手写过模型清单）：<br>
 * 1. 扫描 {@code app/src/main/java} 下所有源码，挑出“持久化上下文”文件——文件里出现
 *    {@code Prefers.put(} / {@code Prefers.getString(} / {@code Path.write(} / {@code GsonProvider.get()}
 *    这类落盘或回读入口；<br>
 * 2. 只在这些文件里抽取 Gson 实际使用的类型实参：{@code new TypeToken<...>} 的泛型内容
 *    （按尖括号配平解析，支持嵌套泛型）与 {@code fromJson(..., X.class)} 的类字面量（含 {@code X[].class}）；<br>
 * 3. 把简单类名解析成 FQCN：同文件嵌套类 → 显式 import → 同包 → classpath 类索引唯一命中；<br>
 * 4. 反射读取每个实例字段，带 {@code @SerializedName} 或所属类命中 keep 规则才算通过。
 *
 * <p>因此新增一个持久化模型时，只要它经过上述调用点就会被自动发现，本测试无需跟着改。
 * 已知盲区：没有任何类型实参的裸 {@code toJson(obj)} + 裸 {@code fromJson(json)}，以及非 Gson
 * 序列化器写入的数据；这两类仍需人工确认。
 */
public class PersistedGsonModelKeepTest {

    private static final String SOURCE_ROOT = "src/main/java";
    private static final String PACKAGE_PREFIX = "com.fongmi.android.tv";
    private static final List<String> PERSISTENCE_MARKERS = List.of(
            "Prefers.put(", "Prefers.getString(", "Path.write(", "GsonProvider.get()");
    private static final Set<String> NON_MODELS = new HashSet<>(Arrays.asList(
            "List", "Map", "Set", "Collection", "Iterable", "Object", "String", "Integer", "Long",
            "Double", "Float", "Short", "Byte", "Character", "Boolean", "Number",
            "LinkedHashMap", "HashMap", "ArrayList", "LinkedHashSet", "HashSet", "TypeToken",
            "JsonObject", "JsonElement", "JsonArray", "JsonPrimitive", "JsonNull", "Date"));

    private static final Pattern CLASS_LITERAL = Pattern.compile(
            "fromJson\\s*\\([^;]*?,\\s*([A-Za-z_$][A-Za-z0-9_$.]*)\\s*(?:\\[\\])?\\.class",
            Pattern.DOTALL);
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$.]*");
    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(static\\s+)?([A-Za-z0-9_$.]+(?:\\*)?)\\s*;",
            Pattern.MULTILINE);
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([A-Za-z0-9_.]+)\\s*;", Pattern.MULTILINE);
    /** 只认整类保留：{@code -keep class X { *; }}（含 allowobfuscation 等修饰）。keepclassmembers 同样能保护字段名，一并接受。 */
    private static final Pattern KEEP_RULE = Pattern.compile(
            "^\\s*-keep(?:classmembers)?(?:\\s*,\\s*[\\w-]+)*\\s+class\\s+(\\S+)\\s*\\{\\s*\\*\\s*;\\s*}\\s*$",
            Pattern.MULTILINE);

    @Test
    public void everyPersistedModelIsProtectedFromR8() throws Exception {
        File app = findAppDir();
        Map<String, Set<String>> refs = collectPersistedModels(new File(app, SOURCE_ROOT));
        // 扫描本身失效（比如源码根目录变了、marker 改了）时必须立刻暴露，而不是静默通过
        assertFalse("没有发现任何被 Gson 持久化的模型，扫描逻辑可能已失效", refs.isEmpty());
        assertTrue("只发现 " + refs.size() + " 个持久化模型，疑似扫描漏掉调用点", refs.size() >= 10);

        List<Pattern> keepRules = readKeepRules(new File(app, "proguard-rules.pro"));
        List<String> failures = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : refs.entrySet()) {
            String fqcn = entry.getKey();
            if (fqcn == null) continue;
            Class<?> type = loadClass(fqcn);
            if (type == null) {
                if (fqcn.startsWith("<unresolved>.")) {
                    unresolved.add(fqcn.substring("<unresolved>.".length()) + " ← " + entry.getValue());
                } else if (!isKeptName(fqcn, keepRules)) {
                    // 依赖模块的类不在单测 JVM 里，无法反射字段；此时至少要求显式 keep
                    failures.add("持久化模型不在测试 JVM 可加载范围，且没有显式 keep: " + fqcn + "\n"
                            + "  发现位置: " + entry.getValue() + "\n"
                            + "  请在 app/proguard-rules.pro 追加: " + suggestion(fqcn));
                }
                continue;
            }
            List<String> unprotected = unprotectedFields(type, keepRules);
            if (unprotected.isEmpty()) continue;
            failures.add("持久化模型缺少 R8 保护: " + fqcn + "\n"
                    + "  发现位置: " + entry.getValue() + "\n"
                    + "  未标注 @SerializedName 的字段: " + unprotected + "\n"
                    + "  请在 app/proguard-rules.pro 追加: " + suggestion(fqcn));
        }
        if (!unresolved.isEmpty()) {
            failures.add("扫描到无法解析的类型（请更新类型解析，否则该模型不会被校验）:\n  " + String.join("\n  ", unresolved));
        }
        if (!failures.isEmpty()) fail(String.join("\n\n", failures));
    }

    /** 自检：checker 必须能识别“无注解、无 keep”的模型，否则上面那条断言可能是恒绿。 */
    @Test
    public void checkerFlagsModelWithoutAnnotationOrKeep() {
        List<String> unprotected = unprotectedFields(UnannotatedFixture.class, List.of());
        assertEquals(Arrays.asList(
                UnannotatedFixture.class.getName() + "#alpha",
                UnannotatedFixture.class.getName() + "#beta"), unprotected);
        assertEquals("-keep class " + UnannotatedFixture.class.getName() + " { *; }",
                suggestion(UnannotatedFixture.class.getName()));
    }

    /** 自检：带 @SerializedName 的类、命中 keep 规则的类都必须放行。 */
    @Test
    public void checkerPassesAnnotatedOrKeptModel() {
        assertTrue(unprotectedFields(AnnotatedFixture.class, List.of()).isEmpty());
        assertTrue(unprotectedFields(UnannotatedFixture.class,
                List.of(Pattern.compile(globToRegex(UnannotatedFixture.class.getName())))).isEmpty());
    }

    /** 自检：keep 规则的通配符语义要对齐 R8（{@code **} 跨包、{@code *} 不跨包）。 */
    @Test
    public void keepGlobMatchesR8Semantics() {
        Pattern packageKeep = Pattern.compile(globToRegex("com.fongmi.android.tv.bean.**"));
        assertTrue(packageKeep.matcher("com.fongmi.android.tv.bean.Vod").matches());
        assertTrue(packageKeep.matcher("com.fongmi.android.tv.bean.drive.DriveCheckResult").matches());
        assertFalse(packageKeep.matcher("com.fongmi.android.tv.beanX.Vod").matches());

        Pattern singleSegment = Pattern.compile(globToRegex("com.fongmi.android.tv.bean.*"));
        assertTrue(singleSegment.matcher("com.fongmi.android.tv.bean.Vod").matches());
        assertFalse(singleSegment.matcher("com.fongmi.android.tv.bean.drive.DriveCheckResult").matches());

        Pattern nested = Pattern.compile(globToRegex("com.fongmi.android.tv.utils.LoginStateSync$*"));
        assertTrue(nested.matcher("com.fongmi.android.tv.utils.LoginStateSync$Snapshot").matches());
    }

    // ---------------------------------------------------------------- 扫描

    private static Map<String, Set<String>> collectPersistedModels(File sourceRoot) throws IOException {
        Map<String, Set<String>> models = new LinkedHashMap<>();
        Map<String, List<String>> classIndex = null;
        try (Stream<java.nio.file.Path> stream = Files.walk(sourceRoot.toPath())) {
            List<java.nio.file.Path> files = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .collect(Collectors.toList());
            for (java.nio.file.Path path : files) {
                String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                if (!isPersistenceContext(text)) continue;
                String relative = sourceRoot.toPath().relativize(path).toString();
                SourceInfo info = SourceInfo.of(text, relative);
                for (TypeRef ref : extractTypeRefs(text)) {
                    if (NON_MODELS.contains(ref.name)) continue;
                    if (classIndex == null) classIndex = classIndex();
                    String fqcn = resolve(ref.name, info, classIndex);
                    if (fqcn == null) fqcn = "<unresolved>." + ref.name;
                    models.computeIfAbsent(fqcn, k -> new LinkedHashSet<>())
                            .add(relative + ":" + lineOf(text, ref.offset));
                }
            }
        }
        return models;
    }

    private static boolean isPersistenceContext(String text) {
        for (String marker : PERSISTENCE_MARKERS) if (text.contains(marker)) return true;
        return false;
    }

    /**
     * 抽取 Gson 的显式类型实参：TypeToken 泛型内容按尖括号配平解析，类字面量用正则。
     * 裸 toJson 无法从源码推断运行时类型，是已知盲区（见类注释）。
     */
    private static List<TypeRef> extractTypeRefs(String text) {
        List<TypeRef> refs = new ArrayList<>();
        int from = 0;
        while ((from = text.indexOf("new TypeToken<", from)) >= 0) {
            int open = from + "new TypeToken".length();
            int close = matchAngle(text, open);
            if (close < 0) break;
            Matcher matcher = IDENTIFIER.matcher(text.substring(open + 1, close));
            while (matcher.find()) refs.add(new TypeRef(matcher.group(), from));
            from = close + 1;
        }
        Matcher literal = CLASS_LITERAL.matcher(text);
        while (literal.find()) refs.add(new TypeRef(literal.group(1), literal.start()));
        return refs;
    }

    private static int matchAngle(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * 简单类名 → FQCN。显式 import 视为权威答案（Java 里它与同名嵌套类冲突，不会二者并存），
     * 这样即使类型来自 catvod 之类不在单测 JVM classpath 的模块，也能定位到真实类名。
     */
    private static String resolve(String raw, SourceInfo info, Map<String, List<String>> classIndex) {
        String name = raw.startsWith("?") ? raw.substring(1).trim() : raw;
        if (NON_MODELS.contains(name)) return null;
        if (name.contains(".")) return name;

        for (String imported : info.imports) if (imported.endsWith("." + name)) return imported;
        String nested = info.pkg + "." + info.outer + "$" + name;
        if (loadClass(nested) != null) return nested;
        String samePackage = info.pkg + "." + name;
        if (loadClass(samePackage) != null) return samePackage;
        for (String wildcard : info.wildcards) {
            String candidate = wildcard + "." + name;
            if (loadClass(candidate) != null) return candidate;
        }
        for (String candidate : classIndex.getOrDefault(name, List.of())) {
            if (loadClass(candidate) != null) return candidate;
        }
        return null;
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    // ------------------------------------------------------------ 类型索引

    private static Map<String, List<String>> classIndex() {
        Map<String, List<String>> index = new HashMap<>();
        String classPath = System.getProperty("java.class.path", "");
        for (String entry : classPath.split(File.pathSeparator)) {
            if (entry.isEmpty()) continue;
            File file = new File(entry);
            if (file.isDirectory()) indexDirectory(file, index);
            else if (file.isFile() && (entry.endsWith(".jar") || entry.endsWith(".zip"))) indexArchive(file, index);
        }
        return index;
    }

    private static void indexDirectory(File root, Map<String, List<String>> index) {
        File packageDir = new File(root, PACKAGE_PREFIX.replace('.', File.separatorChar));
        if (!packageDir.isDirectory()) return;
        try (Stream<java.nio.file.Path> stream = Files.walk(packageDir.toPath())) {
            stream.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                String relative = packageDir.toPath().relativize(p).toString();
                addToIndex(index, PACKAGE_PREFIX + "." + relative.replace(File.separatorChar, '.')
                        .substring(0, relative.length() - ".class".length()));
            });
        } catch (IOException ignored) {
        }
    }

    private static void indexArchive(File archive, Map<String, List<String>> index) {
        String prefix = PACKAGE_PREFIX.replace('.', '/') + "/";
        try (ZipFile zip = new ZipFile(archive)) {
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(prefix) || !name.endsWith(".class")) continue;
                addToIndex(index, name.substring(0, name.length() - ".class".length()).replace('/', '.'));
            }
        } catch (IOException ignored) {
        }
    }

    private static void addToIndex(Map<String, List<String>> index, String fqcn) {
        String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        simple = simple.substring(simple.lastIndexOf('$') + 1);
        index.computeIfAbsent(simple, k -> new ArrayList<>()).add(fqcn);
    }

    private static Class<?> loadClass(String fqcn) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader != null) {
            try {
                return Class.forName(fqcn, false, loader);
            } catch (Throwable ignored) {
            }
        }
        try {
            return Class.forName(fqcn, false, PersistedGsonModelKeepTest.class.getClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------- keep 规则

    private static List<Pattern> readKeepRules(File proguard) throws IOException {
        assertTrue("找不到 " + proguard, proguard.isFile());
        String text = new String(Files.readAllBytes(proguard.toPath()), StandardCharsets.UTF_8);
        List<Pattern> rules = new ArrayList<>();
        Matcher matcher = KEEP_RULE.matcher(text);
        while (matcher.find()) rules.add(Pattern.compile(globToRegex(matcher.group(1))));
        assertFalse("没有从 " + proguard.getName() + " 解析到任何整类 keep 规则，解析逻辑可能失效", rules.isEmpty());
        return rules;
    }

    /** R8 通配符：{@code **} 可跨包匹配任意字符，{@code *} 不跨包（不含 {@code .}）。 */
    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^.]*");
                }
            } else if (c == '$') {
                regex.append("\\$");
            } else if ("\\^.+?()[]{}|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        return regex.toString();
    }

    // ------------------------------------------------------------ 字段检查

    /**
     * 返回未受保护的实例字段（{@code 类#字段}）。逐层检查父类：父类字段同样参与 JSON 键，
     * 父类没有命中 keep 时，仅给子类加 keep 并不能保住父类字段名。
     */
    private static List<String> unprotectedFields(Class<?> type, List<Pattern> keepRules) {
        List<String> unprotected = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            if (isKept(current, keepRules)) continue;
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()) continue;
                if (field.getName().startsWith("this$")) continue;
                if (field.isAnnotationPresent(SerializedName.class)) continue;
                unprotected.add(current.getName() + "#" + field.getName());
            }
        }
        return unprotected;
    }

    private static boolean isKept(Class<?> type, List<Pattern> keepRules) {
        return isKeptName(type.getName(), keepRules);
    }

    private static boolean isKeptName(String fqcn, List<Pattern> keepRules) {
        for (Pattern rule : keepRules) if (rule.matcher(fqcn).matches()) return true;
        return false;
    }

    private static String suggestion(String fqcn) {
        return "-keep class " + fqcn + " { *; }";
    }

    // ---------------------------------------------------------------- 环境

    /** Gradle 单测的工作目录是模块目录（app），这里给出少量兼容候选。 */
    private static File findAppDir() {
        for (String base : new String[]{".", "app", ".."}) {
            File dir = new File(base);
            if (new File(dir, SOURCE_ROOT).isDirectory() && new File(dir, "proguard-rules.pro").isFile()) {
                return dir;
            }
        }
        throw new AssertionError("找不到 app 模块目录（工作目录 " + new File(".").getAbsolutePath() + "）");
    }

    /** 单个源码文件的包名、外层类名与 import 信息，供简单类名解析使用。 */
    private static final class SourceInfo {

        final String pkg;
        final String outer;
        final List<String> imports;
        final List<String> wildcards;

        private SourceInfo(String pkg, String outer, List<String> imports, List<String> wildcards) {
            this.pkg = pkg;
            this.outer = outer;
            this.imports = imports;
            this.wildcards = wildcards;
        }

        static SourceInfo of(String text, String relative) {
            Matcher packageMatcher = PACKAGE.matcher(text);
            String pkg = packageMatcher.find() ? packageMatcher.group(1) : "";
            String outer = new File(relative).getName().replace(".java", "");
            List<String> imports = new ArrayList<>();
            List<String> wildcards = new ArrayList<>();
            Matcher importMatcher = IMPORT.matcher(text);
            while (importMatcher.find()) {
                if (importMatcher.group(1) != null) continue; // static import 不含类型
                String value = importMatcher.group(2);
                if (value.endsWith("*")) wildcards.add(value.substring(0, value.length() - 1));
                else imports.add(value);
            }
            return new SourceInfo(pkg, outer, imports, wildcards);
        }
    }

    private static final class TypeRef {

        final String name;
        final int offset;

        TypeRef(String name, int offset) {
            this.name = name;
            this.offset = offset;
        }
    }

    // -------------------------------------------------------------- fixtures

    /** 模拟“新增持久化模型但忘了加注解/keep”的情况，checker 必须报出来。 */
    static class UnannotatedFixture {

        public String alpha;
        public int beta;
    }

    /** 模拟字段级注解方案。 */
    static class AnnotatedFixture {

        @SerializedName("alpha")
        public String alpha;
    }
}
