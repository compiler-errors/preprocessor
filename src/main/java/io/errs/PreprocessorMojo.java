package io.errs;


import org.apache.maven.plugin.AbstractMojo;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.FileUtils;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

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
    @Parameter(defaultValue = "${project.basedir}/src/", property = "inputDir", required = true)
    private File inputDirectory;

    @Parameter(property = "preprocessor.vars")
    private Properties variableStrings;

    public void execute() {
        for (Map.Entry<Object, Object> property : System.getProperties().entrySet()) {
            Object key = property.getKey();

            if (key instanceof String) {
                String keyStr = (String) key;
                if (keyStr.startsWith("preprocessor.vars.")) {
                    variableStrings.put(keyStr.substring("preprocessor.vars.".length()), property.getValue());
                }
            }
        }

        Map<String, Integer> variableValues = truthifyVariables(variableStrings);

        getLog().debug("- BEGIN Preprocessor variableStrings -");
        for (Map.Entry<String, Integer> var : variableValues.entrySet()) {
            getLog().debug(String.format("%s -> %s", var.getKey(), var.getValue()));
        }
        getLog().debug("- END preprocessor variableStrings -");

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

                if (!outputFile.getParentFile().mkdirs()) {
                    // TODO: Error!
                }

                if (inputPath.endsWith(".java")) {
                    getLog().debug("Processing file.");
                    // Preprocess a java file...

                    Preprocessor p = new Preprocessor(variableValues, new FileReader(inputFile), new FileWriter(outputFile));
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
                        throw new RuntimeException(e);
                    }
                } else {
                    // Otherwise, just copy...
                    getLog().debug("Copying file.");

                    FileUtils.copyFile(inputFile, outputFile);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Map<String, Integer> truthifyVariables(Map<Object, Object> variables) {
        HashMap<String, Integer> values = new HashMap<String, Integer>();

        for (Map.Entry<Object, Object> variable : variables.entrySet()) {
            // TODO: I'm assuming these values are... already strings...
            String k = variable.getKey().toString(), v = variable.getValue().toString();
            int i;

            try {
                i = Integer.parseInt(v);
            } catch (NumberFormatException nfe) {
                switch (v.toLowerCase()) {
                    case "true":
                    case "yes":
                    case "on":
                        i = 1;
                        break;
                    case "false":
                    case "no":
                    case "off":
                        i = 0;
                        break;
                    default:
                        getLog().warn(String.format("Variable '%s' -- Unknown value '%s', defaulting to 1", k, v));
                        i = 1;
                        break;
                }
            }

            values.put(k, i);
        }

        return values;
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
        } else
            return List.of(input);
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
