package io.errs;


import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Goal which pre-processes sources.
 */
@Mojo(name = "preprocessor", defaultPhase = LifecyclePhase.PROCESS_SOURCES)
public class PreprocessorMojo extends AbstractMojo {
    /**
     * Location of the file.
     */
    @Parameter(defaultValue = "${project.build.directory}/preprocessed-sources/", property = "outputDir", required = true)
    private File outputDirectory;

    /**
     * Location of the file.
     */
    @Parameter(defaultValue = "${project.build.sourceDirectory}", property = "outputDir", required = true)
    private File inputDirectory;

    public void execute() {
        HashMap<String, Integer> globalContext = new HashMap<>();

        try {
            getLog().info("Input directory: " + inputDirectory.getCanonicalPath());
            getLog().info("Output directory: " + outputDirectory.getCanonicalPath());

            List<String> excludedPaths = new ArrayList<>();
            List<File> inputSources = getSources(inputDirectory);

            outer: for (File inputFile : inputSources) {
                String inputPath = inputFile.getCanonicalPath();
                String outputPath = rewritePath(inputPath);

                for (String excluded : excludedPaths) {
                    if (inputFile.toPath().startsWith(excluded)) {
                        getLog().debug("Excluding file '" + inputPath + "' because excluded path '" + excluded + "'");
                        continue outer;
                    }
                }

                getLog().debug("Input file: " + inputPath);
                getLog().debug("Output file: " + outputPath);

                File outputFile = new File(outputPath);
                outputFile.getParentFile().mkdirs();

                Preprocessor p = new Preprocessor(globalContext, new FileReader(inputFile), new FileWriter(outputFile));
                try {
                    p.process();
                } catch (DeleteFileException e) {
                    if (e.isDeleteFullPackage()) {
                        String outputParentDirectory = rewritePath(inputFile.getParentFile().getCanonicalPath());
                        getLog().debug("Deleting package: " + outputParentDirectory);

                        excludedPaths.add(outputParentDirectory);
                        deleteRecursively(outputParentDirectory);
                    } else {
                        getLog().debug("Deleting file: " + outputPath);
                        deleteRecursively(outputPath);
                    }
                } catch (PreprocessorException e) {
                    // Just report an error.
                    getLog().error(e);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void deleteRecursively(String fileOrDir) throws IOException {
        Path path = Paths.get(fileOrDir);

        if (!Files.exists(path)) {
            return;
        }

        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private List<File> getSources(File input) {
        if (input.isDirectory()) {
            ArrayList<File> files = new ArrayList<>();

            for (File f : input.listFiles()) {
                files.addAll(getSources(f));
            }

            return files;
        }

        if (input.getName().endsWith(".java"))
            return List.of(input);
        else
            return List.of();
    }

    private String rewritePath(String source) throws IOException {
        String oldBase = inputDirectory.getCanonicalPath();
        String newBase = outputDirectory.getCanonicalPath();

        if (source.startsWith(oldBase)) {
            return newBase + source.substring(oldBase.length());
        }

        // TODO: Handle paths more gracefully...
        throw new RuntimeException("For some reason, not a canonical path?");
    }
}
