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
package org.openrewrite.java.migrate.guava;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesMethod;
import org.openrewrite.java.tree.J;

import java.util.Set;

import static java.util.Collections.singleton;

public class NoGuavaMapsNewHashMap extends Recipe {
    private static final MethodMatcher NEW_HASH_MAP = new MethodMatcher("com.google.common.collect.Maps newHashMap()");
    private static final MethodMatcher NEW_HASH_MAP_WITH_MAP = new MethodMatcher("com.google.common.collect.Maps newHashMap(java.util.Map)");

    @Override
    public String getDisplayName() {
        return "Prefer `new HashMap<>()`";
    }

    @Override
    public String getDescription() {
        return "Prefer the Java standard library over third-party usage of Guava in simple cases like this.";
    }

    @Override
    public Set<String> getTags() {
        return singleton("guava");
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(Preconditions.or(
                new UsesMethod<>(NEW_HASH_MAP),
                new UsesMethod<>(NEW_HASH_MAP_WITH_MAP)), new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                if (NEW_HASH_MAP.matches(method)) {
                    maybeRemoveImport("com.google.common.collect.Maps");
                    maybeAddImport("java.util.HashMap");
                    return JavaTemplate.builder("new HashMap<>()")
                            .contextSensitive()
                            .imports("java.util.HashMap")
                            .build()
                            .apply(getCursor(), method.getCoordinates().replace());
                }
                if (NEW_HASH_MAP_WITH_MAP.matches(method)) {
                    maybeRemoveImport("com.google.common.collect.Maps");
                    maybeAddImport("java.util.HashMap");
                    return JavaTemplate.builder("new HashMap<>(#{any(java.util.Map)})")
                            .contextSensitive()
                            .imports("java.util.HashMap")
                            .build()
                            .apply(getCursor(), method.getCoordinates().replace(), method.getArguments().get(0));
                }
                return super.visitMethodInvocation(method, ctx);
            }
        });
    }
}
