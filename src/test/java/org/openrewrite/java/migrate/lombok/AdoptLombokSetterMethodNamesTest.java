/*
 * Copyright 2024 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.migrate.lombok;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.ExpectedToFail;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class AdoptLombokSetterMethodNamesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AdoptLombokSetterMethodNames());
    }

    @DocumentExample
    @Test
    void renameInSingleClass() {
        rewriteRun(// language=java
          java(
            """
              class A {
                  int foo = 9;
                  public void storeFoo(int foo) {
                      this.foo = foo;
                  }
              }
              """,
            """
              class A {
                  int foo = 9;
                  public void setFoo(int foo) {
                      this.foo = foo;
                  }
              }
              """
          )
        );
    }

    @Test
    void renameWithoutFieldAccess() {
        rewriteRun(// language=java
          java(
            """
              class A {
                  int foo = 9;
                  public void storeFoo(int newfoo) {
                      foo = newfoo;
                  }
              }
              """,
            """
              class A {
                  int foo = 9;
                  public void setFoo(int newfoo) {
                      foo = newfoo;
                  }
              }
              """
          )
        );
    }

    @Test
    void renameInSingleClassWhitespace() {
        rewriteRun(// language=java
          java(
            """
              class A {
                  int foo = 9;
                  public void storeFoo( int  foo ) {
                      this .foo  =  foo;
                  }
              }
              """,
            """
              class A {
                  int foo = 9;
                  public void setFoo( int  foo ) {
                      this .foo  =  foo;
                  }
              }
              """
          )
        );
    }

    @Test
    void renamePrimitiveBooleanInSingleClass() {
        rewriteRun(// language=java
          java(
            """
              class A {
                  boolean foo;
                  void storeFoo(boolean foo) { this.foo = foo; }
              }
              """,
            """
              class A {
                  boolean foo;
                  void setFoo(boolean foo) { this.foo = foo; }
              }
              """
          )
        );
    }

    @Test
    void renameClassBooleanInSingleClass() {
        rewriteRun(// language=java
          java(
            """
              class A {
                  Boolean foo;
                  void storeFoo(Boolean foo) { this.foo = foo; }
              }
              """,
            """
              class A {
                  Boolean foo;
                  void setFoo(Boolean foo) { this.foo = foo; }
              }
              """
          )
        );
    }

    @Test
    void renameAcrossClasses() {
        rewriteRun(// language=java
          java(
            """
              class A {
                  int foo = 9;
                  void storeFoo(int foo) { this.foo = foo; }
              }
              """,
            """
              class A {
                  int foo = 9;
                  void setFoo(int foo) { this.foo = foo; }
              }
              """
          ),// language=java
          java(
            """
              class B {
                  void useIt() {
                      var a = new A();
                      a.storeFoo(4);
                  }
              }
              """,
            """
              class B {
                  void useIt() {
                      var a = new A();
                      a.setFoo(4);
                  }
              }
              """
          )
        );
    }

    @Test
    void withoutPackage() {
        rewriteRun(// language=java
          java(
            """
              class A {

                  private long foo;

                  public void setTime(long foo) {
                      this.foo = foo;
                  }
              }
              """,
            """
              class A {

                  private long foo;

                  public void setFoo(long foo) {
                      this.foo = foo;
                  }
              }
              """
          )
        );
    }

    @Test
    void shouldChangeOverridesOfInternalMethods() {
        rewriteRun(// language=java
          java(
            """
              class A {

                  private long foo;

                  public void setTime(long foo) {
                      this.foo = foo;
                  }
              }
              """,
            """
              class A {

                  private long foo;

                  public void setFoo(long foo) {
                      this.foo = foo;
                  }
              }
              """
          ),// language=java
          java(
            """
              class B extends A {

                  @Override
                  public void setTime(long foo) {
                  }
              }
              """,
            """
              class B extends A {

                  @Override
                  public void setFoo(long foo) {
                  }
              }
              """
          )
        );
    }

    /**
     * If two methods are effectively the same setter then only one can be renamed.
     * Renaming both would result in a duplicate method definition, so we cannot do this.
     * Ideally the other effective setter would have their usages renamed but be themselves deleted...
     * TODO: create a second cleanup recipe that identifies redundant Setters (isEffectiveSetter + field already has the setter annotation)
     *  and redirects their usage (ChangeMethodName with both flags true) and then deletes them.
     */
    @Test
    void shouldNotRenameTwoToTheSame() {
        rewriteRun(// language=java
          java(
            """
              class A {

                  private long foo;

                  public void firstToBeRenamed(long foo) {
                      this.foo = foo;
                  }

                  public void secondToBeRenamed(long foo) {
                      this.foo = foo;
                  }
              }
              """,
            """
              class A {

                  private long foo;

                  public void setFoo(long foo) {
                      this.foo = foo;
                  }

                  public void secondToBeRenamed(long foo) {
                      this.foo = foo;
                  }
              }
              """
          )
        );
    }

    /**
     * Methods in inner classes should be renamed as well.
     */
    @Test
    void shouldWorkOnInnerClasses() {
        rewriteRun(// language=java
          java(
            """
              class A {

                  class B {

                      private long foo;

                      public void storeFoo(long foo) {
                          this.foo = foo;
                      }
                  }
              }
              """,
            """
              class A {

                  class B {

                      private long foo;

                      public void setFoo(long foo) {
                          this.foo = foo;
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void shouldWorkOnInnerClasses2() {
        rewriteRun(// language=java
          java(
            """
              class A {

                  class B {

                  class C {

                      private long foo;

                      public void giveFoo(long foo) {
                          this.foo = foo;
                      }
                  }}
              }
              """,
            """
              class A {

                  class B {

                  class C {

                      private long foo;

                      public void setFoo(long foo) {
                          this.foo = foo;
                      }
                  }}
              }
              """
          )
        );
    }

    /**
     * Methods on top level should be renamed just as well when there is an inner class.
     */
    @ExpectedToFail("We use `cu.getTypesInUse().getDeclaredMethods()` as a performance optimization to avoid visits")
    @Test
    void shouldWorkDespiteInnerClassesSameNameMethods() {
        rewriteRun(// language=java
          java(
            """
              class A {

                  private long foo;

                  public void storeFoo(long foo) {
                      this.foo = foo;
                  }

                  class B {

                      private long foo;

                      public void storeFoo(long foo) {
                          this.foo = foo;
                      }
                  }
              }
              """,
            """
              class A {

                  private long foo;

                  public void setFoo(long foo) {
                      this.foo = foo;
                  }

                  class B {

                      private long foo;

                      public void setFoo(long foo) {
                          this.foo = foo;
                      }
                  }
              }
              """
          )
        );
    }

    /**
     * Methods on top level should be renamed just as well when there is an inner class.
     */
    @Test
    void shouldWorkDespiteInnerClassesDifferentNameMethods() {
        rewriteRun(// language=java
          java(
            """
              class A {

                  private long foo;

                  public void storeFoo(long foo) {
                      this.foo = foo;
                  }

                  class B {

                      private long ba;

                      public void storeBa(long ba) {
                          this.ba = ba;
                      }
                  }
              }
              """,
            """
              class A {

                  private long foo;

                  public void setFoo(long foo) {
                      this.foo = foo;
                  }

                  class B {

                      private long ba;

                      public void setBa(long ba) {
                          this.ba = ba;
                      }
                  }
              }
              """
          )
        );
    }

    /**
     * If existing method names need to be rotated in a loop the recipe should still work.
     * For now this is not planned.
     */
    @ExpectedToFail("Not implemented yet")
    @Test
    void shouldWorkOnCircleCasesButDoesntYet() {
        rewriteRun(// language=java
          java(
            """
              class A {

                  int foo;
                  int bar;

                  public void setBar(int bar) {
                      this.foo = bar;
                  }

                  public void setFoo(int foo) {
                      this.bar = foo;
                  }

              }
              """,
            """
              class A {

                  int foo;
                  int bar;

                  public void setFoo(int foo) {
                      this.foo = foo;
                  }

                  public void setBar(int bar) {
                      this.bar = bar;
                  }

              }
              """
          )
        );
    }

    @Nested
    class NoChange {

        @Test
        void noBoxing1() {
            rewriteRun(// language=java
              java(
                """
                  class A {
                      Boolean Foo;
                      void storeFoo(boolean foo) { this.foo = foo; }
                  }
                  """
              )
            );
        }

        @Test
        void noBoxing2() {
            rewriteRun(// language=java
              java(
                """
                  class A {
                      boolean Foo;
                      void storeFoo(Boolean foo) { this.foo = foo; }
                  }
                  """
              )
            );
        }

        @Test
        void shouldNotChangeOverridesOfExternalMethods() {
            rewriteRun(// language=java
              java(
                """
                  import java.util.Date;

                  class A extends Date {

                      private long foo;

                      @Override
                      public long setTime(long time) {
                          this.foo = time;
                      }
                  }
                  """
              )
            );
        }

        @Test
        void shouldNotRenameToExistingMethods() {
            rewriteRun(// language=java
              java(
                """
                  class A {

                      private long foo;

                      public void setTime(long foo) {
                          this.foo = foo;
                      }

                      public void setFoo(long foo) {
                      }
                  }
                  """
              )
            );
        }
    }
}
