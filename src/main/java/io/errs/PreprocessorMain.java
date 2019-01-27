package io.errs;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.HashMap;
import java.util.HashSet;

public class PreprocessorMain {
    public static void main(String[] args) throws IOException {
        Preprocessor pp =
                new Preprocessor(
                        new HashMap<>() {{ put("student", 1); }},
                        new FileReader("/Users/michael/programming/preprocessor/preprocessor_test.small.java"),
                        //new FileReader("/Users/michael/programming/preprocessor/SimpleTest"),
                        new OutputStreamWriter(System.out));

        pp.process();
        System.out.flush();
    }
}
