package ru.student.cpptemplates;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public class InsertClassAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);

        if (project == null || editor == null
                || !editor.getDocument().isWritable()) {
            return;
        }

        String template = """
                class MyClass {
                public:
                    MyClass() = default;

                    void setValue(int value) {
                        value_ = value;
                    }

                    int getValue() const {
                        return value_;
                    }

                private:
                    int value_ = 0;
                };
                """;

        int offset = editor.getCaretModel().getOffset();

        WriteCommandAction.runWriteCommandAction(project, () -> {
            editor.getDocument().insertString(offset, template);

            int nameStart = offset + template.indexOf("MyClass");
            editor.getCaretModel().moveToOffset(nameStart);
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