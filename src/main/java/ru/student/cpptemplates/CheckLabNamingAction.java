package ru.student.cpptemplates;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class CheckLabNamingAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);

        if (project == null || file == null
                || project.getBasePath() == null) {
            return;
        }

        Document document =
                FileDocumentManager.getInstance().getDocument(file);

        if (document == null || !document.isWritable()) {
            Messages.showErrorDialog(
                    project,
                    "Документ недоступен для изменения.",
                    "Check Lab Naming"
            );
            return;
        }

        if (!StandardCharsets.UTF_8.equals(file.getCharset())) {
            Messages.showErrorDialog(
                    project,
                    "Эта версия команды поддерживает файлы в UTF-8.",
                    "Check Lab Naming"
            );
            return;
        }

        Path projectDirectory = Path.of(project.getBasePath());
        Path sourceFile = Path.of(file.getPath());
        Path buildDirectory =
                projectDirectory.resolve("cmake-build-debug");

        Path executable = Path.of(
                PathManager.getHomePath(),
                "bin", "clang", "win", "x64", "bin", "clang-tidy.exe"
        );

        if (!Files.isRegularFile(executable)) {
            Messages.showErrorDialog(
                    project,
                    "Не найден Clang-Tidy:\n" + executable,
                    "Check Lab Naming"
            );
            return;
        }

        if (!Files.isRegularFile(
                buildDirectory.resolve("compile_commands.json")
        )) {
            Messages.showErrorDialog(
                    project,
                    "Не найден compile_commands.json в:\n"
                            + buildDirectory,
                    "Check Lab Naming"
            );
            return;
        }

        FileDocumentManager.getInstance().saveDocument(document);

        String originalText = document.getText();
        long originalStamp = document.getModificationStamp();

        new Task.Backgroundable(project, "Checking C++ naming", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                Path config = null;
                Path log = null;
                Path fixesFile = null;
                Process process = null;

                try {
                    indicator.setIndeterminate(true);
                    indicator.setText("Анализ имён...");

                    byte[] originalBytes = Files.readAllBytes(sourceFile);

                    if (!normalize(decodeUtf8(originalBytes))
                            .equals(originalText)) {
                        throw new IOException(
                                "Содержимое файла отличается от редактора. "
                                        + "Сохраните файл и повторите проверку."
                        );
                    }

                    config = Files.createTempFile("lab-style-", ".yaml");
                    log = Files.createTempFile("lab-output-", ".txt");
                    fixesFile = Files.createTempFile("lab-fixes-", ".yaml");

                    try (InputStream resource =
                                 CheckLabNamingAction.class.getResourceAsStream(
                                         "/lab-clang-tidy.yaml"
                                 )) {
                        if (resource == null) {
                            throw new IOException(
                                    "Не найден lab-clang-tidy.yaml."
                            );
                        }

                        Files.copy(
                                resource,
                                config,
                                StandardCopyOption.REPLACE_EXISTING
                        );
                    }

                    ProcessBuilder builder = new ProcessBuilder(
                            executable.toString(),
                            sourceFile.toString(),
                            "-p=" + buildDirectory,
                            "--config-file=" + config,
                            "--export-fixes=" + fixesFile,
                            "--quiet"
                    );

                    builder.directory(projectDirectory.toFile());
                    builder.redirectErrorStream(true);
                    builder.redirectOutput(log.toFile());

                    process = builder.start();

                    long deadline = System.nanoTime()
                            + TimeUnit.SECONDS.toNanos(120);

                    while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                        indicator.checkCanceled();

                        if (System.nanoTime() > deadline) {
                            throw new IOException(
                                    "Проверка не завершилась за 120 секунд."
                            );
                        }
                    }

                    indicator.checkCanceled();

                    int exitCode = process.exitValue();
                    String output = Files.readString(
                            log, StandardCharsets.UTF_8
                    );

                    if (output.isBlank()) {
                        output = exitCode == 0
                                ? "Нарушений имён не найдено."
                                : "Анализ завершился с ошибкой.";
                    }

                    String result = "Файл: " + sourceFile
                            + "\nКод завершения: " + exitCode
                            + "\n\n" + output;

                    String correctedText = null;

                    if (exitCode == 0) {
                        try {
                            String renamedText = prepareFixes(
                                    fixesFile, sourceFile, originalBytes
                            );

                            if (renamedText == null) {
                                renamedText = originalText;
                            }

                            correctedText = normalize(
                                    LabCodeFormatter.format(
                                            renamedText,
                                            sourceFile,
                                            indicator
                                    )
                            );

                            if (!correctedText.equals(originalText)) {
                                result += "\n\nПредлагаемый результат "
                                        + "после переименования "
                                        + "и форматирования:\n\n"
                                        + correctedText;
                            } else {
                                result += "\n\nИмена и форматирование "
                                        + "уже соответствуют настройкам.";
                            }

                        } catch (ProcessCanceledException exception) {
                            throw exception;
                        } catch (IOException | RuntimeException exception) {
                            correctedText = null;

                            result += "\n\nПрименение недоступно:\n"
                                    + exception.getMessage();
                        }
                    }

                    String finalResult = result;
                    String finalCorrectedText = correctedText;

                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (project.isDisposed()) {
                            return;
                        }

                        Runnable apply = null;

                        if (finalCorrectedText != null
                                && !finalCorrectedText.equals(originalText)) {
                            apply = () -> applyChanges(
                                    project,
                                    document,
                                    sourceFile,
                                    originalBytes,
                                    originalStamp,
                                    finalCorrectedText
                            );
                        }

                        new ResultDialog(
                                project, finalResult, apply
                        ).show();
                    });

                } catch (ProcessCanceledException exception) {
                    throw exception;
                } catch (IOException | RuntimeException exception) {
                    showError(project, exception.getMessage());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    if (process != null && process.isAlive()) {
                        process.destroyForcibly();
                    }

                    deleteTemporaryFile(config);
                    deleteTemporaryFile(log);
                    deleteTemporaryFile(fixesFile);
                }
            }
        }.queue();
    }

    private static String prepareFixes(
            Path fixesFile,
            Path sourceFile,
            byte[] originalBytes
    ) throws IOException {
        String yaml = Files.readString(
                fixesFile, StandardCharsets.UTF_8
        );

        if (yaml.isBlank()) {
            return null;
        }

        Load parser = new Load(LoadSettings.builder().build());
        Object data = parser.loadFromString(yaml);

        Set<Replacement> unique = new LinkedHashSet<>();
        collectReplacements(data, sourceFile, unique);

        if (unique.isEmpty()) {
            return null;
        }

        List<Replacement> replacements = new ArrayList<>(unique);
        replacements.sort(Comparator.comparingInt(Replacement::offset));

        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int position = 0;
        Replacement previous = null;

        for (Replacement replacement : replacements) {
            long end = (long) replacement.offset() + replacement.length();

            if (replacement.offset() < position
                    || replacement.offset() < 0
                    || replacement.length() < 0
                    || end > originalBytes.length
                    || (previous != null
                    && previous.offset() == replacement.offset())) {
                throw new IOException(
                        "Обнаружены пересекающиеся или некорректные замены."
                );
            }

            result.write(
                    originalBytes,
                    position,
                    replacement.offset() - position
            );

            result.writeBytes(
                    replacement.text().getBytes(StandardCharsets.UTF_8)
            );

            position = (int) end;
            previous = replacement;
        }

        result.write(
                originalBytes,
                position,
                originalBytes.length - position
        );

        return normalize(decodeUtf8(result.toByteArray()));
    }

    private static void collectReplacements(
            Object node,
            Path sourceFile,
            Set<Replacement> replacements
    ) throws IOException {
        if (node instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if ("Replacements".equals(entry.getKey())
                        && entry.getValue() instanceof List<?> list) {
                    for (Object item : list) {
                        if (!(item instanceof Map<?, ?> replacement)) {
                            throw new IOException(
                                    "Некорректный формат замены."
                            );
                        }

                        Object pathValue = replacement.get("FilePath");
                        Object offsetValue = replacement.get("Offset");
                        Object lengthValue = replacement.get("Length");
                        Object textValue =
                                replacement.get("ReplacementText");

                        if (!(pathValue instanceof String path)
                                || !(offsetValue instanceof Number offset)
                                || !(lengthValue instanceof Number length)
                                || !(textValue instanceof String text)) {
                            throw new IOException(
                                    "В замене отсутствуют необходимые поля."
                            );
                        }

                        Path target = Path.of(path);

                        if (!target.isAbsolute()
                                || !Files.isSameFile(target, sourceFile)) {
                            throw new IOException(
                                    "Исправления затрагивают другие файлы. "
                                            + "Эта версия поддерживает "
                                            + "только открытый файл."
                            );
                        }

                        replacements.add(new Replacement(
                                Math.toIntExact(offset.longValue()),
                                Math.toIntExact(length.longValue()),
                                text
                        ));
                    }
                } else {
                    collectReplacements(
                            entry.getValue(), sourceFile, replacements
                    );
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                collectReplacements(item, sourceFile, replacements);
            }
        }
    }

    private static void applyChanges(
            Project project,
            Document document,
            Path sourceFile,
            byte[] originalBytes,
            long originalStamp,
            String correctedText
    ) {
        try {
            if (!document.isWritable()
                    || document.getModificationStamp() != originalStamp
                    || !Arrays.equals(
                    originalBytes, Files.readAllBytes(sourceFile)
            )) {
                Messages.showErrorDialog(
                        project,
                        "Файл изменился после анализа или недоступен "
                                + "для записи. Запустите проверку заново.",
                        "Check Lab Naming"
                );
                return;
            }

            WriteCommandAction.runWriteCommandAction(
                    project,
                    () -> document.setText(correctedText)
            );

        } catch (IOException exception) {
            Messages.showErrorDialog(
                    project,
                    exception.getMessage(),
                    "Check Lab Naming"
            );
        }
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);

        if (!Arrays.equals(
                bytes, text.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new IOException("Файл содержит некорректный UTF-8.");
        }

        return text;
    }

    private static String normalize(String text) {
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }

        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static void showError(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                Messages.showErrorDialog(
                        project,
                        message == null ? "Неизвестная ошибка." : message,
                        "Check Lab Naming"
                );
            }
        });
    }

    private static void deleteTemporaryFile(Path file) {
        if (file == null) {
            return;
        }

        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            file.toFile().deleteOnExit();
        }
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        String extension = file == null ? null : file.getExtension();

        boolean cppFile = extension != null
                && Set.of("cpp", "cc", "cxx").contains(
                extension.toLowerCase(Locale.ROOT)
        );

        event.getPresentation().setEnabled(
                event.getProject() != null
                        && file != null
                        && file.isInLocalFileSystem()
                        && !file.isDirectory()
                        && file.isWritable()
                        && cppFile
        );
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    private record Replacement(int offset, int length, String text) {
    }

    private static class ResultDialog extends DialogWrapper {

        private final String text;
        private final Runnable apply;

        ResultDialog(Project project, String text, Runnable apply) {
            super(project);
            this.text = text;
            this.apply = apply;

            setTitle("Check Lab Naming");
            setOKButtonText("Применить исправления");
            setCancelButtonText("Закрыть");
            init();
        }

        @Override
        protected JComponent createCenterPanel() {
            JTextArea area = new JTextArea(text);
            area.setEditable(false);
            area.setCaretPosition(0);

            JScrollPane scrollPane = new JScrollPane(area);
            scrollPane.setPreferredSize(new Dimension(850, 500));

            JPanel panel = new JPanel(new BorderLayout());
            panel.add(scrollPane, BorderLayout.CENTER);

            return panel;
        }

        @Override
        protected Action[] createActions() {
            if (apply == null) {
                return new Action[]{getCancelAction()};
            }

            return new Action[]{getOKAction(), getCancelAction()};
        }

        @Override
        protected void doOKAction() {
            if (apply != null) {
                apply.run();
            }

            super.doOKAction();
        }
    }
}