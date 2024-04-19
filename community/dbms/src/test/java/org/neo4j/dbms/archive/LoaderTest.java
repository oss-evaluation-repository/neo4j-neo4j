/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [https://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.neo4j.dbms.archive;

import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.neo4j.configuration.GraphDatabaseSettings.initial_default_database;
import static org.neo4j.configuration.GraphDatabaseSettings.neo4j_home;
import static org.neo4j.configuration.GraphDatabaseSettings.transaction_logs_root_path;
import static org.neo4j.dbms.archive.TestUtils.withPermissions;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.neo4j.configuration.Config;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.test.extension.DisabledForRoot;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.Neo4jLayoutExtension;
import org.neo4j.test.utils.TestDirectory;

@Neo4jLayoutExtension
class LoaderTest {
    @Inject
    private TestDirectory testDirectory;

    @Inject
    private FileSystemAbstraction fileSystem;

    @Inject
    private DatabaseLayout databaseLayout;

    @Test
    void shouldGiveAClearErrorMessageIfTheArchiveDoesntExist() throws IOException {
        Path archive = testDirectory.file("the-archive.dump");

        deleteLayoutFolders(databaseLayout);

        NoSuchFileException exception =
                assertThrows(NoSuchFileException.class, () -> new Loader(fileSystem).load(databaseLayout, archive));
        assertEquals(archive.toString(), exception.getMessage());
    }

    @Test
    void shouldGiveAClearErrorMessageIfTheArchiveIsNotInGzipFormat() throws IOException {
        Path archive = testDirectory.file("the-archive.dump");
        try (var outputStream = fileSystem.openAsOutputStream(archive, false);
                var out = new PrintStream(outputStream)) {
            out.println("some incorrectly formatted data");
        }

        deleteLayoutFolders(databaseLayout);

        var incorrectFormat =
                assertThrows(IncorrectFormat.class, () -> new Loader(fileSystem).load(databaseLayout, archive));
        assertEquals(archive.toString(), incorrectFormat.getMessage());
    }

    @Test
    void shouldGiveAClearErrorMessageIfTheArchiveIsNotInTarFormat() throws IOException {
        Path archive = testDirectory.file("the-archive.dump");
        try (GzipCompressorOutputStream compressor =
                new GzipCompressorOutputStream(fileSystem.openAsOutputStream(archive, false))) {
            byte[] bytes = new byte[1000];
            new Random().nextBytes(bytes);
            compressor.write(bytes);
        }

        deleteLayoutFolders(databaseLayout);

        var incorrectFormat =
                assertThrows(IncorrectFormat.class, () -> new Loader(fileSystem).load(databaseLayout, archive));
        assertEquals(archive.toString(), incorrectFormat.getMessage());
    }

    @Test
    void shouldGiveAClearErrorMessageIfTheArchiveEntryPointsToRandomPlace() throws IOException {
        Path archive = testDirectory.file("the-archive.dump");

        deleteLayoutFolders(databaseLayout);

        final Path testFile = testDirectory.file("testFile");
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(
                new GzipCompressorOutputStream(fileSystem.openAsOutputStream(archive, false)))) {
            var archiveEntry = tar.createArchiveEntry(testFile.toFile(), "../../../../etc/shadow");
            tar.putArchiveEntry(archiveEntry);
            tar.closeArchiveEntry();
        }
        final InvalidDumpEntryException exception = assertThrows(
                InvalidDumpEntryException.class, () -> new Loader(fileSystem).load(databaseLayout, archive));
        assertThat(exception.getMessage()).contains("points to a location outside of the destination database.");
    }

    @Test
    void shouldGiveAClearErrorIfTheDestinationTxLogAlreadyExists() throws IOException {
        Path archive = testDirectory.file("the-archive.dump");

        fileSystem.deleteRecursively(databaseLayout.databaseDirectory());
        assertTrue(fileSystem.isDirectory(databaseLayout.getTransactionLogsDirectory()));

        FileAlreadyExistsException exception = assertThrows(
                FileAlreadyExistsException.class, () -> new Loader(fileSystem).load(databaseLayout, archive));
        assertEquals(databaseLayout.getTransactionLogsDirectory().toString(), exception.getMessage());
    }

    @Test
    void shouldGiveAClearErrorMessageIfTheDestinationsParentDirectoryDoesntExist() {
        Path archive = testDirectory.file("the-archive.dump");
        Path destination = Paths.get(testDirectory.absolutePath().toString(), "subdir", "the-destination");
        DatabaseLayout databaseLayout = DatabaseLayout.ofFlat(destination);

        NoSuchFileException noSuchFileException =
                assertThrows(NoSuchFileException.class, () -> new Loader(fileSystem).load(databaseLayout, archive));
        assertEquals(destination.getParent().toString(), noSuchFileException.getMessage());
    }

    @Test
    void shouldGiveAClearErrorMessageIfTheTxLogsParentDirectoryDoesntExist() throws IOException {
        Path archive = testDirectory.file("the-archive.dump");
        Path txLogsDestination = Paths.get(testDirectory.absolutePath().toString(), "subdir", "txLogs");
        Config config = Config.newBuilder()
                .set(neo4j_home, testDirectory.homePath())
                .set(transaction_logs_root_path, txLogsDestination.toAbsolutePath())
                .set(initial_default_database, "destination")
                .build();
        DatabaseLayout databaseLayout = DatabaseLayout.of(config);
        fileSystem.deleteRecursively(txLogsDestination);
        NoSuchFileException noSuchFileException =
                assertThrows(NoSuchFileException.class, () -> new Loader(fileSystem).load(databaseLayout, archive));
        assertEquals(txLogsDestination.toString(), noSuchFileException.getMessage());
    }

    @Test
    void shouldGiveAClearErrorMessageIfTheDestinationsParentDirectoryIsAFile() throws IOException {
        Path archive = testDirectory.file("the-archive.dump");
        Path destination = Paths.get(testDirectory.absolutePath().toString(), "subdir", "the-destination");
        try (var outputStream = fileSystem.openAsOutputStream(archive, false);
                var out = new PrintStream(outputStream)) {
            out.println("some incorrectly formatted data");
        }
        fileSystem.write(destination.getParent()).close();
        DatabaseLayout databaseLayout = DatabaseLayout.ofFlat(destination);

        FileSystemException exception =
                assertThrows(FileSystemException.class, () -> new Loader(fileSystem).load(databaseLayout, archive));
        assertEquals(destination.getParent() + ": Not a directory", exception.getMessage());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisabledForRoot
    void shouldGiveAClearErrorMessageIfTheDestinationsParentDirectoryIsNotWritable() throws IOException {
        Path archive = testDirectory.file("the-archive.dump");
        Path destination = testDirectory.directory("subdir/the-destination");
        DatabaseLayout databaseLayout = DatabaseLayout.ofFlat(destination);

        Path parentPath = databaseLayout.databaseDirectory().getParent();
        try (Closeable ignored = withPermissions(parentPath, emptySet())) {
            AccessDeniedException exception = assertThrows(
                    AccessDeniedException.class, () -> new Loader(fileSystem).load(databaseLayout, archive));
            assertEquals(parentPath.toString(), exception.getMessage());
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisabledForRoot
    void shouldGiveAClearErrorMessageIfTheTxLogsParentDirectoryIsNotWritable() throws IOException {
        Path archive = testDirectory.file("the-archive.dump");
        Path txLogsDirectory = testDirectory.directory("subdir", "txLogs");
        Config config = Config.newBuilder()
                .set(neo4j_home, testDirectory.homePath())
                .set(transaction_logs_root_path, txLogsDirectory.toAbsolutePath())
                .set(initial_default_database, "destination")
                .build();
        DatabaseLayout databaseLayout = DatabaseLayout.of(config);

        Path txLogsRoot = databaseLayout.getTransactionLogsDirectory().getParent();
        try (Closeable ignored = withPermissions(txLogsRoot, emptySet())) {
            AccessDeniedException exception = assertThrows(
                    AccessDeniedException.class, () -> new Loader(fileSystem).load(databaseLayout, archive));
            assertEquals(txLogsRoot.toString(), exception.getMessage());
        }
    }

    private void deleteLayoutFolders(DatabaseLayout databaseLayout) throws IOException {
        fileSystem.deleteRecursively(databaseLayout.databaseDirectory());
        fileSystem.deleteRecursively(databaseLayout.getTransactionLogsDirectory());
    }
}
