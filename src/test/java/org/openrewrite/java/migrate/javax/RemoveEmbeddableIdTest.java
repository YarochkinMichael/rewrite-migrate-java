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
package org.openrewrite.java.migrate.javax;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class RemoveEmbeddableIdTest implements RewriteTest {
    @Override
    public void defaults(RecipeSpec spec) {
        spec.parser(JavaParser.fromJavaVersion().classpathFromResources(new InMemoryExecutionContext(), "javax.persistence-api-2.2"))
          .recipe(new RemoveEmbeddableId());
    }

    @DocumentExample
    @Test
    void removeIdFromEmbeddableObject() {
        //language=java
        rewriteRun(
          java(
            """
              import javax.persistence.EmbeddedId;
              import javax.persistence.Entity;

              @Entity
              public class MainEntity {
                 @EmbeddedId
                 private EmbeddableObject eo;
              }
              """
          ),
          java(
            """
              import javax.persistence.Embeddable;
              import javax.persistence.Id;

              @Embeddable
              public class EmbeddableObject {
                  @Id
                  private int field;
              }
              """,
            """
              import javax.persistence.Embeddable;

              @Embeddable
              public class EmbeddableObject {
                  private int field;
              }
              """
          )
        );
    }

    @Test
    void noChangeIfNotEmbeddable() {
        //language=java
        rewriteRun(
          java(
            """
              import javax.persistence.EmbeddedId;
              import javax.persistence.Entity;

              @Entity
              public class MainEntity {
                 @EmbeddedId
                 private EmbeddableObject eo;
              }
              """
          ),
          java(
            """
              import javax.persistence.Embeddable;
              import javax.persistence.Id;

              public class EmbeddableObject {
                  @Id
                  private int field;
              }
              """
          )
        );
    }

    @Test
    void noChangeIfNoIdAnnotation() {
        //language=java
        rewriteRun(
          java(
            """
              import javax.persistence.EmbeddedId;
              import javax.persistence.Entity;

              @Entity
              public class MainEntity {
                 @EmbeddedId
                 private EmbeddableObject eo;
              }
              """
          ),
          java(
            """
              import javax.persistence.Embeddable;

              @Embeddable
              public class EmbeddableObject {
                  private int field;
              }
              """
          )
        );
    }

    @Test
    void noChangeIfNotReferencedByEmbeddedId() {
        //language=java
        rewriteRun(
          java(
            """
              import javax.persistence.EmbeddedId;
              import javax.persistence.Entity;

              @Entity
              public class MainEntity {
                 private EmbeddableObject eo;
              }
              """
          ),
          java(
            """
              import javax.persistence.Embeddable;
              import javax.persistence.Id;

              @Embeddable
              public class EmbeddableObject {
                  @Id
                  private int field;
              }
              """
          )
        );
    }

    @Test
    void removeEmbeddableIdFromInnerClass() {
        //language=java
        rewriteRun(
          java(
            """
              import javax.persistence.EmbeddedId;
              import javax.persistence.Entity;

              @Entity
              public class MainEntity {
                  @EmbeddedId
                  private OuterClass.EmbeddableObject eo;
              }
              """
          ),
          java(
            """
              import javax.persistence.Embeddable;
              import javax.persistence.Id;

              public class OuterClass {
                  @Id
                  private int test;
                  @Embeddable
                  class EmbeddableObject {
                      @Id
                      private int field;
                  }
              }
              """,
            """
              import javax.persistence.Embeddable;
              import javax.persistence.Id;

              public class OuterClass {
                  @Id
                  private int test;
                  @Embeddable
                  class EmbeddableObject {
                      private int field;
                  }
              }
              """
          )
        );
    }

    @Test
    void removeEmbeddableIdFromClassAndInnerClasses() {
        //language=java
        rewriteRun(
          java(
            """
              import javax.persistence.EmbeddedId;
              import javax.persistence.Entity;

              @Entity
              public class MainEntity {
                  @EmbeddedId
                  private OuterClass eo;
              }
              """
          ),
          java(
            """
              import javax.persistence.Embeddable;
              import javax.persistence.Id;

              @Embeddable
              public class OuterClass {
                  @Id
                  private int test;

                  class NonEmbeddableObject {
                      @Id
                      private int field;
                  }
              }
              """,
            """
              import javax.persistence.Embeddable;

              @Embeddable
              public class OuterClass {
                  private int test;

                  class NonEmbeddableObject {
                      private int field;
                  }
              }
              """
          )
        );
    }
}
