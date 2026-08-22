package com.labdms.infrastructure.adapter.out.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Converts EPS figures to PDF with Ghostscript, at upload time.
 *
 * <p>pdflatex cannot read EPS. The usual answer is the {@code epstopdf} package,
 * which shells out during compilation — but that requires shell-escape, which is
 * deliberately disabled so a crafted document cannot run commands. Converting on
 * the server sidesteps that entirely: the conversion happens once, under our
 * control, and the compiler stays locked down.
 *
 * <p>It also makes projects dramatically smaller. Measured on a real thesis:
 * capa.eps 11.84 MB became 1.45 MB (-88%) and fig01.eps 2.09 MB became 0.04 MB
 * (-98%); the whole project went from 15.9 MB to 1.97 MB.
 */
@Component
public class EpsConverter {

    private static final Logger logger = LoggerFactory.getLogger(EpsConverter.class);

    @Value("${latex.eps-conversion-timeout-seconds:120}")
    private int timeoutSeconds;

    /**
     * @return the PDF bytes, or {@code null} when conversion is not possible —
     *         the caller then keeps the original EPS rather than losing the file
     */
    public byte[] toPdf(byte[] epsBytes, String fileName) {
        Path work = null;
        try {
            work = Files.createTempDirectory("eps-");
            Path input = work.resolve("input.eps");
            Path output = work.resolve("output.pdf");
            Files.write(input, epsBytes);

            ProcessBuilder pb = new ProcessBuilder(
                    "gs", "-dNOPAUSE", "-dBATCH", "-dQUIET", "-dSAFER",
                    "-sDEVICE=pdfwrite",
                    // Crops to the figure's BoundingBox; without it the PDF is a
                    // full page and the figure lands in a corner.
                    "-dEPSCrop",
                    "-sOutputFile=" + output.toAbsolutePath(),
                    input.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);

            Process process = pb.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                logger.warn("EPS conversion timed out for {}; keeping the original", fileName);
                return null;
            }

            if (!Files.exists(output) || Files.size(output) == 0) {
                logger.warn("Ghostscript produced no output for {}; keeping the original", fileName);
                return null;
            }
            return Files.readAllBytes(output);

        } catch (Exception e) {
            // A figure that will not convert must not fail the whole import.
            logger.warn("Could not convert {} from EPS: {}", fileName, e.getMessage());
            return null;
        } finally {
            deleteRecursively(work);
        }
    }

    public boolean isAvailable() {
        try {
            Process p = new ProcessBuilder("gs", "--version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }
}
