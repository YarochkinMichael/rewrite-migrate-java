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
package org.openrewrite.java.migrate.lombok;

import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.RemoveAnnotationVisitor;
import org.openrewrite.java.search.UsesJavaVersion;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.Collections.singleton;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

@EqualsAndHashCode(callSuper = false)
@Value
public class LombokValueToRecord extends ScanningRecipe<Map<String, Set<String>>> {

    private static final AnnotationMatcher LOMBOK_VALUE_MATCHER = new AnnotationMatcher("@lombok.Value()");
    private static final AnnotationMatcher LOMBOK_BUILDER_MATCHER = new AnnotationMatcher("@lombok.Builder()");

    @Option(displayName = "Add a `toString()` implementation matching Lombok",
            description = "When set the `toString` format from Lombok is used in the migrated record.",
            required = false)
    @Nullable
    Boolean useExactToString;

    @Override
    public String getDisplayName() {
        return "Convert `@lombok.Value` class to Record";
    }

    @Override
    public String getDescription() {
        return "Convert Lombok `@Value` annotated classes to standard Java Records.";
    }

    @Override
    public Set<String> getTags() {
        return singleton("lombok");
    }

    @Override
    public Map<String, Set<String>> getInitialValue(ExecutionContext ctx) {
        return new ConcurrentHashMap<>();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Map<String, Set<String>> acc) {
        TreeVisitor<?, ExecutionContext> check = Preconditions.and(
                new UsesJavaVersion<>(17),
                new UsesType<>("lombok.Value", false)
        );
        return Preconditions.check(check, new ScannerVisitor(acc));
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Map<String, Set<String>> recordTypesToMembers) {
        return new LombokValueToRecord.LombokValueToRecordVisitor(useExactToString, recordTypesToMembers);
    }


    @RequiredArgsConstructor
    private static class ScannerVisitor extends JavaIsoVisitor<ExecutionContext> {
        private final Map<String, Set<String>> acc;

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
            if (!isRelevantClass(cd)) {
                return cd;
            }

            List<J.VariableDeclarations> memberVariables = findAllClassFields(cd).collect(toList());
            if (hasMemberVariableAssignments(memberVariables)) {
                return cd;
            }

            assert cd.getType() != null : "Class type must not be null"; // Checked in isRelevantClass
            Set<String> memberVariableNames = getMemberVariableNames(memberVariables);
            if (implementsConflictingInterfaces(cd, memberVariableNames)) {
                return cd;
            }

            acc.putIfAbsent(
                    cd.getType().getFullyQualifiedName(),
                    memberVariableNames);

            return cd;
        }

        private boolean isRelevantClass(J.ClassDeclaration classDeclaration) {
            List<J.Annotation> allAnnotations = classDeclaration.getAllAnnotations();
            return classDeclaration.getType() != null &&
                   J.ClassDeclaration.Kind.Type.Record != classDeclaration.getKind() &&
                   hasMatchingAnnotations(classDeclaration) &&
                   !hasGenericTypeParameter(classDeclaration) &&
                   classDeclaration.getBody().getStatements().stream().allMatch(this::isRecordCompatibleField) &&
                   !hasIncompatibleModifier(classDeclaration);
        }

        private static Predicate<J.Annotation> matchAnnotationWithNoArguments(AnnotationMatcher matcher) {
            return ann -> matcher.matches(ann) && (ann.getArguments() == null || ann.getArguments().isEmpty());
        }

        private static boolean hasMatchingAnnotations(J.ClassDeclaration classDeclaration) {
            List<J.Annotation> allAnnotations = classDeclaration.getAllAnnotations();
            if (allAnnotations.stream().anyMatch(matchAnnotationWithNoArguments(LOMBOK_VALUE_MATCHER))) {
                // Tolerate a limited set of other annotations like Builder, that work well with records too
                return allAnnotations.stream().allMatch(
                        matchAnnotationWithNoArguments(LOMBOK_VALUE_MATCHER)
                                // compatible annotations can be added here
                                .or(matchAnnotationWithNoArguments(LOMBOK_BUILDER_MATCHER))
                );
            }
            return false;
        }

        /**
         * If the class target class implements an interface, transforming it to a record will not work in general,
         * because the record access methods do not have the "get" prefix.
         *
         * @param classDeclaration
         * @return true if the class implements an interface with a getter method based on a member variable
         */
        private boolean implementsConflictingInterfaces(J.ClassDeclaration classDeclaration, Set<String> memberVariableNames) {
            List<TypeTree> classDeclarationImplements = classDeclaration.getImplements();
            if (classDeclarationImplements == null) {
                return false;
            }
            return classDeclarationImplements.stream().anyMatch(implemented -> {
                JavaType type = implemented.getType();
                if (type instanceof JavaType.FullyQualified) {
                    return isConflictingInterface((JavaType.FullyQualified) type, memberVariableNames);
                }
                return false;
            });
        }

        private static boolean isConflictingInterface(JavaType.FullyQualified implemented, Set<String> memberVariableNames) {
            boolean hasConflictingMethod = implemented.getMethods().stream()
                    .map(JavaType.Method::getName)
                    .map(LombokValueToRecordVisitor::getterMethodNameToFluentMethodName)
                    .anyMatch(memberVariableNames::contains);
            if (hasConflictingMethod) {
                return true;
            }
            List<JavaType.FullyQualified> superInterfaces = implemented.getInterfaces();
            if (superInterfaces != null) {
                return superInterfaces.stream().anyMatch(i -> isConflictingInterface(i, memberVariableNames));
            }
            return false;
        }

        private boolean hasGenericTypeParameter(J.ClassDeclaration classDeclaration) {
            List<J.TypeParameter> typeParameters = classDeclaration.getTypeParameters();
            return typeParameters != null && !typeParameters.isEmpty();
        }

        private boolean hasIncompatibleModifier(J.ClassDeclaration classDeclaration) {
            // Inner classes need to be static
            if (getCursor().getParent() != null) {
                Object parentValue = getCursor().getParent().getValue();
                if (parentValue instanceof J.ClassDeclaration || (parentValue instanceof JRightPadded && ((JRightPadded) parentValue).getElement() instanceof J.ClassDeclaration)) {
                    if (classDeclaration.getModifiers().stream().noneMatch(mod -> mod.getType() == J.Modifier.Type.Static)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean isRecordCompatibleField(Statement statement) {
            if (!(statement instanceof J.VariableDeclarations)) {
                return false;
            }
            J.VariableDeclarations variableDeclarations = (J.VariableDeclarations) statement;
            if (variableDeclarations.getModifiers().stream().anyMatch(modifier -> modifier.getType() == J.Modifier.Type.Static)) {
                return false;
            }
            if (!variableDeclarations.getAllAnnotations().isEmpty()) {
                return false;
            }
            return true;
        }

        private boolean hasMemberVariableAssignments(List<J.VariableDeclarations> memberVariables) {
            return memberVariables
                    .stream()
                    .map(J.VariableDeclarations::getVariables)
                    .flatMap(List::stream)
                    .map(J.VariableDeclarations.NamedVariable::getInitializer)
                    .anyMatch(Objects::nonNull);
        }

    }

    private static class LombokValueToRecordVisitor extends JavaIsoVisitor<ExecutionContext> {
        private static final JavaTemplate TO_STRING_TEMPLATE = JavaTemplate
                .builder("@Override public String toString() { return \"#{}(\" +\n#{}\n\")\"; }")
                .contextSensitive()
                .build();

        private static final String TO_STRING_MEMBER_LINE_PATTERN = "\"%s=\" + %s +";
        private static final String TO_STRING_MEMBER_DELIMITER = "\", \" +\n";
        private static final String STANDARD_GETTER_PREFIX = "get";

        private final @Nullable Boolean useExactToString;
        private final Map<String, Set<String>> recordTypeToMembers;

        public LombokValueToRecordVisitor(@Nullable Boolean useExactToString, Map<String, Set<String>> recordTypeToMembers) {
            this.useExactToString = useExactToString;
            this.recordTypeToMembers = recordTypeToMembers;
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation methodInvocation = super.visitMethodInvocation(method, ctx);

            if (!isMethodInvocationOnRecordTypeClassMember(methodInvocation)) {
                return methodInvocation;
            }

            J.Identifier methodName = methodInvocation.getName();
            return methodInvocation
                    .withName(methodName
                            .withSimpleName(getterMethodNameToFluentMethodName(methodName.getSimpleName()))
                    );
        }

        @Override
        public J.MemberReference visitMemberReference(J.MemberReference memberRef, ExecutionContext ctx) {
            J.MemberReference memberReference = super.visitMemberReference(memberRef, ctx);

            Expression containing = memberReference.getContaining();
            if (containing.getType() instanceof JavaType.Class) {
                String classFqn = ((JavaType.Class) containing.getType()).getFullyQualifiedName();
                J.Identifier reference = memberReference.getReference();
                String methodName = reference.getSimpleName();
                String newSimpleName = getterMethodNameToFluentMethodName(methodName);
                if (recordTypeToMembers.containsKey(classFqn) &&
                    methodName.startsWith(STANDARD_GETTER_PREFIX) &&
                    recordTypeToMembers.get(classFqn).contains(newSimpleName)) {

                    JavaType.Method methodType = memberReference.getMethodType();
                    if (methodType != null) {
                        methodType = methodType.withName(newSimpleName);
                    }
                    return memberReference
                        .withReference(reference.withSimpleName(newSimpleName))
                        .withMethodType(methodType);
                }
            }
            return memberReference;
        }

        private boolean isMethodInvocationOnRecordTypeClassMember(J.MethodInvocation methodInvocation) {
            Expression expression = methodInvocation.getSelect();
            if (!isClassExpression(expression)) {
                return false;
            }

            JavaType.Class classType = (JavaType.Class) expression.getType();
            if (classType == null) {
                return false;
            }

            String methodName = methodInvocation.getName().getSimpleName();
            String classFqn = classType.getFullyQualifiedName();

            return recordTypeToMembers.containsKey(classFqn) &&
                   methodName.startsWith(STANDARD_GETTER_PREFIX) &&
                   recordTypeToMembers.get(classFqn).contains(getterMethodNameToFluentMethodName(methodName));
        }

        private static boolean isClassExpression(@Nullable Expression expression) {
            return expression != null && (expression.getType() instanceof JavaType.Class);
        }

        private static String getterMethodNameToFluentMethodName(String methodName) {
            StringBuilder fluentMethodName = new StringBuilder(
                    methodName.replace(STANDARD_GETTER_PREFIX, ""));

            if (fluentMethodName.length() == 0) {
                return "";
            }

            char firstMemberChar = fluentMethodName.charAt(0);
            fluentMethodName.setCharAt(0, Character.toLowerCase(firstMemberChar));

            return fluentMethodName.toString();
        }

        private static List<Statement> mapToConstructorArguments(List<J.VariableDeclarations> memberVariables) {
            return memberVariables
                    .stream()
                    .map(it -> it
                            .withModifiers(emptyList())
                            .withVariables(it.getVariables())
                    )
                    .map(Statement.class::cast)
                    .collect(toList());
        }

        private J.ClassDeclaration addExactToStringMethod(J.ClassDeclaration classDeclaration,
                                                          List<J.VariableDeclarations> memberVariables) {
            return classDeclaration.withBody(TO_STRING_TEMPLATE
                    .apply(new Cursor(getCursor(), classDeclaration.getBody()),
                            classDeclaration.getBody().getCoordinates().lastStatement(),
                            classDeclaration.getSimpleName(),
                            memberVariablesToString(getMemberVariableNames(memberVariables))));
        }

        private static String memberVariablesToString(Set<String> memberVariables) {
            return memberVariables
                    .stream()
                    .map(member -> String.format(TO_STRING_MEMBER_LINE_PATTERN, member, member))
                    .collect(joining(TO_STRING_MEMBER_DELIMITER));
        }

        private static JavaType.Class buildRecordType(J.ClassDeclaration classDeclaration) {
            assert classDeclaration.getType() != null : "Class type must not be null";
            String className = classDeclaration.getType().getFullyQualifiedName();

            return JavaType.ShallowClass.build(className)
                    .withKind(JavaType.FullyQualified.Kind.Record);
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd, ExecutionContext ctx) {
            J.ClassDeclaration classDeclaration = super.visitClassDeclaration(cd, ctx);
            JavaType.FullyQualified classType = classDeclaration.getType();

            if (classType == null || !recordTypeToMembers.containsKey(classType.getFullyQualifiedName())) {
                return classDeclaration;
            }

            List<J.VariableDeclarations> memberVariables = findAllClassFields(classDeclaration)
                    .collect(toList());

            List<Statement> bodyStatements = new ArrayList<>(classDeclaration.getBody().getStatements());
            bodyStatements.removeAll(memberVariables);

            classDeclaration = new RemoveAnnotationVisitor(LOMBOK_VALUE_MATCHER).visitClassDeclaration(classDeclaration, ctx);
            maybeRemoveImport("lombok.Value");

            classDeclaration = classDeclaration
                    .withKind(J.ClassDeclaration.Kind.Type.Record)
                    .withModifiers(ListUtils.map(classDeclaration.getModifiers(), modifier -> {
                        J.Modifier.Type type = modifier.getType();
                        if (type == J.Modifier.Type.Static || type == J.Modifier.Type.Final) {
                            return null;
                        }
                        return modifier;
                    }))
                    .withType(buildRecordType(classDeclaration))
                    .withBody(classDeclaration.getBody()
                            .withStatements(bodyStatements)
                    )
                    .withPrimaryConstructor(mapToConstructorArguments(memberVariables));

            if (useExactToString != null && useExactToString) {
                classDeclaration = addExactToStringMethod(classDeclaration, memberVariables);
            }

            return maybeAutoFormat(cd, classDeclaration, ctx);
        }
    }

    private static Stream<J.VariableDeclarations> findAllClassFields(J.ClassDeclaration cd) {
        return cd.getBody().getStatements()
                .stream()
                .filter(J.VariableDeclarations.class::isInstance)
                .map(J.VariableDeclarations.class::cast);
    }

    private static Set<String> getMemberVariableNames(List<J.VariableDeclarations> memberVariables) {
        return memberVariables
                .stream()
                .map(J.VariableDeclarations::getVariables)
                .flatMap(List::stream)
                .map(J.VariableDeclarations.NamedVariable::getSimpleName)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
    }
}
