package ru.student.cpptemplates;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.progress.ProgressIndicator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public final class LabCodeFormatter {

    private LabCodeFormatter() {
    }

    public static String format(
            String code,
            Path sourceFile,
            ProgressIndicator indicator
    ) throws IOException, InterruptedException {

        Path executable = Path.of(
                PathManager.getHomePath(),
                "plugins",
                "clion-radler",
                "DotFiles",
                "windows-x64",
                "clang-format.exe"
        );

        if (!Files.isRegularFile(executable)) {
            throw new IOException(
                    "Не найден Clang-Format:\n" + executable
            );
        }

        String style = """
                {BasedOnStyle: Google, \
                IndentWidth: 4, \
                ContinuationIndentWidth: 4, \
                TabWidth: 4, \
                UseTab: Never, \
                AccessModifierOffset: -4, \
                AllowShortFunctionsOnASingleLine: None}
                """.strip();

        Path input = null;
        Path output = null;
        Path errors = null;
        Process process = null;

        try {
            input = Files.createTempFile("lab-format-input-", ".cpp");
            output = Files.createTempFile("lab-format-output-", ".cpp");
            errors = Files.createTempFile("lab-format-errors-", ".txt");

            Files.writeString(input, code, StandardCharsets.UTF_8);

            ProcessBuilder builder = new ProcessBuilder(
                    executable.toString(),
                    "--style=" + style,
                    "--assume-filename=" + sourceFile,
                    "--fail-on-incomplete-format"
            );

            builder.directory(sourceFile.getParent().toFile());
            builder.redirectInput(input.toFile());
            builder.redirectOutput(output.toFile());
            builder.redirectError(errors.toFile());

            indicator.setText("Форматирование кода...");
            process = builder.start();

            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(30);

            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                indicator.checkCanceled();

                if (System.nanoTime() > deadline) {
                    throw new IOException(
                            "Форматирование не завершилось за 30 секунд."
                    );
                }
            }

            indicator.checkCanceled();

            if (process.exitValue() != 0) {
                String message = Files.readString(
                        errors, StandardCharsets.UTF_8
                );

                throw new IOException(
                        "Ошибка Clang-Format:\n" + message
                );
            }

            return Files.readString(
                    output, StandardCharsets.UTF_8
            );

        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }

            deleteTemporaryFile(input);
            deleteTemporaryFile(output);
            deleteTemporaryFile(errors);
        }
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
}