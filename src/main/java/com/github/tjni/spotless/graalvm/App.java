package com.github.tjni.spotless.graalvm;

import com.diffplug.spotless.*;
import com.diffplug.spotless.Formatter;
import com.google.googlejavaformat.java.ImportOrderer;
import com.google.googlejavaformat.java.JavaFormatterOptions;
import com.google.googlejavaformat.java.RemoveUnusedImports;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class App {
  public static void main(String[] args) {
    Path srcDirectory = Paths.get(args[0]);

    Provisioner provisioner = new ClasspathProvisioner();

    FormatterStep step = RemoveUnusedImportsStep.create(provisioner);

    Formatter formatter = Formatter.builder()
        .rootDir(srcDirectory)
        .steps(List.of(step))
        .lineEndingsPolicy(LineEnding.PLATFORM_NATIVE.createPolicy())
        .encoding(StandardCharsets.UTF_8)
        .build();

    ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    List<Future<?>> tasks = new ArrayList<>();

    try {
      Files.walkFileTree(srcDirectory, new FileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if (file.toString().endsWith(".java")) {
            Future<?> task = executorService.submit(() -> {
              try {
                formatter.applyTo(file.toFile());
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });

            tasks.add(task);
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    for (Future<?> task : tasks) {
      try {
        task.get();
      } catch (ExecutionException | InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static final class ClasspathProvisioner implements Provisioner {
    @Override
    public Set<File> provisionWithTransitives(boolean withTransitives, Collection<String> mavenCoordinates) {
      /*String classpath = System.getProperty("java.class.path");
      return Arrays.stream(classpath.split(File.pathSeparator))
          .filter(path -> path.endsWith(".jar"))
          .map(File::new)
          .collect(Collectors.toSet());
       */
      return Collections.singleton(new File("."));
    }
  }

  /** Wraps up [google-java-format](https://github.com/google/google-java-format) as a FormatterStep. */
  public static class GoogleJavaFormatStep {
    // prevent direct instantiation
    private GoogleJavaFormatStep() {}

    private static final String DEFAULT_STYLE = "GOOGLE";
    static final String NAME = "google-java-format";
    static final String MAVEN_COORDINATE = "com.google.googlejavaformat:google-java-format:";
    static final String FORMATTER_CLASS = "com.google.googlejavaformat.java.Formatter";
    static final String FORMATTER_METHOD = "formatSource";

    private static final String OPTIONS_CLASS = "com.google.googlejavaformat.java.JavaFormatterOptions";
    private static final String OPTIONS_BUILDER_METHOD = "builder";
    private static final String OPTIONS_BUILDER_CLASS = "com.google.googlejavaformat.java.JavaFormatterOptions$Builder";
    private static final String OPTIONS_BUILDER_STYLE_METHOD = "style";
    private static final String OPTIONS_BUILDER_BUILD_METHOD = "build";
    private static final String OPTIONS_Style = "com.google.googlejavaformat.java.JavaFormatterOptions$Style";

    private static final String REMOVE_UNUSED_CLASS = "com.google.googlejavaformat.java.RemoveUnusedImports";
    private static final String REMOVE_UNUSED_METHOD = "removeUnusedImports";

    private static final String REMOVE_UNUSED_IMPORT_JavadocOnlyImports = "com.google.googlejavaformat.java.RemoveUnusedImports$JavadocOnlyImports";
    private static final String REMOVE_UNUSED_IMPORT_JavadocOnlyImports_Keep = "KEEP";

    private static final String IMPORT_ORDERER_CLASS = "com.google.googlejavaformat.java.ImportOrderer";
    private static final String IMPORT_ORDERER_METHOD = "reorderImports";

    /** Creates a step which formats everything - code, import order, and unused imports. */
    public static FormatterStep create(Provisioner provisioner) {
      return create(defaultVersion(), provisioner);
    }

    /** Creates a step which formats everything - code, import order, and unused imports. */
    public static FormatterStep create(String version, Provisioner provisioner) {
      return create(version, DEFAULT_STYLE, provisioner);
    }

    /** Creates a step which formats everything - code, import order, and unused imports. */
    public static FormatterStep create(String version, String style, Provisioner provisioner) {
      Objects.requireNonNull(version, "version");
      Objects.requireNonNull(style, "style");
      Objects.requireNonNull(provisioner, "provisioner");
      return FormatterStep.createLazy(NAME,
          () -> new State(NAME, version, style, provisioner),
          State::createFormat);
    }

    private static final int JRE_VERSION;

    static {
      String jre = System.getProperty("java.version");
      if (jre.startsWith("1.8")) {
        JRE_VERSION = 8;
      } else {
        Matcher matcher = Pattern.compile("(\\d+)").matcher(jre);
        if (!matcher.find()) {
          throw new IllegalArgumentException("Expected " + jre + " to start with an integer");
        }
        JRE_VERSION = Integer.parseInt(matcher.group(1));
        if (JRE_VERSION <= 8) {
          throw new IllegalArgumentException("Expected " + jre + " to start with an integer greater than 8");
        }
      }
    }

    /** On JRE 11+, returns `1.9`. On earlier JREs, returns `1.7`. */
    public static String defaultVersion() {
      return JRE_VERSION >= 11 ? LATEST_VERSION_JRE_11 : LATEST_VERSION_JRE_8;
    }

    private static final String LATEST_VERSION_JRE_8 = "1.7";
    private static final String LATEST_VERSION_JRE_11 = "1.9";

    public static String defaultStyle() {
      return DEFAULT_STYLE;
    }

    static final class State implements Serializable {
      private static final long serialVersionUID = 1L;

      /** The jar that contains the eclipse formatter. */
      //final JarState jarState;
      final String stepName;
      final String version;
      final String style;

      State(String stepName, String version, Provisioner provisioner) throws IOException {
        this(stepName, version, DEFAULT_STYLE, provisioner);
      }

      State(String stepName, String version, String style, Provisioner provisioner) throws IOException {
        //this.jarState = JarState.from(MAVEN_COORDINATE + version, provisioner);
        this.stepName = stepName;
        this.version = version;
        this.style = style;
      }

      @SuppressWarnings({"unchecked", "rawtypes"})
      FormatterFunc createFormat() throws Exception {
        ClassLoader classLoader = getClass().getClassLoader();

        // instantiate the formatter and get its format method
        //Class<?> optionsClass = classLoader.loadClass(OPTIONS_CLASS);
        Class<?> optionsClass = JavaFormatterOptions.class;
        //Class<?> optionsBuilderClass = classLoader.loadClass(OPTIONS_BUILDER_CLASS);
        Class<?> optionsBuilderClass = JavaFormatterOptions.Builder.class;
        Method optionsBuilderMethod = optionsClass.getMethod(OPTIONS_BUILDER_METHOD);
        Object optionsBuilder = optionsBuilderMethod.invoke(null);

        //Class<?> optionsStyleClass = classLoader.loadClass(OPTIONS_Style);
        Class<?> optionsStyleClass = JavaFormatterOptions.Style.class;
        Object styleConstant = Enum.valueOf((Class<Enum>) optionsStyleClass, style);
        Method optionsBuilderStyleMethod = optionsBuilderClass.getMethod(OPTIONS_BUILDER_STYLE_METHOD, optionsStyleClass);
        optionsBuilderStyleMethod.invoke(optionsBuilder, styleConstant);

        Method optionsBuilderBuildMethod = optionsBuilderClass.getMethod(OPTIONS_BUILDER_BUILD_METHOD);
        Object options = optionsBuilderBuildMethod.invoke(optionsBuilder);

        Class<?> formatterClazz = classLoader.loadClass(FORMATTER_CLASS);
        Object formatter = formatterClazz.getConstructor(optionsClass).newInstance(options);
        Method formatterMethod = formatterClazz.getMethod(FORMATTER_METHOD, String.class);

        ThrowingEx.Function<String, String> removeUnused = constructRemoveUnusedFunction(classLoader);

        //Class<?> importOrdererClass = classLoader.loadClass(IMPORT_ORDERER_CLASS);
        Class<?> importOrdererClass = ImportOrderer.class;
        Method importOrdererMethod = importOrdererClass.getMethod(IMPORT_ORDERER_METHOD, String.class);

        return suggestJre11(input -> {
          String formatted = (String) formatterMethod.invoke(formatter, input);
          String removedUnused = removeUnused.apply(formatted);
          String sortedImports = (String) importOrdererMethod.invoke(null, removedUnused);
          return fixWindowsBug(sortedImports, version);
        });
      }

      FormatterFunc createRemoveUnusedImportsOnly() throws Exception {
        ClassLoader classLoader = getClass().getClassLoader();
        ThrowingEx.Function<String, String> removeUnused = constructRemoveUnusedFunction(classLoader);
        return suggestJre11(input -> fixWindowsBug(removeUnused.apply(input), version));
      }

      private static ThrowingEx.Function<String, String> constructRemoveUnusedFunction(ClassLoader classLoader)
          throws NoSuchMethodException, ClassNotFoundException {
        //Class<?> removeUnusedClass = classLoader.loadClass(REMOVE_UNUSED_CLASS);
        Class<?> removeUnusedClass = RemoveUnusedImports.class;
        Class<?> removeJavadocOnlyClass = null;
        /*
        try {
          // google-java-format 1.7 or lower
          removeJavadocOnlyClass = classLoader.loadClass(REMOVE_UNUSED_IMPORT_JavadocOnlyImports);
        } catch (ClassNotFoundException e) {
          // google-java-format 1.8+
          removeJavadocOnlyClass = null;
        }
         */

        ThrowingEx.Function<String, String> removeUnused;
        if (removeJavadocOnlyClass != null) {
          @SuppressWarnings({"unchecked", "rawtypes"})
          Object removeJavadocConstant = Enum.valueOf((Class<Enum>) removeJavadocOnlyClass, REMOVE_UNUSED_IMPORT_JavadocOnlyImports_Keep);
          Method removeUnusedMethod = removeUnusedClass.getMethod(REMOVE_UNUSED_METHOD, String.class, removeJavadocOnlyClass);
          removeUnused = (x) -> (String) removeUnusedMethod.invoke(null, x, removeJavadocConstant);
        } else {
          Method removeUnusedMethod = removeUnusedClass.getMethod(REMOVE_UNUSED_METHOD, String.class);
          removeUnused = (x) -> (String) removeUnusedMethod.invoke(null, x);
        }
        return removeUnused;
      }
    }

    private static final boolean IS_WINDOWS = LineEnding.PLATFORM_NATIVE.str().equals("\r\n");

    /**
     * google-java-format-1.1's removeUnusedImports does *wacky* stuff on Windows.
     * The beauty of normalizing all line endings to unix!
     */
    static String fixWindowsBug(String input, String version) {
      if (IS_WINDOWS && version.equals("1.1")) {
        int firstImport = input.indexOf("\nimport ");
        if (firstImport == 0) {
          return input;
        } else if (firstImport > 0) {
          int numToTrim = 0;
          char prevChar;
          do {
            ++numToTrim;
            prevChar = input.charAt(firstImport - numToTrim);
          } while (Character.isWhitespace(prevChar) && (firstImport - numToTrim) > 0);
          if (firstImport - numToTrim == 0) {
            // import was the very first line, and we'd like to maintain a one-line gap
            ++numToTrim;
          } else if (prevChar == ';' || prevChar == '/') {
            // import came after either license or a package declaration
            --numToTrim;
          }
          if (numToTrim > 0) {
            return input.substring(0, firstImport - numToTrim + 2) + input.substring(firstImport + 1);
          }
        }
      }
      return input;
    }

    private static FormatterFunc suggestJre11(FormatterFunc in) {
      if (JRE_VERSION >= 11) {
        return in;
      } else {
        return unixIn -> {
          try {
            return in.apply(unixIn);
          } catch (Exception e) {
            throw new Exception("You are running Spotless on JRE " + JRE_VERSION + ", which limits you to google-java-format " + LATEST_VERSION_JRE_8 + "\n"
                + "If you upgrade your build JVM to 11+, then you can use google-java-format " + LATEST_VERSION_JRE_11 + ", which may have fixed this problem.", e);
          }
        };
      }
    }
  }

  /** Uses google-java-format, but only to remove unused imports. */
  public static class RemoveUnusedImportsStep {
    // prevent direct instantiation
    private RemoveUnusedImportsStep() {}

    static final String NAME = "removeUnusedImports";

    public static FormatterStep create(Provisioner provisioner) {
      Objects.requireNonNull(provisioner, "provisioner");
      return FormatterStep.createLazy(NAME,
          () -> new GoogleJavaFormatStep.State(NAME, com.diffplug.spotless.java.GoogleJavaFormatStep.defaultVersion(), provisioner),
          GoogleJavaFormatStep.State::createRemoveUnusedImportsOnly);
    }
  }
}
