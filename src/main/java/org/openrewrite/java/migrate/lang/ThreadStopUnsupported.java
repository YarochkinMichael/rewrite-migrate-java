/*
 * Copyright 2024 the original author or authors.
 * <p>
 * Licensed under the Moderne Source Available License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://docs.moderne.io/licensing/moderne-source-available-license
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.migrate.lang;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesJavaVersion;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.marker.Markers;

import static java.util.Collections.singletonList;

public class ThreadStopUnsupported extends Recipe {
    private static final MethodMatcher THREAD_STOP_MATCHER = new MethodMatcher("java.lang.Thread stop()");
    private static final MethodMatcher THREAD_RESUME_MATCHER = new MethodMatcher("java.lang.Thread resume()");
    private static final MethodMatcher THREAD_SUSPEND_MATCHER = new MethodMatcher("java.lang.Thread suspend()");

    @Override
    public String getDisplayName() {
        return "Replace `Thread.resume()`, `Thread.stop()`, and `Thread.suspend()` with `throw new UnsupportedOperationException()`";
    }

    @Override
    public String getDescription() {
        return "`Thread.resume()`, `Thread.stop()`, and `Thread.suspend()` always throws a `new UnsupportedOperationException` in Java 21+. " +
                "This recipe makes that explicit, as the migration is more complicated. " +
                "See https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/doc-files/threadPrimitiveDeprecation.html .";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J j = super.visitMethodInvocation(method, ctx);
                if (THREAD_STOP_MATCHER.matches(method) || THREAD_RESUME_MATCHER.matches(method) || THREAD_SUSPEND_MATCHER.matches(method)) {
                    if (usesJava21(ctx)) {
                        j = JavaTemplate.apply("throw new UnsupportedOperationException()", getCursor(), method.getCoordinates().replace());
                    }
                    if (j.getComments().isEmpty()) {
                        j = getWithComment(j, method.getName().getSimpleName());
                    }
                }
                return j;
            }

            private boolean usesJava21(ExecutionContext ctx) {
                JavaSourceFile javaSourceFile = getCursor().firstEnclosing(JavaSourceFile.class);
                return javaSourceFile != null && new UsesJavaVersion<>(21).visit(javaSourceFile, ctx) != javaSourceFile;
            }

            private J getWithComment(J j, String methodName) {
                String prefixWhitespace = j.getPrefix().getWhitespace();
                String commentText =
                        prefixWhitespace + " * `Thread." + methodName + "()` always throws a `new UnsupportedOperationException()` in Java 21+." +
                                prefixWhitespace + " * For detailed migration instructions see the migration guide available at" +
                                prefixWhitespace + " * https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/doc-files/threadPrimitiveDeprecation.html" +
                                prefixWhitespace + " ";
                return j.withComments(singletonList(new TextComment(true, commentText, prefixWhitespace, Markers.EMPTY)));
            }
        };
    }
}
