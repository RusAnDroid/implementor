package implementor;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.*;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

/**
 * Provides an implementation of the {@link JarImpler} interface.
 * This class can generate an implementation for a given class or interface and
 * write it into <var>.jar</var> file
 */
public class Implementor implements JarImpler {

    /**
     * The system line separator.
     * Equals {@link System#lineSeparator()}
     */
    private static final String LINE_SEPARATOR = System.lineSeparator();

    /**
     * String representing a single tabulation.
     * Equals 4 spaces
     */
    private final String TABULATION = " ".repeat(4);

    /**
     * Array of constructors for the implemented class.
     * Written to in {@link Implementor#checkArgs(Class, Path)}
     * Used in {@link Implementor#writeConstructors(Writer)}
     * 
     * @see Constructor
     */
    private Constructor<?>[] constructors;

    /**
     * The main method of the Implementor class,
     *  that runs {@link Implementor#implement(Class, Path)} or {@link Implementor#implementJar(Class, Path)}
     * Gets arguments from command line and have different behaviour depending on what it got:
     *      <ul>
     *          <li> If it gets 2 arguments, then it runs {@link Implementor#implement(Class, Path)} on them. </li>
     *          <li> If it gets 3 arguments and the first is '-jar', then it runs
     *               {@link Implementor#implementJar(Class, Path)} on them </li>
     *          <li> If it gets invalid arguments or number of arguments, or catches {@link ImplerException},
     *               then it writes error message to {@link System#out} </li>
     *      </ul>
     *
     * @param args command line arguments
     *
     * @see Implementor#implement(Class, Path)
     * @see Implementor#implementJar(Class, Path)
     */
    public static void main(String[] args) {
        String argumentsExpected = "Please provide the following arguments: " +
                "canonical class name and root path for implementing into .java file" + LINE_SEPARATOR +
                "or '-jar' as first argument and canonical class name and path to .jar file " +
                "for implementing into .jar file";

        if (args == null) {
            System.out.println("Arguments expected. " + argumentsExpected);
            return;
        }

        if (args.length != 2 && args.length != 3) {
            System.out.println("Exactly 2 or 3 arguments expected. Got: " + args.length + " " + argumentsExpected);
            return;
        }

        for (String arg : args) {
            if (arg == null) {
                System.out.println("Unexpected null argument. " + argumentsExpected);
                return;
            }
        }

        if (args.length == 3 && !args[0].equals("-jar")) {
            System.out.println("If provided 3 arguments, first must equal to '-jar");
            return;
        }

        boolean isJar = (args.length == 3);

        JarImpler impler = new Implementor();
        try {
            if (isJar) {
                impler.implementJar(Class.forName(args[1]), Paths.get(args[2]));
            } else {
                impler.implement(Class.forName(args[0]), Paths.get(args[1]));
            }
        } catch (ImplerException e) {
            System.out.println("Error occurred while trying to implement: " + e.getMessage());
        } catch (ClassNotFoundException e) {
            System.out.println("Can't find class: " + e.getMessage());
        }
    }

    /**
     * Generates an implementation for the specified token and writes into a specified <var>.jar</var> file
     * <p>
     *      Generated class' name should be the same as the class name of the type token with <var>Impl</var> suffix
     *      added.
     * </p>
     * 
     * @param token   the class or interface token to be implemented.
     * @param jarFile the path to the <var>.jar</var> file where the implementation will be stored.
     * @throws ImplerException if an error occurs during implementation or <var>.jar</var> creation.
     */
    @Override
    public void implementJar(Class<?> token, Path jarFile) throws ImplerException {
        Path tmpDirectory;
        try {
            tmpDirectory = Files.createTempDirectory(jarFile.getParent(), "tmp");
        } catch (IOException e) {
            throw new ImplerException("Error while creating temp directory: " + e.getMessage(), e);
        }

        try {
            implement(token, tmpDirectory);
            compile(token, tmpDirectory);
            createJar(token, tmpDirectory, jarFile);
        } finally {
            try {
                Files.walkFileTree(tmpDirectory, new Implementor.Cleaner());
            } catch (Exception e) {
                // ignored
            }
        }
    }

    /**
     * File visitor for cleaning temporary files and directories, 
     * that were creating in {@link Implementor#implementJar(Class, Path)}
     *
     * @see SimpleFileVisitor
     */
    private static class Cleaner extends SimpleFileVisitor<Path> {

        /**
         * Constructs a new Cleaner.
         */
        Cleaner() {
            super();
        }

        /**
         * Deletes the visited file.
         *
         * @param file  the path to the file to be visited.
         * @param attrs the file attributes.
         * @return {@link FileVisitResult#CONTINUE}
         * @throws IOException if deletion fails
         *
         * @see SimpleFileVisitor#visitFile(Object, BasicFileAttributes)
         */
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
        }

        /**
         * Deletes the visited directory after all its entries have been visited.
         *
         * @param dir the path to the directory to be visited.
         * @param exc the I/O exception thrown when accessing the directory, or null if none.
         * @return {@link FileVisitResult#CONTINUE}
         * @throws IOException if deletion fails
         *
         * @see SimpleFileVisitor#postVisitDirectory(Object, IOException)
         */
        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
        }
    }

    /**
     * Writes into a <var>.jar</var> file specified by {@param jarFile}
     * the implementation of {@param token} class, that is stored in {@param tmpDirectory} directory.
     *
     * @param token       token of the class or interface for which the implementation was generated.
     * @param tmpDirectory the temporary directory containing the generated class files.
     * @param jarFile     the path to the <var>.jar</var> file where the implementation will be stored.
     * @throws ImplerException if an error occurs while creating the <var>.jar</var> file.
     */
    private void createJar(Class<?> token, Path tmpDirectory, Path jarFile) throws ImplerException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.IMPLEMENTATION_VENDOR, "Andrey Arkhangelsky");

        Path filePath = getFilePath(token, tmpDirectory, ".class");
        String classname = token.getPackageName().replace('.', '/')
                +  "/" + getClassName(token) + ".class";

        try (JarOutputStream writer = new JarOutputStream(Files.newOutputStream(jarFile), manifest)) {
            writer.putNextEntry(new JarEntry(classname));
            Files.copy(filePath, writer);
        } catch (IOException e) {
            throw new ImplerException("Error while writing to jar file: " + e.getMessage(), e);
        }
    }

    /**
     * Get classpath to sources of the implementation of class, represented by {@param token}
     *
     * @param token token the class or interface for which the implementation was generated.
     * @return the classpath of the token as a String.
     * @throws ImplerException if couldn't find source files
     */
    private String getClassPath(Class<?> token) throws ImplerException {
        try {
            return Path.of(token.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (final URISyntaxException e) {
            throw new ImplerException("Error: can't find source file, that is implemented in "
                    + getClassName(token) + " for compiling");
        }
    }

    /**
     * Compiles the generated class file.
     *
     * @param token      token of the class or interface for which the implementation was generated.
     * @param tmpDirectory the temporary directory containing the generated class files.
     * @throws ImplerException if an error occurs during compilation.
     */
    private void compile(Class<?> token, Path tmpDirectory) throws ImplerException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new ImplerException("Error while compiling generated class: compiler not found");
        }

        String filePath = getFilePath(token, tmpDirectory, ".java").toString();
        String classpath = tmpDirectory.toString() + File.pathSeparator + getClassPath(token);
        int compileResult = compiler.run(null, null, null, "-cp", classpath, filePath);

        if (compileResult != 0) {
            throw new ImplerException("Error while compiling generated class: compiler returned " + compileResult);
        }
    }

    /**
     * Retrieves the file path for the specified token and suffix in the given directory.
     *
     * @param token  token of the class or interface for which the implementation is being generated.
     * @param dir    path to the root directory, where implementation should be located
     * @param suffix the extention of the implementation (".java" when need source code and ".class" for binary)
     * @return the file path.
     */
    private Path getFilePath(Class<?> token, Path dir, String suffix) {
        return getPackagePath(token, dir).resolve(getClassName(token) + suffix);
    }

    /**
     * Wrapper for BufferedWriter that writes Unicode characters.
     *
     * @see BufferedWriter
     */
    private static class UnicodeWriterWrapper extends BufferedWriter {

        /**
         * Constructs a new UnicodeWriterWrapper.
         *
         * @param out the underlying writer.
         */
        public UnicodeWriterWrapper(Writer out) {
            super(out);
        }

        /**
         * Converts string's characters to Unicode
         *
         * @param str string that should be converted
         * @return string, that's converted into Unicode
         */
        private String toUnicode(String str) {
            return str.chars()
                    .mapToObj(
                            ch -> ch < 128
                                    ? String.valueOf((char) ch)
                                    : String.format("\\u%04X", ch))
                    .collect(Collectors.joining());
        }


        /**
         * Converts the string to Unicode representation and writes it.
         *
         * @param str the string to be written.
         * @throws IOException if an I/O error occurs.
         *
         * @see BufferedWriter#write(String)
         */
        @Override
        public void write(String str) throws IOException {
            super.write(toUnicode(str));
        }
    }

    /**
     * Implements the specified token and writes the implementation to the given directory.
     * <p>
     *   Generated class' name should be the same as the class name of the type token with {@code Impl} suffix
     *   added. Generated source code should be placed in the correct subdirectory of the specified
     *   {@code root} directory and have correct file name. For example, the implementation of the
     *   interface {@link java.util.List} should go to {@code $root/java/util/ListImpl.java}
     * </p>
     *
     * @param token the class or interface token to be implemented.
     * @param root  the root directory where the implementation should be stored.
     * @throws ImplerException if an error occurs during implementation.
     */
    @Override
    public void implement(Class<?> token, Path root) throws ImplerException {
        checkArgs(token, root);

        Path pathToPackage = getPackagePath(token, root);
        try {
            Files.createDirectories(pathToPackage);
        } catch (IOException e) {
            // ignored
        }
        Path pathToFile = pathToPackage.resolve(getClassName(token) + ".java");

        try (BufferedWriter writer = new UnicodeWriterWrapper(Files.newBufferedWriter(pathToFile))) {
            writer.write(getClassHeader(token));

            if (!token.isInterface()) {
                writeConstructors(writer);
            }

            writeMethods(token, writer);

            writer.write("}" + LINE_SEPARATOR);
        } catch (IOException e) {
            throw new ImplerException("Error while writing to output file of implementation: " + e.getMessage(), e);
        }
    }

    // Old code lower

    /**
     * Writes the implementation of the constructors of the implemented class.
     * Implemented constructors just call {@code super()} with the same parameters
     *
     * @param writer the writer to write the constructors to.
     * @throws IOException if an I/O error occurs while writing
     */
    private void writeConstructors(Writer writer) throws IOException {
        for (Constructor<?> constructor : this.constructors) {
            writer.write(getExecutable(constructor));
        }
    }

    /**
     * Filters abstract methods from the given array of all methods of the class
     * and wraps them into a set of {@link MethodWrapper} objects.
     *
     * @param methods         the array of methods to filter.
     * @param wrappedMethodsSet the set to collect the wrapped methods into.
     */
    private void putAbstractMethods(Method[] methods, Set<MethodWrapper> wrappedMethodsSet) {
        Arrays.stream(methods)
                .filter(method -> Modifier.isAbstract(method.getModifiers()))
                .map(MethodWrapper::new)
                .collect(Collectors.toCollection(() -> wrappedMethodsSet));
    }

    /**
     * Writes the implementation of methods of the class.
     * <p>
     *      Implemented methods return <var>0</var> (or <var>false</var> for bool) if the return type is a primitive.
     *      And <var>null</var> otherwise
     * </p>
     *
     * @param token  the class or interface token.
     * @param writer the writer to write the methods to.
     * @throws IOException if an I/O error occurs while writing
     */
    private void writeMethods(Class<?> token, Writer writer) throws IOException {
        Set<MethodWrapper> wrappedMethods = new HashSet<>();
        putAbstractMethods(token.getMethods(), wrappedMethods);

        Class<?> current = token;
        while (current != null) {
            putAbstractMethods(current.getDeclaredMethods(), wrappedMethods);
            current = current.getSuperclass();
        }

        for (MethodWrapper wrappedMethod : wrappedMethods) {
            writer.write(getExecutable(wrappedMethod.method()));
        }
    }

    /**
     * Retrieves the string of parameter representation.
     * If {@param typeNeeded} flag is <var>true</var> then parameter type is before it's name in the string
     *
     * @param param      the parameter.
     * @param typeNeeded flag indicating whether the type should be included.
     * @return the parameter string representation.
     */
    private static String getParam(Parameter param, boolean typeNeeded) {
        return (typeNeeded ? param.getType().getCanonicalName() + " " : "") + param.getName();
    }

    /**
     * Retrieves the parameters string representation for the given executable.
     * <p>
     *      Parameters are separated by commas and enclosed in brackets.
     *      If {@param typeNeeded} is true, then there's parameter's type before every parameter
     *      Returns empty brackets if there's no parameters
     * </p>
     *
     * @param exec       the executable.
     * @param typedNeeded flag indicating whether the types should be included.
     * @return the parameters string representation.
     */
    private String getParams(Executable exec, boolean typedNeeded) {
        return Arrays.stream(exec.getParameters())
                .map(param -> getParam(param, typedNeeded))
                .collect(Collectors.joining(", ", "(", ")"));
    }

    /**
     * Retrieves the exception types string representation for the given executable.
     * Returns empty string if there's no exceptions
     *
     * @param exec the executable.
     * @return the exception types string representation.
     */
    private String getExceptions(Executable exec) {
        StringBuilder res = new StringBuilder();
        Class<?>[] exceptions = exec.getExceptionTypes();
        if (exceptions.length > 0) {
            res.append(" throws ");

            res.append(Arrays.stream(exceptions)
                    .map(Class::getCanonicalName)
                    .collect(Collectors.joining(", "))
            );
        }
        return res.toString();
    }

    /**
     * Retrieves the return type and name string representation for the given executable.
     *
     * @param executable the executable.
     * @return the return type and name string representation.
     */
    private String getReturnTypeAndName(Executable executable) {
        StringBuilder sb = new StringBuilder();
        if (executable instanceof Method) {
            sb.append(((Method) executable).getReturnType().getCanonicalName())
                    .append(" ")
                    .append(((Method) executable).getName());
        } else {
            sb.append(getClassName(((Constructor<?>) executable).getDeclaringClass()));
        }

        return sb.toString();
    }

    /**
     * Retrieves the signature string for the given executable.
     * Including modifiers, return type, name, parameters, exceptions,
     * and "{" with {@link Implementor#LINE_SEPARATOR} at the end
     *
     * @param executable the executable.
     * @return the signature string.
     *
     * @see Implementor#getReturnTypeAndName(Executable)
     * @see Implementor#getParams(Executable, boolean)
     * @see Implementor#getExceptions(Executable)
     */
    private String getSignature(Executable executable) {
        StringBuilder sb = new StringBuilder();

        String modifiers = Modifier.toString(
                executable.getModifiers()
                        & ~Modifier.ABSTRACT
                        & ~Modifier.TRANSIENT
                        & ~Modifier.NATIVE
        );

        sb.append(getTabulation(1))
                .append(modifiers)
                .append(!modifiers.isEmpty() ? " " : "")
                .append(getReturnTypeAndName(executable))
                .append(getParams(executable, true))
                .append(getExceptions(executable))
                .append(" {")
                .append(LINE_SEPARATOR);

        return sb.toString();
    }

    /**
     * Retrieves a string of {@link Implementor#TABULATION} repeated {@param count} times
     *
     * @param cnt the count of tabulations.
     * @return the tabulation string.
     */
    private String getTabulation(int cnt) {
        return TABULATION.repeat(Math.max(0, cnt));
    }

    /**
     * Retrieves the string representing implementation of the given executable.
     *
     * @param executable the executable.
     * @return the string representation of the executable.
     *
     * @see Implementor#getSignature(Executable)
     * @see Implementor#getBody(Executable)
     */
    private String getExecutable(Executable executable) {
        return getSignature(executable) +
                getTabulation(2) +
                getBody(executable) +
                getTabulation(1) +
                "}" +
                LINE_SEPARATOR;
    }

    /**
     * Retrieves the default value string representation for the given type of token.
     * Used in {@link Implementor#writeMethods(Class, Writer)} to return default value of the returned type
     *
     * @param token the token of the class or interface that's being implemented.
     * @return the default value string representation.
     */
    private String getDefaultValue(Class<?> token) {
        if (token.equals(boolean.class)) {
            return " false";
        } else if (token.equals(void.class)) {
            return "";
        } else if (token.isPrimitive()) {
            return " 0";
        }
        return " null";
    }

    /**
     * Retrieves the body string representation of the implementation of the given executable.
     * <p>
     *      Calls {@code super()} with the same parameters if the {@param executable} is a constructor
     *      Returns default value of the return type if the {@param executable} is a method
     *      (<var>0</var> or <var>false</var> for primitives and <var>null</var> for non-primitive types)
     * </p>
     *
     * @param executable the executable.
     * @return the body string representation.
     *
     * @see Implementor#getDefaultValue(Class)
     */
    private String getBody(Executable executable) {
        StringBuilder sb = new StringBuilder();
        if (executable instanceof Method) {
            sb.append("return").append(getDefaultValue(((Method) executable).getReturnType()));
        } else {
            sb.append("super").append(getParams(executable, false));
        }
        sb.append(";").append(LINE_SEPARATOR);
        return sb.toString();
    }

    /**
     * Retrieves the class name for the implementation of class or interface.
     * It's the same name as {@code token.getSimpleName()}, but with added "Impl" at the end.
     *
     * @param token the token of the class or interface that's being implemented.
     * @return the classname of the implementation ({@code token.getSimpleName() + "Impl"})
     */
    private String getClassName(Class<?> token) {
        return token.getSimpleName() + "Impl";
    }

    /**
     * Retrieves the path to the directory with the implementation of {@param token} from {@param root} directory
     *
     * @param token the token of the class or interface that's being implemented.
     * @param root  the root directory.
     * @return the path to the directory with the implementation
     */
    private Path getPackagePath(Class<?> token, Path root) {
        if (token.getPackage() == null) {
            return root;
        }
        return root.resolve(token.getPackage().getName().replace('.', File.separatorChar));
    }

    /**
     * Validates the input arguments for {@link Implementor#implement(Class, Path)} method.
     * Validation fails if:
     * <ul>
     *     <li> any of the arguments is <var>null</var> </li>
     *     <li> {@param token} represents Enum, Record, Array or a Primitive type </li>
     *     <li> {@param token} has modifiers {@code final} or {@code private} </li>
     *     <li> {@param token} is class, but doesn't have any non-private constructors </li>
     * </ul>
     * This method also updates {@link Implementor#constructors},
     * putting there all the constructors of the {@param token}
     *
     * @param token the token of the class or interface that's being implemented
     * @param root  the root directory.
     * @throws ImplerException if the arguments are invalid.
     */
    private void checkArgs(Class<?> token, Path root) throws ImplerException {
        if (token == null || root == null) {
            throw new ImplerException("Error: the class token or root is null");
        }

        if (token.isArray() || token.isPrimitive()
                || token == Enum.class || token == Record.class) {
            throw new ImplerException("Error: the class token is not a class or interface");
        }

        if (Modifier.isFinal(token.getModifiers())) {
            throw new ImplerException("Error: can't implement final class");
        }

        if (Modifier.isPrivate(token.getModifiers())) {
            throw new ImplerException("Error: can't implement private class");
        }

        Constructor<?>[] constructors = Arrays.stream(token.getDeclaredConstructors())
                .filter(constructor -> !Modifier.isPrivate(constructor.getModifiers()))
                .toArray(Constructor<?>[]::new);
        if (!token.isInterface() && constructors.length == 0) {
            throw new ImplerException("Error: token is a class and no non-private constructors were found");
        }

        this.constructors = constructors;
    }

    /**
     * Retrieves the first several lines of the implemented class file as a string.
     * Including package name, name of the class and implemented interface name or extended class name,
     * depending on whether {@param token} is an interface or a class
     *
     * @param token the token.
     * @return the first several lines
     */
    private String getClassHeader(Class<?> token) {
        StringBuilder sb = new StringBuilder();

        String packageName = token.getPackage().getName();
        if (!packageName.isEmpty()) {
            sb.append("package ")
                    .append(packageName)
                    .append(";")
                    .append(LINE_SEPARATOR);
        }

        sb.append("public class ")
                .append(getClassName(token))
                .append(" ")
                .append(token.isInterface() ? "implements" : "extends")
                .append(" ")
                .append(token.getCanonicalName())
                .append("{")
                .append(LINE_SEPARATOR);

        return sb.toString();
    }

    /**
     * Wrapper for the Method class to be able to use it in {@link HashSet}
     */
    private record MethodWrapper(Method method) {

        /**
         * Base for the polynomial hashcode
         */
        private final static int BASE = 42;
        /**
         * Mod for the polynomial hashcode
         */
        private final static int MOD = 1000000007;

        /**
         * Checks if this MethodWrapper is equal to the specified object.
         * If the second object is an instance of  {@link MethodWrapper}, then the method checks
         * names, return types and parameter types of the {@link MethodWrapper#method} of the two objects are equal
         *
         * @param obj the object to compare with.
         * @return true if the second object is an instance of {@link MethodWrapper} and they are equal, false otherwise.
         */
        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (obj instanceof MethodWrapper other) {
                return Arrays.equals(method().getParameterTypes(), other.method().getParameterTypes())
                        && method().getReturnType().equals(other.method().getReturnType())
                        && method().getName().equals(other.method().getName());
            }
            return false;
        }

        /**
         * Returns the hash code value for this MethodWrapper.
         * It's a polynomial hashcode with base {@link MethodWrapper#BASE} and module {@link MethodWrapper#MOD}.
         * Hash builds up from the name, the return type and the parameter types of the {@link MethodWrapper#method}
         *
         * @return the hash code value.
         */
        @Override
        public int hashCode() {
            return ((method().getName().hashCode() + (method().getReturnType().hashCode()) * BASE % MOD) % MOD
                    + (Arrays.hashCode(method().getParameterTypes()) * BASE % MOD * BASE % MOD)) % MOD;
        }
    }
}
