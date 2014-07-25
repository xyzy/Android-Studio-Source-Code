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
package com.intellij.openapi.editor.actions;

import com.intellij.find.*;
import com.intellij.find.editorHeaderActions.AddOccurrenceAction;
import com.intellij.find.editorHeaderActions.EditorHeaderAction;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.actionSystem.EditorAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.Nullable;

public class SelectNextOccurrenceAction extends EditorAction {
  protected SelectNextOccurrenceAction() {
    super(new Handler());
  }

  static class Handler extends SelectOccurrencesActionHandler {
    @Override
    public boolean isEnabled(Editor editor, DataContext dataContext) {
      return super.isEnabled(editor, dataContext) && editor.getProject() != null && editor.getCaretModel().supportsMultipleCarets();
    }

    @Override
    public void doExecute(Editor editor, @Nullable Caret c, DataContext dataContext) {
      if (executeEquivalentFindPanelAction(editor, dataContext)) return;

      Caret caret = c == null ? editor.getCaretModel().getPrimaryCaret() : c;
      TextRange wordSelectionRange = getSelectionRange(editor, caret);
      boolean notFoundPreviously = getAndResetNotFoundStatus(editor);
      boolean wholeWordSearch = isWholeWordSearch(editor);
      if (caret.hasSelection()) {
        Project project = editor.getProject();
        String selectedText = caret.getSelectedText();
        if (project == null || selectedText == null) {
          return;
        }
        FindManager findManager = FindManager.getInstance(project);

        FindModel model = getFindModel(selectedText, wholeWordSearch);

        findManager.setFindWasPerformed();
        findManager.setFindNextModel(model);

        int searchStartOffset = notFoundPreviously ? 0 : caret.getSelectionEnd();
        FindResult findResult = findManager.findString(editor.getDocument().getCharsSequence(), searchStartOffset, model);
        if (findResult.isStringFound()) {
          boolean caretAdded = FindUtil.selectSearchResultInEditor(editor, findResult, caret.getOffset() - caret.getSelectionStart());
          if (!caretAdded) {
            // this means that the found occurence is already selected
            if (notFoundPreviously) {
              setNotFoundStatus(editor); // to make sure we won't show hint anymore if there are no more occurrences
            }
          }
        }
        else {
          setNotFoundStatus(editor);
          showHint(editor);
        }
      }
      else {
        if (wordSelectionRange == null) {
          return;
        }
        setSelection(editor, caret, wordSelectionRange);
        setWholeWordSearch(editor, true);
      }
      editor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
    }

    @Override
    protected EditorHeaderAction getEquivalentFindPanelAction(EditorSearchComponent searchComponent) {
      return new AddOccurrenceAction(searchComponent);
    }
  }
}
