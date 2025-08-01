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
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesMethod;
import org.openrewrite.java.tree.J;

import java.util.Set;

import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;

public class MigrateSecurityManagerMulticast extends Recipe {
    private static final MethodMatcher MULTICAST_METHOD = new MethodMatcher("java.lang.SecurityManager checkMulticast(java.net.InetAddress, byte)");

    @Override
    public String getDisplayName() {
        return "Use `SecurityManager#checkMulticast(InetAddress)`";
    }

    @Override
    public String getDescription() {
        return "Use `SecurityManager#checkMulticast(InetAddress)` instead of the deprecated `SecurityManager#checkMulticast(InetAddress, byte)` in Java 1.4 or higher.";
    }

    @Override
    public Set<String> getTags() {
        return singleton("deprecated");
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new UsesMethod<>(MULTICAST_METHOD), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);

                if (MULTICAST_METHOD.matches(m) && m.getArguments().size() == 2) {
                    return m.withArguments(singletonList(m.getArguments().get(0)))
                            .withMethodType(m.getMethodType()
                                    .withParameterNames(m.getMethodType().getParameterNames().subList(0, 1))
                                    .withParameterTypes(m.getMethodType().getParameterTypes().subList(0, 1))
                            );
                }
                return m;
            }
        });
    }

}
