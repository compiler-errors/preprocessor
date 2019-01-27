package io.errs;


import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Goal which pre-processes sources.
 */
@Mojo(name = "preprocessmagic", defaultPhase = LifecyclePhase.PROCESS_SOURCES)
public class PreprocessorMojo extends AbstractMojo {
    /**
     * Location of the file.
     */
    @Parameter(defaultValue = "${project.build.directory}", property = "outputDir", required = true)
    private File outputDirectory;

    /**
     * Location of the file.
     */
    @Parameter(defaultValue = "${project.build.sourceDirectory}", property = "outputDir", required = true)
    private File inputDirectory;

    public void execute() {
        try {
            getLog().info("Input directory: " + inputDirectory.getCanonicalPath());
            getLog().info("Output directory: " + outputDirectory.getCanonicalPath());

            List<File> inputSources = getSources(inputDirectory);
            for (File f : inputSources) {
                getLog().info("Input file: " + f.getCanonicalPath());
                getLog().info("Output file: " + rewritePath(f.getCanonicalPath()));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
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
