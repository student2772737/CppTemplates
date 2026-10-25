package ru.student.cpptemplates;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public class InsertForAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);

        if (project == null || editor == null
                || !editor.getDocument().isWritable()) {
            return;
        }

        Document document = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();

        int lineNumber = document.getLineNumber(offset);
        int lineStart = document.getLineStartOffset(lineNumber);

        String textBeforeCaret = document.getCharsSequence()
                .subSequence(lineStart, offset)
                .toString();

        // Сохраняем отступ текущей строки.
        String indent = textBeforeCaret.isBlank()
                ? textBeforeCaret
                : "";

        String beforeCaret = "for (int i = 0; i < 10; ++i) {\n"
                + indent + "    ";

        String afterCaret = "\n" + indent + "}";

        String template = beforeCaret + afterCaret;

        WriteCommandAction.runWriteCommandAction(project, () -> {
            document.insertString(offset, template);
            editor.getCaretModel().moveToOffset(
                    offset + beforeCaret.length()
            );
        });
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Editor editor = event.getData(CommonDataKeys.EDITOR);

        event.getPresentation().setEnabled(
                event.getProject() != null
                        && editor != null
                        && editor.getDocument().isWritable()
        );
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}