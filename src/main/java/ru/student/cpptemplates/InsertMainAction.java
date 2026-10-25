package ru.student.cpptemplates;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public class InsertMainAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);

        if (project == null || editor == null
                || !editor.getDocument().isWritable()) {
            return;
        }

        String beforeCaret = "#include <iostream>\n\n"
                + "int main() {\n"
                + "    ";

        String afterCaret = "\n"
                + "    return 0;\n"
                + "}\n";

        String template = beforeCaret + afterCaret;
        int offset = editor.getCaretModel().getOffset();

        WriteCommandAction.runWriteCommandAction(project, () -> {
            editor.getDocument().insertString(offset, template);
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