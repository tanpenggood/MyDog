package com.mydog.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Loads the {@code SHOW CREATE TABLE} texts captured from the docker MySQL 8.0 sandbox.
 *
 * <p>These are server output, not hand-written DDL, which is the whole point: the parser and the
 * differ have to cope with exactly what MySQL prints — backticks everywhere, one item per line,
 * the auto-increment watermark, and 8.0's collation names.
 */
public final class Fixtures {

    private Fixtures() {
    }

    public static String read(String name) {
        URL url = Objects.requireNonNull(Fixtures.class.getResource("/com/mydog/metadata/" + name),
                "missing fixture " + name);
        try {
            return Files.readString(Path.of(url.toURI()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
