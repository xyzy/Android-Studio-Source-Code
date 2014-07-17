/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.navigation
import com.intellij.ide.util.gotoByName.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase
import com.intellij.util.Consumer
import com.intellij.util.concurrency.Semaphore
import org.jetbrains.annotations.NotNull

/**
 * @author peter
 */
class ChooseByNameTest extends LightCodeInsightFixtureTestCase {
  ChooseByNamePopup myPopup

  @Override
  protected void tearDown() throws Exception {
    myPopup = null
    super.tearDown()
  }

  public void "test goto class order by matching degree"() {
    def startMatch = myFixture.addClass("class UiUtil {}")
    def wordSkipMatch = myFixture.addClass("class UiAbstractUtil {}")
    def camelMatch = myFixture.addClass("class UberInstructionUxTopicInterface {}")
    def middleMatch = myFixture.addClass("class BaseUiUtil {}")
    def elements = getPopupElements(new GotoClassModel2(project), "uiuti")
    assert elements == [startMatch, wordSkipMatch, camelMatch, ChooseByNameBase.NON_PREFIX_SEPARATOR, middleMatch]
  }

  public void "test annotation syntax"() {
    def match = myFixture.addClass("@interface Anno1 {}")
    myFixture.addClass("class Anno2 {}")
    def elements = getPopupElements(new GotoClassModel2(project), "@Anno")
    assert elements == [match]
  }

  public void "test no result for empty patterns"() {
    myFixture.addClass("@interface Anno1 {}")
    myFixture.addClass("class Anno2 {}")

    def popup = createPopup(new GotoClassModel2(project))
    assert calcPopupElements(popup, "") == []
    popup.close(false)

    popup = createPopup(new GotoClassModel2(project))
    assert calcPopupElements(popup, "@") == []
    popup.close(false)

    popup = createPopup(new GotoFileModel(project))
    assert calcPopupElements(popup, "foo/") == []
    popup.close(false)
  }

  public void "test filter overridden methods from goto symbol"() {
    def intf = myFixture.addClass("""
class Intf {
  void xxx1() {}
  void xxx2() {}
}""")
    def impl = myFixture.addClass("""
class Impl extends Intf {
    void xxx1() {}
    void xxx3() {}
}
""")

    def elements = getPopupElements(new GotoSymbolModel2(project), "xxx")

    assert intf.findMethodsByName('xxx1', false)[0] in elements
    assert intf.findMethodsByName('xxx2', false)[0] in elements

    assert impl.findMethodsByName('xxx3', false)[0] in elements
    assert !(impl.findMethodsByName('xxx1', false)[0] in elements)
  }

  public void "test disprefer underscore"() {
    def intf = myFixture.addClass("""
class Intf {
  void _xxx1() {}
  void xxx2() {}
}""")

    def elements = getPopupElements(new GotoSymbolModel2(project), "xxx")
    assert elements == [intf.findMethodsByName('xxx2', false), ChooseByNameBase.NON_PREFIX_SEPARATOR, intf.findMethodsByName('_xxx1', false)]
  }

  public void "test prefer exact extension matches"() {
    def m = myFixture.addFileToProject("relaunch.m", "")
    def mod = myFixture.addFileToProject("reference.mod", "")
    def elements = getPopupElements(new GotoFileModel(project), "re*.m")
    assert elements == [m, mod]
  }

  public void "test prefer better path matches"() {
    def fooIndex = myFixture.addFileToProject("foo/index.html", "foo")
    def fooBarIndex = myFixture.addFileToProject("foo/bar/index.html", "foo bar")
    def barFooIndex = myFixture.addFileToProject("bar/foo/index.html", "bar foo")
    def elements = getPopupElements(new GotoFileModel(project), "foo/index")
    assert elements == [fooIndex, barFooIndex, fooBarIndex]
  }

  public void "test sort same-named items by path"() {
    def files = (30..10).collect { i -> myFixture.addFileToProject("foo$i/index.html", "foo$i") }.reverse()
    def elements = getPopupElements(new GotoFileModel(project), "index")
    assert elements == files
  }

  public void "test middle matching for directories"() {
    def fooIndex = myFixture.addFileToProject("foo/index.html", "foo")
    def ooIndex = myFixture.addFileToProject("oo/index.html", "oo")
    def fooBarIndex = myFixture.addFileToProject("foo/bar/index.html", "foo bar")
    def elements = getPopupElements(new GotoFileModel(project), "oo/index")
    assert elements == [ooIndex, fooIndex, fooBarIndex]
  }

  public void "test prefer files from current directory"() {
    def fooIndex = myFixture.addFileToProject("foo/index.html", "foo")
    def barIndex = myFixture.addFileToProject("bar/index.html", "bar")
    def fooContext = myFixture.addFileToProject("foo/context.html", "")
    def barContext = myFixture.addFileToProject("bar/context.html", "")

    def popup = createPopup(new GotoFileModel(project), fooContext)
    assert calcPopupElements(popup, "index") == [fooIndex, barIndex]
    popup.close(false)

    popup = createPopup(new GotoFileModel(project), barContext)
    assert calcPopupElements(popup, "index") == [barIndex, fooIndex]

  }

  public void "test goto file can go to dir"() {
    PsiFile fooIndex = myFixture.addFileToProject("foo/index.html", "foo")
    PsiFile barIndex = myFixture.addFileToProject("bar.txt/bar.txt", "foo")

    def popup = createPopup(new GotoFileModel(project), fooIndex)

    def fooDir
    def barDir
    edt {
      fooDir = fooIndex.containingDirectory
      barDir = barIndex.containingDirectory
    }

    assert calcPopupElements(popup, "foo/") == [fooDir]
    assert calcPopupElements(popup, "foo\\") == [fooDir]
    assert calcPopupElements(popup, "/foo") == [fooDir]
    assert calcPopupElements(popup, "\\foo") == [fooDir]
    assert calcPopupElements(popup, "foo") == []
    assert calcPopupElements(popup, "/index.html") == [fooIndex]
    assert calcPopupElements(popup, "\\index.html") == [fooIndex]
    assert calcPopupElements(popup, "index.html/") == [fooIndex]
    assert calcPopupElements(popup, "index.html\\") == [fooIndex]

    assert calcPopupElements(popup, "bar.txt/") == [barDir]
    assert calcPopupElements(popup, "bar.txt\\") == [barDir]
    assert calcPopupElements(popup, "/bar.txt") == [barDir]
    assert calcPopupElements(popup, "\\bar.txt") == [barDir]
    assert calcPopupElements(popup, "bar.txt") == [barIndex]
    popup.close(false)
  }

  public void "test find method by qualified name"() {
    def method = myFixture.addClass("package foo.bar; class Goo { void zzzZzz() {} }").methods[0]
    assert getPopupElements(new GotoSymbolModel2(project), 'zzzZzz') == [method]
    assert getPopupElements(new GotoSymbolModel2(project), 'goo.zzzZzz') == [method]
    assert getPopupElements(new GotoSymbolModel2(project), 'foo.bar.goo.zzzZzz') == [method]
    assert getPopupElements(new GotoSymbolModel2(project), 'foo.zzzZzz') == [method]
    assert getPopupElements(new GotoSymbolModel2(project), 'bar.zzzZzz') == [method]
    assert getPopupElements(new GotoSymbolModel2(project), 'bar.goo.zzzZzz') == [method]
  }

  public void "test line and column suffix"() {
    def c = myFixture.addClass("package foo; class Bar {}")
    assert getPopupElements(new GotoClassModel2(project), 'Bar') == [c]
    assert getPopupElements(new GotoClassModel2(project), 'Bar:2') == [c]
    assert getPopupElements(new GotoClassModel2(project), 'Bar:2:3') == [c]
    assert getPopupElements(new GotoClassModel2(project), 'Bar:[2:3]') == [c]
    assert getPopupElements(new GotoClassModel2(project), 'Bar:[2,3]') == [c]
  }

  public void "test super method in jdk"() {
    def ourRun = myFixture.addClass("package foo.bar; class Goo implements Runnable { public void run() {} }").methods[0]
    def sdkRun
    edt {
      sdkRun = ourRun.containingClass.interfaces[0].methods[0]
    }
    assert getPopupElements(new GotoSymbolModel2(project), 'run ', true) == [sdkRun]
    assert getPopupElements(new GotoSymbolModel2(project), 'run ', false) == [ourRun]
  }

  private List<Object> getPopupElements(ChooseByNameModel model, String text, boolean checkboxState = false) {
    return calcPopupElements(createPopup(model), text, checkboxState)
  }

  private static ArrayList<String> calcPopupElements(ChooseByNamePopup popup, String text, boolean checkboxState = false) {
    List<Object> elements = ['empty']
    def semaphore = new Semaphore()
    semaphore.down()
    popup.scheduleCalcElements(text, checkboxState, ModalityState.NON_MODAL, { set ->
      elements = set as List
      semaphore.up()
    } as Consumer<Set<?>>)
    if (!semaphore.waitFor(10000)) {
      printThreadDump()
      fail()
    }
    return elements
  }

  private ChooseByNamePopup createPopup(ChooseByNameModel model, PsiElement context = null) {
    if (myPopup) {
      myPopup.close(false)
    }

    def popup = myPopup = ChooseByNamePopup.createPopup(project, model, (PsiElement)context, "")
    Disposer.register(testRootDisposable, { popup.close(false) } as Disposable)
    popup
  }

  @Override
  protected boolean runInDispatchThread() {
    return false
  }

  @Override
  protected void invokeTestRunnable(@NotNull Runnable runnable) throws Exception {
    runnable.run()
  }
}
