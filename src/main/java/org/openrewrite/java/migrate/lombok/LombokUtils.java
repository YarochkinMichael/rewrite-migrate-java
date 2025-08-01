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

import lombok.AccessLevel;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.service.AnnotationService;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import java.util.List;

import static lombok.AccessLevel.*;
import static org.openrewrite.java.tree.J.Modifier.Type.*;

class LombokUtils {

    private static final AnnotationMatcher OVERRIDE_MATCHER = new AnnotationMatcher("java.lang.Override");

    static boolean isGetter(Cursor cursor, AnnotationService service) {
        if (!(cursor.getValue() instanceof J.MethodDeclaration)) {
            return false;
        }
        J.MethodDeclaration method = cursor.getValue();
        if (method.getMethodType() == null) {
            return false;
        }
        // Check signature: no parameters
        if (!(method.getParameters().get(0) instanceof J.Empty) || method.getReturnTypeExpression() == null) {
            return false;
        }
        // Check body: just a return statement
        if (method.getBody() == null ||
                method.getBody().getStatements().size() != 1 ||
                !(method.getBody().getStatements().get(0) instanceof J.Return)) {
            return false;
        }
        // Check there is no annotation except @Overwrite
        if (hasAnyAnnotatioOtherThanOverride(cursor, service)) {
            return false;
        }
        // Check field is declared on method type
        JavaType.FullyQualified declaringType = method.getMethodType().getDeclaringType();
        Expression returnExpression = ((J.Return) method.getBody().getStatements().get(0)).getExpression();
        if (returnExpression instanceof J.Identifier) {
            J.Identifier identifier = (J.Identifier) returnExpression;
            if (identifier.getFieldType() != null && declaringType == identifier.getFieldType().getOwner()) {
                // Check return: type and matching field name
                return hasMatchingTypeAndGetterName(method, identifier.getType(), identifier.getSimpleName());
            }
        } else if (returnExpression instanceof J.FieldAccess) {
            J.FieldAccess fieldAccess = (J.FieldAccess) returnExpression;
            Expression target = fieldAccess.getTarget();
            if (target instanceof J.Identifier && ((J.Identifier) target).getFieldType() != null &&
                    declaringType == ((J.Identifier) target).getFieldType().getOwner()) {
                // Check return: type and matching field name
                return hasMatchingTypeAndGetterName(method, fieldAccess.getType(), fieldAccess.getSimpleName());
            }
        }
        return false;
    }

    private static boolean hasMatchingTypeAndGetterName(J.MethodDeclaration method, @Nullable JavaType type, String simpleName) {
        if (method.getType() == type) {
            String deriveGetterMethodName = deriveGetterMethodName(type, simpleName);
            return method.getSimpleName().equals(deriveGetterMethodName);
        }
        return false;
    }

    public static boolean isEffectivelyGetter(J.MethodDeclaration method) {
        if (!method.getParameters().isEmpty() && !(method.getParameters().get(0) instanceof J.Empty)) {
            return false;
        }
        if (method.getBody() == null ||
                method.getBody().getStatements().size() != 1 ||
                !(method.getBody().getStatements().get(0) instanceof J.Return)) {
            return false;
        }
        Expression returnExpression = ((J.Return) method.getBody().getStatements().get(0)).getExpression();
        if (!(returnExpression instanceof J.Identifier) && !(returnExpression instanceof J.FieldAccess)) {
            return false;
        }
        // compiler already guarantees that the returned variable is a subtype of the method type, but we want an exact match
        return method.getType() == returnExpression.getType();
    }

    public static String deriveGetterMethodName(@Nullable JavaType type, String fieldName) {
        if (type == JavaType.Primitive.Boolean) {
            boolean alreadyStartsWithIs = fieldName.length() >= 3 &&
                    fieldName.substring(0, 3).matches("is[A-Z]");
            if (alreadyStartsWithIs) {
                return fieldName;
            }
            return "is" + StringUtils.capitalize(fieldName);
        }
        return "get" + StringUtils.capitalize(fieldName);
    }

    static boolean isSetter(Cursor cursor, AnnotationService service) {
        if (!(cursor.getValue() instanceof J.MethodDeclaration)) {
            return false;
        }
        J.MethodDeclaration method = cursor.getValue();

        // Check return type: void
        if (method.getType() != JavaType.Primitive.Void) {
            return false;
        }
        // Check signature: single parameter
        if (method.getParameters().size() != 1 || method.getParameters().get(0) instanceof J.Empty) {
            return false;
        }
        // Check body: just an assignment
        if (method.getBody() == null || //abstract methods can be null
                method.getBody().getStatements().size() != 1 ||
                !(method.getBody().getStatements().get(0) instanceof J.Assignment)) {
            return false;
        }

        // Check there is no annotation except @Overwrite
        if (hasAnyAnnotatioOtherThanOverride(cursor, service)) {
            return false;
        }

        // Check there's no up/down cast between parameter and field
        J.VariableDeclarations.NamedVariable param = ((J.VariableDeclarations) method.getParameters().get(0)).getVariables().get(0);
        Expression variable = ((J.Assignment) method.getBody().getStatements().get(0)).getVariable();
        if (param.getType() != variable.getType()) {
            return false;
        }

        // Method name has to match
        JavaType.FullyQualified declaringType = method.getMethodType().getDeclaringType();
        if (variable instanceof J.Identifier) {
            J.Identifier assignedVar = (J.Identifier) variable;
            if (hasMatchingSetterMethodName(method, assignedVar.getSimpleName())) {
                // Check field is declared on method type
                return assignedVar.getFieldType() != null && declaringType == assignedVar.getFieldType().getOwner();
            }
        } else if (variable instanceof J.FieldAccess) {
            J.FieldAccess assignedField = (J.FieldAccess) variable;
            if (hasMatchingSetterMethodName(method, assignedField.getSimpleName())) {
                Expression target = assignedField.getTarget();
                // Check field is declared on method type
                return target instanceof J.Identifier && ((J.Identifier) target).getFieldType() != null &&
                        declaringType == ((J.Identifier) target).getFieldType().getOwner();
            }
        }

        return false;
    }

    private static boolean hasMatchingSetterMethodName(J.MethodDeclaration method, String simpleName) {
        return method.getSimpleName().equals("set" + StringUtils.capitalize(simpleName));
    }

    public static boolean isEffectivelySetter(J.MethodDeclaration method) {
        if (method.getType() != JavaType.Primitive.Void) {
            return false;
        }
        if (method.getParameters().size() != 1 || method.getParameters().get(0) instanceof J.Empty) {
            return false;
        }

        J.VariableDeclarations variableDeclarations = (J.VariableDeclarations) method.getParameters().get(0);
        J.VariableDeclarations.NamedVariable param = variableDeclarations.getVariables().get(0);
        String paramName = param.getName().toString();

        if (method.getBody() == null ||
                method.getBody().getStatements().size() != 1 ||
                !(method.getBody().getStatements().get(0) instanceof J.Assignment)) {
            return false;
        }
        J.Assignment assignment = (J.Assignment) method.getBody().getStatements().get(0);

        if (!(assignment.getVariable() instanceof J.FieldAccess) && !(assignment.getVariable() instanceof J.Identifier)) {
            return false;
        }

        JavaType fieldType = assignment.getVariable().getType();
        // assigned value is exactly the parameter
        return assignment.getAssignment().toString().equals(paramName) &&
                        param.getType() != null &&
                        param.getType().equals(fieldType);  // type of parameter and field have to match
    }

    public static String deriveSetterMethodName(JavaType.Variable fieldType) {
        return "set" + StringUtils.capitalize(fieldType.getName());
    }

    static AccessLevel getAccessLevel(J.MethodDeclaration methodDeclaration) {
        if (methodDeclaration.hasModifier(Public)) {
            return PUBLIC;
        }
        if (methodDeclaration.hasModifier(Protected)) {
            return PROTECTED;
        }
        if (methodDeclaration.hasModifier(Private)) {
            return PRIVATE;
        }
        return PACKAGE;
    }

    private static boolean hasAnyAnnotatioOtherThanOverride(Cursor cursor, AnnotationService service) {
        List<J.Annotation> annotations = service.getAllAnnotations(cursor);
        return !(annotations.isEmpty() || (annotations.size() == 1 && OVERRIDE_MATCHER.matches(annotations.get(0))));
    }
}
