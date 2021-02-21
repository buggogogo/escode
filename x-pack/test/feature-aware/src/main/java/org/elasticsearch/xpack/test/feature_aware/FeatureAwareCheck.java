/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.test.feature_aware;

import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.persistent.PersistentTaskParams;
import org.elasticsearch.xpack.core.XPackPlugin;
import org.objectweb.asm.ClassReader;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Used in the featureAwareCheck to check for classes in X-Pack that implement customs but do not extend the appropriate marker interface.
 */
/**
 *  A NOTE ABOUT APPEND ONLY OPTIMIZATIONS:
 * if we have an autoGeneratedID that comes into the engine we can potentially optimize
 * and just use addDocument instead of updateDocument and skip the entire version and index lookupVersion across the board.
 * Yet, we have to deal with multiple document delivery, for this we use a property of the document that is added
 * to detect if it has potentially been added before. We use the documents timestamp for this since it's something
 * that:
 *  - doesn't change per document
 *  - is preserved in the transaction log
 *  - and is assigned before we start to index / replicate
 * NOTE: it's not important for this timestamp to be consistent across nodes etc. it's just a number that is in the common
 * case increasing and can be used in the failure case when we retry and resent documents to establish a happens before
 * relationship. For instance:
 *  - doc A has autoGeneratedIdTimestamp = 10, isRetry = false
 *  - doc B has autoGeneratedIdTimestamp = 9, isRetry = false
 *
 *  while both docs are in in flight, we disconnect on one node, reconnect and send doc A again
 *  - now doc A' has autoGeneratedIdTimestamp = 10, isRetry = true
 *
 *  if A' arrives on the shard first we update maxUnsafeAutoIdTimestamp to 10 and use update document. All subsequent
 *  documents that arrive (A and B) will also use updateDocument since their timestamps are less than
 *  maxUnsafeAutoIdTimestamp. While this is not strictly needed for doc B it is just much simpler to implement since it
 *  will just de-optimize some doc in the worst case.
 *
 *  if A arrives on the shard first we use addDocument since maxUnsafeAutoIdTimestamp is < 10. A` will then just be skipped
 *  or calls updateDocument.
 */
public final class FeatureAwareCheck {

    /**
     * Check the class directories specified by the arguments for classes in X-Pack that implement customs but do not extend the appropriate
     * marker interface that provides a mix-in implementation of {@link ClusterState.FeatureAware#getRequiredFeature()}.
     *
     * @param args the class directories to check
     * @throws IOException if an I/O exception is walking the class directories
     */
    public static void main(final String[] args) throws IOException {
        systemOutPrintln("checking for custom violations");
        final List<FeatureAwareViolation> violations = new ArrayList<>();
        checkDirectories(violations::add, args);
        if (violations.isEmpty()) {
            systemOutPrintln("no custom violations found");
        } else {
            violations.forEach(violation ->
                    systemOutPrintln(
                            "class [" + violation.name + "] implements"
                                    + " [" + violation.interfaceName + " but does not implement"
                                    + " [" + violation.expectedInterfaceName + "]")
            );
            throw new IllegalStateException(
                    "found custom" + (violations.size() == 1 ? "" : "s") + " in X-Pack not extending appropriate X-Pack mix-in");
        }
    }

    @SuppressForbidden(reason = "System.out#println")
    private static void systemOutPrintln(final String s) {
        System.out.println(s);
    }

    private static void checkDirectories(
            final Consumer<FeatureAwareViolation> callback,
            final String... classDirectories) throws IOException {
        for (final String classDirectory : classDirectories) {
            final Path root = pathsGet(classDirectory);
            if (Files.isDirectory(root)) {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                        if (Files.isRegularFile(file) && file.getFileName().toString().endsWith(".class")) {
                            try (InputStream in = Files.newInputStream(file)) {
                                checkClass(in, callback);
                            }
                        }
                        return super.visitFile(file, attrs);
                    }
                });
            } else {
                throw new FileNotFoundException("class directory [" + classDirectory + "] should exist");
            }
        }
    }

    @SuppressForbidden(reason = "Paths#get")
    private static Path pathsGet(final String pathString) {
        return Paths.get(pathString);
    }

    /**
     * Represents a feature-aware violation.
     */
    static class FeatureAwareViolation {

        final String name;
        final String interfaceName;
        final String expectedInterfaceName;

        /**
         * Constructs a representation of a feature-aware violation.
         *
         * @param name                  the name of the custom class
         * @param interfaceName         the name of the feature-aware interface
         * @param expectedInterfaceName the name of the expected mix-in class
         */
        FeatureAwareViolation(final String name, final String interfaceName, final String expectedInterfaceName) {
            this.name = name;
            this.interfaceName = interfaceName;
            this.expectedInterfaceName = expectedInterfaceName;
        }

    }

    /**
     * Loads a class from the specified input stream and checks that if it implements a feature-aware custom then it extends the appropriate
     * mix-in interface from X-Pack. If the class does not, then the specified callback is invoked.
     *
     * @param in       the input stream
     * @param callback the callback to invoke
     * @throws IOException if an I/O exception occurs loading the class hierarchy
     */
    static void checkClass(final InputStream in, final Consumer<FeatureAwareViolation> callback) throws IOException {
        // the class format only reports declared interfaces so we have to walk the hierarchy looking for all interfaces
        final List<String> interfaces = new ArrayList<>();
        ClassReader cr = new ClassReader(in);
        final String name = cr.getClassName();
        do {
            interfaces.addAll(Arrays.asList(cr.getInterfaces()));
            final String superName = cr.getSuperName();
            if ("java/lang/Object".equals(superName)) {
                break;
            }
            cr = new ClassReader(superName);
        } while (true);
        checkClass(name, interfaces, callback);
    }

    private static void checkClass(
            final String name,
            final List<String> interfaces,
            final Consumer<FeatureAwareViolation> callback) {
        checkCustomForClass(ClusterState.Custom.class, XPackPlugin.XPackClusterStateCustom.class, name, interfaces, callback);
        checkCustomForClass(MetaData.Custom.class, XPackPlugin.XPackMetaDataCustom.class, name, interfaces, callback);
        checkCustomForClass(PersistentTaskParams.class, XPackPlugin.XPackPersistentTaskParams.class, name, interfaces, callback);
    }

    private static void checkCustomForClass(
            final Class<? extends ClusterState.FeatureAware> interfaceToCheck,
            final Class<? extends ClusterState.FeatureAware> expectedInterface,
            final String name,
            final List<String> interfaces,
            final Consumer<FeatureAwareViolation> callback) {
        final Set<String> interfaceSet = new TreeSet<>(interfaces);
        final String interfaceToCheckName = formatClassName(interfaceToCheck);
        final String expectedXPackInterfaceName = formatClassName(expectedInterface);
        if (interfaceSet.contains(interfaceToCheckName)
                && name.equals(expectedXPackInterfaceName) == false
                && interfaceSet.contains(expectedXPackInterfaceName) == false) {
            assert name.startsWith("org/elasticsearch/license") || name.startsWith("org/elasticsearch/xpack");
            callback.accept(new FeatureAwareViolation(name, interfaceToCheckName, expectedXPackInterfaceName));
        }
    }

    /**
     * Format the specified class to a name in the ASM format replacing all dots in the class name with forward-slashes.
     *
     * @param clazz the class whose name to format
     * @return the formatted class name
     */
    static String formatClassName(final Class<?> clazz) {
        return clazz.getName().replace(".", "/");
    }

}
