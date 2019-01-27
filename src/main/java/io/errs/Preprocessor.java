package io.errs;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

import java.io.*;
import java.util.*;

public class Preprocessor {
    static int LOOKBACK_SIZE = 5;

    private Map<String, Integer> globalContext;
    private Reader in;
    private Writer out;

    // Line contextual information, for the parsing phase.
    private StringBuilder lineBuilder = new StringBuilder();
    private StringBuilder commentBuilder = new StringBuilder();
    private ArrayDeque<Character> lookback = new ArrayDeque<>(LOOKBACK_SIZE);

    // Enums that describe contextful parsing modes (such as reading strings) and commands.
    enum ReadMode {REGULAR, BLOCK_COMMENT, LINE_COMMENT, STRING}
    enum IncludeCommand {NONE, INCLUDE_LINE, REMOVE_LINE, INCLUDE_UNTIL, REMOVE_UNTIL, INCLUDE_COMMENT, INCLUDE_TODO_COMMENT, UNCOMMENT, END}

    // Command switching information.
    private ReadMode readMode = ReadMode.REGULAR;
    private IncludeCommand command = IncludeCommand.NONE;
    private boolean commandConditional = true;
    private boolean commandComment = false;

    // Expected delimiter if we're currently inside of a string-like (char or string) literal.
    private char expectedDelimiter = '\0';

    public Preprocessor(HashMap<String, Integer> globalContext, Reader in, Writer out) {
        this.globalContext = new HashMap<>(globalContext);
        this.in = in;
        this.out = out;
    }

    void process() throws IOException {
        // Last "character" (or EOF) that we have read.
        int read;

        while ((read = in.read()) != -1) {
            char c = (char) read;

            // We append the character stream to one of two `StringBuilder`s, depending on the mode.
            // The command that is present in any comment will affect whether the contents of the command
            // are folded into the lineBuilder, or are thrown away.
            if (isCommentMode()) {
                commentBuilder.append(c);
            } else {
                lineBuilder.append(c);
            }

            // Push onto the lookback queue, removing one char if we are at max size.
            if (lookback.size() == LOOKBACK_SIZE) {
                lookback.removeFirst();
            }
            lookback.addLast(c);

            switch (readMode) {
                case REGULAR: {
                    /* In regular mode, we just read normally, unless we hit:
                     *
                     * a quotation mark -> switch into "string" mode.
                     * the beginning of a block comment -> switch into "block comment" mode.
                     * the beginning of a single-line comment -> switch into "single line" mode.
                     * a newline -> flush the line buffer. */

                    if (c == '\"') {
                        expectedDelimiter = '\"';
                        changeReadMode(ReadMode.STRING);
                    }

                    if (c == '\'') {
                        expectedDelimiter = '\'';
                        changeReadMode(ReadMode.STRING);
                    }

                    if (c == '*' && lookbackMatches("/*")) {
                        // Delete two characters off of the line.
                        lineBuilder.deleteCharAt(lineBuilder.length() - 1);
                        lineBuilder.deleteCharAt(lineBuilder.length() - 1);

                        // Put the comment characters onto the commentBuilder.
                        commentBuilder.append("/*");

                        changeReadMode(ReadMode.BLOCK_COMMENT);
                    }

                    if (c == '/' && lookbackMatches("//")) {
                        // Delete two characters off of the line.
                        lineBuilder.deleteCharAt(lineBuilder.length() - 1);
                        lineBuilder.deleteCharAt(lineBuilder.length() - 1);

                        // Put the comment characters onto the commentBuilder.
                        commentBuilder.append("//");

                        changeReadMode(ReadMode.LINE_COMMENT);
                    }

                    // Flush the line buffer, which depending on any existing commands might cause
                    // us to do something special, such as throw away the contents of the buffer...
                    if (c == '\n') {
                        flushLine();
                    }
                }
                break;

                case STRING: {
                    /* Inside of a string, we read characters until we hit the end,
                     * which is characterized by the `expectedDelimiter`. If we hit
                     * the expected delimiter character, then break the mode if we
                     * are preceded by an EVEN number of backslashes, including 0. */

                    if (c == expectedDelimiter) {
                        // We need full-string lookback for this one.
                        String line = lineBuilder.toString();

                        int backslashes = 0;
                        for (int i = line.length() - 2; i >= 0; i--) {
                            if (line.charAt(i) == '\\')
                                backslashes++;
                            else
                                break;
                        }

                        if (backslashes % 2 == 0) {
                            changeReadMode(ReadMode.REGULAR);
                            expectedDelimiter = '\0';
                        }
                    }
                }
                break;

                case BLOCK_COMMENT: {
                    // If we start the comment off with a `~`, then we should try to read a command!
                    if (c == '~' && lookback.size() == 1) {
                        commandComment = true;

                        // Try to emit the previous line if it has meaningful contents.
                        // i.e. commands that have code preceding them will act as if they
                        // had a line-break at the beginning.
                        if (shouldEmitPartialLine()) {
                            lineBuilder.append('\n');
                            flushLine();
                        }

                        parseCommand();
                    }

                    // Finally, when we reach the end of the command, flush the comment buffer.
                    // This also cleans up and processes any comment-specific commands.
                    if (c == '/' && lookbackMatches("*/")) {
                        flushComment();
                        changeReadMode(ReadMode.REGULAR);
                    }
                }
                break;

                case LINE_COMMENT: {
                    if (c == '~' && lookback.size() == 1) {
                        commandComment = true;

                        if (shouldEmitPartialLine()) {
                            lineBuilder.append('\n');
                            flushLine();
                        }

                        parseCommand();
                    }

                    // If we hit an inline comment, then process it in a special way.
                    if (c == '~' && lookbackMatches("<~") && lookback.size() == 2) {
                        commandComment = true;

                        // Can't have any other commands running here...
                        if (command != IncludeCommand.NONE)
                            throw new UnsupportedOperationException("Cannot use inline comments with any other command active.");

                        parseCommand();

                        // We can handle INCLUDE_LINE and REMOVE_LINE right now, by throwing away the lineBuilder.
                        switch (command) {
                            case INCLUDE_LINE:
                            case REMOVE_LINE: {
                                lineBuilder.append('\n');
                                flushLine();
                            } break;
                            default: {
                                throw new UnsupportedOperationException("The command '" + command + "' does not support inline mode.");
                            }
                        }

                        // Finally, use the END command to ignore the rest of this comment block.
                        command = IncludeCommand.END;
                    }

                    if (c == '\n' /* TODO: Make this more OS-agnostic. */) {
                        /* Flushing a line comment is line flushing a block comment, but with an EOL too! */

                        // Flush this "block comment"...
                        flushComment();
                        changeReadMode(ReadMode.REGULAR);

                        flushLine();
                    }
                }
                break;
            }
        }

        out.flush();
    }

    /* This method is used to determine whether or not to emit the line
     * contents preceding a command.
     *
     * Is it meaningful to emit this line? If it's just spaces, then no. */
    private boolean shouldEmitPartialLine() {
        for (char c : lineBuilder.toString().toCharArray()) {
            if (c != '\t' && c != ' ') {
                return true;
            }
        }

        return false;
    }

    /* Do the last characters of the lookback match this string? */
    private boolean lookbackMatches(String s) {
        assert (s.length() <= LOOKBACK_SIZE);

        Iterator<Character> it = lookback.descendingIterator();

        for (int i = s.length() - 1; i >= 0; i--) {
            if (it.hasNext()) {
                if (it.next() != s.charAt(i)) {
                    return false;
                }
            } else {
                return false;
            }
        }

        return true;
    }

    private void changeReadMode(ReadMode mode) {
        this.readMode = mode;
        lookback.clear();
    }

    private boolean isCommentMode() {
        return readMode == ReadMode.BLOCK_COMMENT || readMode == ReadMode.LINE_COMMENT;
    }

    // Parse until the next `.`, and lowercase the string.
    private String readCommand() throws IOException {
        StringBuilder ret = new StringBuilder();
        int next;

        while ((next = in.read()) != -1) {
            char c = (char) next;

            ret.append(c);

            if (c == '.') {
                return ret.toString().toLowerCase();
            }

            if (c == '\n') {
                throw new UnsupportedOperationException("Command must end in a `.`!");
            }
        }

        throw new UnsupportedOperationException("Reached the end of the file without a full comment!");
    }

    /* Flush a line, conditionally emitting it if the current command instructs us to do so. */
    private void flushLine() throws IOException {
        boolean shouldEmitLine = true;

        switch (command) {
            case INCLUDE_LINE: {
                shouldEmitLine = commandConditional;
                command = IncludeCommand.NONE;
            }
            break;
            case REMOVE_LINE: {
                shouldEmitLine = !commandConditional;
                command = IncludeCommand.NONE;
            }
            break;
            case INCLUDE_UNTIL: {
                shouldEmitLine = commandConditional;
            }
            break;
            case REMOVE_UNTIL: {
                shouldEmitLine = !commandConditional;
            } break;
            case NONE: {
                // Do nothing, intentionally.
            } break;
            default:
                throw new UnsupportedOperationException("Uncaught command in flushLine: " + command);
        }

        if (shouldEmitLine) {
            out.write(lineBuilder.toString());
            out.flush();
        }

        lineBuilder = new StringBuilder();
    }

    // Flush the comment. If it's not a command, just append it.
    // Otherwise, possibly process it.
    private void flushComment() throws IOException {
        if (!commandComment) {
            lineBuilder.append(commentBuilder);
        }

        switch (command) {
            case UNCOMMENT: {
                if (commandConditional) {
                    String comment = commentBuilder.toString();

                    // Uncomment commands need to have their "comment" delimiters chopped off...
                    if (comment.startsWith("/*")) {
                        comment = comment.substring(2, comment.length() - 2);
                    } else {
                        // TODO: Trimming might be unnecessary or unwanted?
                        comment = comment.substring(3).trim() + '\n';
                    }

                    lineBuilder.append(comment);
                }

                command = IncludeCommand.NONE;
            }
            break;
            case INCLUDE_COMMENT: {
                if (commandConditional) {
                    // Chop out the `~` from the command, but keep the command delimiter.
                    lineBuilder.append(commentBuilder.substring(0, 2) + commentBuilder.substring(3));
                }

                command = IncludeCommand.NONE;
            } break;
            case INCLUDE_TODO_COMMENT: {
                if (commandConditional) {
                    // Chop out the `~` from inside.
                    lineBuilder.append(commentBuilder.substring(0, 2) + " TODO:" + commentBuilder.substring(3));
                }

                // Also include the exception so methods that return non-void don't have compiler errors...
                lineBuilder.append("throw new UnsupportedOperationException(\"TODO: Implement me!\");\n");
                command = IncludeCommand.NONE;
            } break;
            case END: {
                // Don't do anything, but ALSO throw away the comment.
                command = IncludeCommand.NONE;
            }
            break;
        }

        commentBuilder = new StringBuilder();
        commandComment = false;
    }

    private void parseCommand() throws IOException {
        command = null;
        commandConditional = false;

        String command = readCommand();
        CharStream cs = CharStreams.fromString(command);
        PreprocessorCommandLexer lexer = new PreprocessorCommandLexer(cs);
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        PreprocessorCommandParser parser = new PreprocessorCommandParser(tokens);
        parser.setErrorHandler(new BailErrorStrategy());

        PreprocessorCommandParser.CommandContext ctx = parser.command();

        ParseTreeWalker ptw = new ParseTreeWalker();
        ptw.walk(new CommandParser(), ctx);
    }

    class CommandParser extends PreprocessorCommandBaseListener {
        @Override
        public void enterInclude_until_action(PreprocessorCommandParser.Include_until_actionContext ctx) {
            command = IncludeCommand.INCLUDE_UNTIL;
        }

        @Override
        public void enterInclude_line_action(PreprocessorCommandParser.Include_line_actionContext ctx) {
            command = IncludeCommand.INCLUDE_LINE;
        }

        @Override
        public void enterRemove_until_action(PreprocessorCommandParser.Remove_until_actionContext ctx) {
            command = IncludeCommand.REMOVE_UNTIL;
        }

        @Override
        public void enterRemove_line_action(PreprocessorCommandParser.Remove_line_actionContext ctx) {
            command = IncludeCommand.REMOVE_LINE;
        }

        @Override
        public void enterInclude_comment_action(PreprocessorCommandParser.Include_comment_actionContext ctx) {
            command = IncludeCommand.INCLUDE_COMMENT;
        }

        @Override
        public void enterInclude_todo_action(PreprocessorCommandParser.Include_todo_actionContext ctx) {
            command = IncludeCommand.INCLUDE_TODO_COMMENT;
        }

        @Override
        public void enterUncomment_action(PreprocessorCommandParser.Uncomment_actionContext ctx) {
            command = IncludeCommand.UNCOMMENT;
        }

        @Override
        public void enterEnd_action(PreprocessorCommandParser.End_actionContext ctx) {
            command = IncludeCommand.END;
        }

        @Override
        public void enterCommand(PreprocessorCommandParser.CommandContext ctx) {
            if (ctx.conditional() != null) {
                commandConditional = new ConditionalVisitor().visit(ctx.conditional()) > 0;
            }
        }
    }

    class ConditionalVisitor extends PreprocessorCommandBaseVisitor<Integer> {
        @Override
        protected Integer defaultResult() {
            throw new UnsupportedOperationException("Cannot process conditional...");
        }

        @Override
        public Integer visitLiteral_conditional(PreprocessorCommandParser.Literal_conditionalContext ctx) {
            return Integer.parseInt(ctx.LITERAL().getText());
        }

        @Override
        public Integer visitVariable_conditional(PreprocessorCommandParser.Variable_conditionalContext ctx) {
            String variable = ctx.VARIABLE().getText().substring(1);

            if (globalContext.containsKey(variable)) {
                return globalContext.get(variable);
            }

            return 0;
        }

        @Override
        public Integer visitNegate_conditional(PreprocessorCommandParser.Negate_conditionalContext ctx) {
            return visit(ctx.conditional()) > 0 ? 0 : 1;
        }

        @Override
        public Integer visitParens_conditional(PreprocessorCommandParser.Parens_conditionalContext ctx) {
            return visit(ctx.conditional());
        }

        @Override
        public Integer visitBoolean_op_conditional(PreprocessorCommandParser.Boolean_op_conditionalContext ctx) {
            int left = visit(ctx.conditional(0));
            int right = visit(ctx.conditional(1));

            if (ctx.AND() != null) {
                return left > 0 && right > 0 ? 1 : 0;
            } else if (ctx.OR() != null) {
                return left > 0 || right > 0 ? 1 : 0;
            } else {
                throw new UnsupportedOperationException("Some other boolean operator?");
            }
        }

        @Override
        public Integer visitCompare_op_conditional(PreprocessorCommandParser.Compare_op_conditionalContext ctx) {
            int left = visit(ctx.conditional(0));
            int right = visit(ctx.conditional(1));

            if (ctx.EQ() != null) {
                return left == right ? 1 : 0;
            } else if (ctx.NE() != null) {
                return left != right ? 1 : 0;
            } else if (ctx.LT() != null) {
                return left < right ? 1 : 0;
            } else if (ctx.GT() != null) {
                return left > right ? 1 : 0;
            } else if (ctx.LTE() != null) {
                return left <= right ? 1 : 0;
            } else if (ctx.GTE() != null) {
                return left >= right ? 1 : 0;
            } else {
                throw new UnsupportedOperationException("Some other comparison operator?");
            }
        }
    }
}
